package com.skywing.dgradio.model;

import com.google.common.collect.EvictingQueue;
import com.skywing.dgradio.job.AbstractRadioJob;
import com.skywing.dgradio.job.RefreshMediaStreamJob;
import com.skywing.dgradio.job.RefreshMediaUrlJob;
import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.auth.impl.http.SimpleHttpClient;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;

import java.net.URL;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
@Slf4j
public class RadioStation {

    private static final Pattern CURR_STREAM_PATTERN = Pattern.compile("curr_stream\\s*=\\s*\"(.*)\";");

    @NonNull
    private String name;

    @NonNull
    private String webUrl;

    @NonNull
    private int queueSize;

    @NonNull
    private Scheduler quartz;

    @NonNull
    private Vertx vertx;

    private String mediaUrl;

    private int version = 3;

    private long mediaSequence;

    private int targetDuration;

    private EvictingQueue<WrapMediaSegment> fifoQueue;

    private SimpleHttpClient httpClient;

    private MediaPlaylistParser parser = new MediaPlaylistParser(ParsingMode.LENIENT);

    public synchronized void startMe() {
        httpClient = new SimpleHttpClient(vertx,
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36",
                new HttpClientOptions());
        fifoQueue = EvictingQueue.create(queueSize);
        RefreshMediaUrlJob mediaUrlJob = new RefreshMediaUrlJob(name);
        addJob(mediaUrlJob);
        RefreshMediaStreamJob mediaStreamJob = new RefreshMediaStreamJob(name);
        addJob(mediaStreamJob);

        scheduleJob(mediaUrlJob, 0);
    }

    public synchronized void stopMe() {
        RefreshMediaUrlJob mediaUrlJob = new RefreshMediaUrlJob(name);
        removeJob(mediaUrlJob);
        RefreshMediaStreamJob mediaStreamJob = new RefreshMediaStreamJob(name);
        removeJob(mediaStreamJob);
    }

    public void refreshMediaUrl() {
        httpClient.fetch(HttpMethod.GET, webUrl, null, null)
                .onSuccess(event -> {
                    log.info("{} Success get MediaUrl from {}", name, webUrl);
                    String html = event.body().toString();
                    Matcher matcher = CURR_STREAM_PATTERN.matcher(html);
                    if (matcher.find()) {
                        String urlStr = matcher.group(1);
                        String protocol = "http";
                        try {
                            URL url = new URL(webUrl);
                            protocol = url.getProtocol();
                        } catch (Exception e) {
                            //do nothing
                        }
                        if (urlStr.startsWith("//")) {
                            urlStr = String.format("%s:%s", protocol, urlStr);
                        } else {
                            urlStr = String.format("%s://%s", protocol, urlStr);
                        }
                        mediaUrl = urlStr;
                        log.info("{} Set mediaUrl={}", name, mediaUrl);

                        int ttl = 1200;
                        long t = System.currentTimeMillis() / 1000;
                        try {
                            URL url = new URL(mediaUrl);
                            QueryStringDecoder decoder = new QueryStringDecoder(url.getQuery(), false);
                            ttl = Integer.valueOf(decoder.parameters().get("ttl").get(0));
                            t = Long.valueOf(decoder.parameters().get("t").get(0));
                        } catch (Exception e) {
                            //do nothing
                            log.error("{} compute delay failed: {}", name, e.toString(), e);
                        }

                        RefreshMediaUrlJob mediaUrlJob = new RefreshMediaUrlJob(name);
                        int seconds = (int) (t + ttl - (System.currentTimeMillis() / 1000) - 120);
                        if (seconds < 0) seconds = 0;
                        log.info("{} ttl={} t={} seconds={}", name, ttl, t, seconds);
                        scheduleJob(mediaUrlJob, seconds);

                        RefreshMediaStreamJob mediaStreamJob = new RefreshMediaStreamJob(name);
                        scheduleJob(mediaStreamJob, 0);
                    }
                })
            .onFailure(event -> {
                log.warn("{} Failed get MediaUrl from {} with {}", name, webUrl, event.toString());
                // retry 3 seconds
                RefreshMediaUrlJob mediaUrlJob = new RefreshMediaUrlJob(name);
                scheduleJob(mediaUrlJob, 3);
            });
    }

    public void refreshMediaStream() {
        httpClient.fetch(HttpMethod.GET, mediaUrl, null, null)
                .onSuccess(event -> {
                    if (log.isDebugEnabled()) {
                        log.debug("{} Success get MediaStream from {}", name, mediaUrl);
                    }
                    String mediaUrlPrefix = mediaUrl.substring(0, mediaUrl.lastIndexOf('/')+1);
                    MediaPlaylist playlist = null;
                    try {
                        playlist = parser.readPlaylist(event.body().toString());
                    } catch (PlaylistParserException e) {
                        log.error("{} Failed parse m3u8 {}", name, e.toString(), e);
                        // retry 3 seconds
                        RefreshMediaStreamJob mediaStreamJob = new RefreshMediaStreamJob(name);
                        scheduleJob(mediaStreamJob, 3);
                        return;
                    }
                    playlist.version().ifPresent(ver -> version=ver);
                    targetDuration = playlist.targetDuration();
                    playlist.mediaSegments().forEach(mediaSegment -> {
                        WrapMediaSegment wrap = new WrapMediaSegment(mediaSegment, mediaUrlPrefix);
                        if (!fifoQueue.contains(wrap)) {
                            fifoQueue.add(wrap);
                        }
                    });
                    mediaSequence = playlist.mediaSequence();

                    int seconds = Math.round(playlist.mediaSegments().size() * targetDuration * 0.5f);
                    RefreshMediaStreamJob mediaStreamJob = new RefreshMediaStreamJob(name);
                    scheduleJob(mediaStreamJob, seconds);
                })
                .onFailure(event -> {
                    log.warn("{} Failed get MediaStream from {} with {}", name, mediaUrl, event.toString());
                    // retry 3 seconds
                    RefreshMediaStreamJob mediaStreamJob = new RefreshMediaStreamJob(name);
                    scheduleJob(mediaStreamJob, 3);
                });
    }

    public String m3u8() {
        List<MediaSegment> segments = fifoQueue.stream().map(WrapMediaSegment::getMediaSegment).collect(Collectors.toList());
        MediaPlaylist mediaPlaylist = MediaPlaylist.builder()
                .version(version)
                .targetDuration(targetDuration)
                .mediaSequence(mediaSequence)
                .addAllMediaSegments(segments)
                .build();
        return parser.writePlaylistAsString(mediaPlaylist);
    }

    private <T extends AbstractRadioJob> void scheduleJob(T job, int seconds) {
        Trigger trigger = TriggerBuilder.newTrigger()
                .withIdentity(job.getTriggerKey())
                .forJob(job.getJobKey())
                .startAt(seconds==0 ? new Date() : DateBuilder.futureDate(seconds, DateBuilder.IntervalUnit.SECOND))
                .withSchedule(SimpleScheduleBuilder.simpleSchedule().withRepeatCount(1).withIntervalInSeconds(seconds==0 ? 3 : seconds))
//                .withSchedule(seconds != 0 ? SimpleScheduleBuilder.repeatSecondlyForever(seconds) : SimpleScheduleBuilder.simpleSchedule())
                .build();
        try {
            Date date = quartz.rescheduleJob(job.getTriggerKey(), trigger);
            if (date == null) {
                date = quartz.scheduleJob(trigger);
            }
            if (RefreshMediaUrlJob.REFRESH_MEDIA_URL.equals(job.getGroup()) || log.isDebugEnabled()) {
                log.info("scheduleJob {} success: interval {} seconds, next {}", job, seconds, date);
            }
        } catch (SchedulerException e) {
            log.error("scheduleJob {} failed: {}", job, e.toString(), e);
        }
    }

    private <T extends AbstractRadioJob> void addJob(T job) {
        JobDetail jobDetail = JobBuilder.newJob().ofType(job.getClass())
                .storeDurably()
                .withIdentity(job.getJobKey())
                .usingJobData(AbstractRadioJob.RADIO_NAME, job.getRadioName())
                .build();
        try {
            quartz.addJob(jobDetail, true);
            log.info("addJob {} success", job);
        } catch (SchedulerException e) {
            log.error("addJob {} failed: {}", job, e.toString(), e);
        }
    }

    private <T extends AbstractRadioJob> void removeJob(T job) {
        try {
            boolean success = quartz.deleteJob(job.getJobKey());
            log.info("deleteJob {} return {}", job, success);
        } catch (SchedulerException e) {
            //do nothing
        }
    }
}
