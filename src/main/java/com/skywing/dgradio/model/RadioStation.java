package com.skywing.dgradio.model;

import com.google.common.collect.EvictingQueue;
import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.netty.handler.codec.http.QueryStringDecoder;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.BasicResponseHandler;

import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Data
@Slf4j
public class RadioStation {

    private static final Pattern CURR_STREAM_PATTERN = Pattern.compile("curr_stream\\s*=\\s*\"(.*)\";");

    public static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36";
    public static final int DEFAULT_TIMEOUT_MILLI = 3000;
    public static final String REFRESH_MEDIA_URL = "RefreshMediaUrl";
    public static final String REFRESH_MEDIA_STREAM = "RefreshMediaStream";
    @NonNull
    private String name;

    @NonNull
    private String webUrl;

    @NonNull
    private int queueSize;

    @NonNull
    private HttpClient httpClient;

    private ScheduledThreadPoolExecutor scheduledExecutor;

    private RefreshMediaUrlRunner refreshMediaUrlRunner;

    private volatile ScheduledFuture<?> refreshMediaUrlRunnerFuture;

    private RefreshMediaStreamRunner refreshMediaStreamRunner;

    private volatile ScheduledFuture<?> refreshMediaStreamRunnerFuture;

    private String mediaUrl;

    private int version = 3;

    private long mediaSequence;

    private int targetDuration;

    private Collection<WrapMediaSegment> fifoQueue;

    private MediaPlaylistParser parser = new MediaPlaylistParser(ParsingMode.LENIENT);

    private BasicResponseHandler responseHandler = new BasicResponseHandler();

    public synchronized void startMe() {
        fifoQueue = Collections.synchronizedCollection(EvictingQueue.create(queueSize));

        scheduledExecutor = new ScheduledThreadPoolExecutor(2);
        scheduledExecutor.setRemoveOnCancelPolicy(true);
        refreshMediaUrlRunner = new RefreshMediaUrlRunner();
        refreshMediaStreamRunner = new RefreshMediaStreamRunner();

        scheduledExecutor.submit(refreshMediaUrlRunner);
    }

    public synchronized void stopMe() {
        log.info("{} stopMe", name);
        scheduledExecutor.shutdown();
        try {
            scheduledExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            //do nothing
        }
    }

    public void refreshMediaUrl() {
        final ScheduledFuture<?> prevRunner = refreshMediaUrlRunnerFuture;

        HttpUriRequest request = RequestBuilder.get(webUrl).build();
        Matcher matcher = null;
        String failedReason = null;
        try {
            String html = httpClient.execute(request, responseHandler);
            matcher = CURR_STREAM_PATTERN.matcher(html);
            if (!matcher.find()) {
                failedReason = "NOT MATCH";
            }
        } catch (Exception e) {
            failedReason = e.toString();
        }

        if (failedReason != null) {
            if (prevRunner == refreshMediaUrlRunnerFuture) {
                log.warn("{} get MediaUrl failed from {} with {}", name, webUrl, failedReason);
                stopRunner(refreshMediaStreamRunnerFuture, REFRESH_MEDIA_STREAM);
                refreshMediaStreamRunnerFuture = null;
                // retry after 1 seconds
                refreshMediaUrlRunnerFuture = scheduledExecutor.schedule(refreshMediaUrlRunner, 1, TimeUnit.SECONDS);
                log.info("{} schedule refreshMediaUrlRunner {}", name, refreshMediaUrlRunnerFuture);
            } else {
                log.info("{} skip schedule refreshMediaUrlRunner because future changed", name);
            }
            return;
        }

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
        log.info("{} get MediaUrl={} success from {}", name, mediaUrl, webUrl);

        int ttl = 1200;
        long t = System.currentTimeMillis() / 1000;
        try {
            URL url = new URL(mediaUrl);
            QueryStringDecoder decoder = new QueryStringDecoder(url.getQuery(), false);
            ttl = Integer.parseInt(decoder.parameters().get("ttl").get(0));
            t = Long.parseLong(decoder.parameters().get("t").get(0));
        } catch (Exception e) {
            //do nothing
            log.warn("{} compute delay failed: {}", name, e.toString(), e);
        }

        int seconds = (int) (t + ttl - (System.currentTimeMillis() / 1000) - 120);
        if (seconds < 0) seconds = 0;
        if (prevRunner == refreshMediaUrlRunnerFuture) {
            log.info("{} ttl={}, t={}, seconds={}, next run={}", name, ttl, t, seconds, new Date(System.currentTimeMillis() + seconds*1000));
            stopRunner(refreshMediaStreamRunnerFuture, REFRESH_MEDIA_STREAM);
            refreshMediaStreamRunnerFuture = null;
            refreshMediaUrlRunnerFuture = scheduledExecutor.schedule(refreshMediaUrlRunner, seconds, TimeUnit.SECONDS);
            refreshMediaStreamRunnerFuture = scheduledExecutor.schedule(refreshMediaStreamRunner, 1000, TimeUnit.MILLISECONDS);
            log.info("{} schedule refreshMediaUrlRunner {}", name, refreshMediaUrlRunnerFuture);
        } else {
            log.info("{} skip schedule refreshMediaUrlRunner because future changed", name);
        }
    }

    private void stopRunner(ScheduledFuture<?> future, String jobName) {
        if (future != null) {
            future.cancel(false);
            log.info("{} stopRunner {} {}", name, jobName, future);
        }
    }

    public void refreshMediaStream() {
        final ScheduledFuture<?> prevRunner = refreshMediaStreamRunnerFuture;
        final ScheduledFuture<?> otherRunner = refreshMediaUrlRunnerFuture;

        HttpUriRequest request = RequestBuilder.get(mediaUrl).build();
        String mediaUrlPrefix = mediaUrl.substring(0, mediaUrl.lastIndexOf('/')+1);
        MediaPlaylist playlist = null;
        String failedReason = null;
        try {
            String content = httpClient.execute(request, responseHandler);
            playlist = parser.readPlaylist(content);
        } catch (Exception e) {
            failedReason = e.toString();
        }

        if (failedReason != null) {
            log.warn("{} get MediaStream failed from {} with {}", name, mediaUrl, failedReason);
            if (refreshMediaUrlRunnerFuture == otherRunner) {
                stopRunner(otherRunner, REFRESH_MEDIA_URL);
                refreshMediaStreamRunnerFuture = null;
                // schedule to refresh media url runner
                refreshMediaUrlRunnerFuture = scheduledExecutor.schedule(refreshMediaUrlRunner, 1000, TimeUnit.MILLISECONDS);
                log.info("{} explicit run refreshMediaUrlRunner {}", name, refreshMediaUrlRunnerFuture);
            } else {
                refreshMediaStreamRunnerFuture = null;
                log.info("{} skip schedule refreshMediaUrlRunner because future changed", name);
            }
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
        if (seconds >= 5 || seconds <= 0) {
            log.warn("{} error refreshMediaStreamRunner seconds={}", name, seconds);
            seconds = 3;
        }
        if (prevRunner == null || refreshMediaStreamRunnerFuture == prevRunner) {
            refreshMediaStreamRunnerFuture = scheduledExecutor.schedule(refreshMediaStreamRunner, seconds, TimeUnit.SECONDS);
        } else {
            log.info("{} skip schedule refreshMediaStreamRunner because future changed", name);
        }
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


    class RefreshMediaUrlRunner implements Runnable {
        @Override
        public void run() {
            refreshMediaUrl();
        }
    }

    class RefreshMediaStreamRunner implements Runnable {
        @Override
        public void run() {
            refreshMediaStream();
        }
    }
}
