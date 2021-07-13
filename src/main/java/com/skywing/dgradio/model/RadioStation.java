package com.skywing.dgradio.model;

import com.google.common.collect.EvictingQueue;
import io.lindstrom.m3u8.model.MediaPlaylist;
import io.lindstrom.m3u8.model.MediaSegment;
import io.lindstrom.m3u8.parser.MediaPlaylistParser;
import io.lindstrom.m3u8.parser.ParsingMode;
import io.lindstrom.m3u8.parser.PlaylistParserException;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import lombok.Data;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.net.URL;
import java.util.Collection;
import java.util.Collections;
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

    private static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.114 Safari/537.36";
    public static final int DEFAULT_TIMEOUT_MILLI = 3000;
    @NonNull
    private String name;

    @NonNull
    private String webUrl;

    @NonNull
    private int queueSize;

    @NonNull
    private Vertx vertx;

    private ScheduledThreadPoolExecutor scheduledExecutor;

    private RefreshMediaUrlRunner refreshMediaUrlRunner;

    private ScheduledFuture<?> refreshMediaUrlRunnerFuture;

    private RefreshMediaStreamRunner refreshMediaStreamRunner;

    private ScheduledFuture<?> refreshMediaStreamRunnerFuture;

    private String mediaUrl;

    private int version = 3;

    private long mediaSequence;

    private int targetDuration;

    private Collection<WrapMediaSegment> fifoQueue;

    private WebClient webClient;

    private MediaPlaylistParser parser = new MediaPlaylistParser(ParsingMode.LENIENT);

    public synchronized void startMe() {
        WebClientOptions options = new WebClientOptions()
                .setUserAgent(USER_AGENT)
                .setConnectTimeout(2000);
        webClient = WebClient.create(vertx, options);
        fifoQueue = Collections.synchronizedCollection(EvictingQueue.create(queueSize));

        scheduledExecutor = new ScheduledThreadPoolExecutor(2);
        scheduledExecutor.setRemoveOnCancelPolicy(true);
        refreshMediaUrlRunner = new RefreshMediaUrlRunner();
        refreshMediaStreamRunner = new RefreshMediaStreamRunner();

        scheduledExecutor.submit(refreshMediaUrlRunner);
    }

    public synchronized void stopMe() {
        scheduledExecutor.shutdown();
        try {
            scheduledExecutor.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            //do nothing
        }
    }

    public void refreshMediaUrl() {
        webClient.getAbs(webUrl).timeout(DEFAULT_TIMEOUT_MILLI).send()
                .onSuccess(event -> {
                    String html = event.body().toString();
                    Matcher matcher = CURR_STREAM_PATTERN.matcher(html);
                    if (matcher.find()) {
                        log.info("{} get MediaUrl success from {}", name, webUrl);
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
                        log.info("{} set MediaUrl={}", name, mediaUrl);

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

                        int seconds = (int) (t + ttl - (System.currentTimeMillis() / 1000) - 120);
                        if (seconds < 0) seconds = 0;
                        log.info("{} ttl={} t={} seconds={}", name, ttl, t, seconds);
                        clearAllJobs();
                        refreshMediaUrlRunnerFuture = scheduledExecutor.schedule(refreshMediaUrlRunner, seconds, TimeUnit.SECONDS);
                        refreshMediaStreamRunnerFuture = scheduledExecutor.schedule(refreshMediaStreamRunner, 0, TimeUnit.NANOSECONDS);
                    } else {
                        log.warn("{} get MediaUrl failed from {} with NOT MATCH", name, webUrl);
                        clearAllJobs();
                        // retry 3 seconds
                        refreshMediaUrlRunnerFuture = scheduledExecutor.schedule(refreshMediaUrlRunner, 3, TimeUnit.SECONDS);
                    }
                })
            .onFailure(event -> {
                log.warn("{} get MediaUrl failed from {} with {}", name, webUrl, event.toString());
                clearAllJobs();
                // retry 3 seconds
                refreshMediaUrlRunnerFuture = scheduledExecutor.schedule(refreshMediaUrlRunner, 3, TimeUnit.SECONDS);
            });
    }

    private void clearAllJobs() {
        if (refreshMediaUrlRunnerFuture != null) {
            refreshMediaUrlRunnerFuture.cancel(false);
        }
        if (refreshMediaStreamRunnerFuture != null) {
            refreshMediaStreamRunnerFuture.cancel(false);
        }
    }

    public void refreshMediaStream() {
        webClient.getAbs(mediaUrl).timeout(DEFAULT_TIMEOUT_MILLI).send()
                .onSuccess(event -> {
                    if (log.isDebugEnabled()) {
                        log.debug("{} get MediaStream success from {}", name, mediaUrl);
                    }
                    String mediaUrlPrefix = mediaUrl.substring(0, mediaUrl.lastIndexOf('/')+1);
                    MediaPlaylist playlist = null;
                    try {
                        playlist = parser.readPlaylist(event.body().toString());
                    } catch (PlaylistParserException e) {
                        log.error("{} Failed parse m3u8 {}", name, e.toString(), e);
                        // retry 1 seconds
                        refreshMediaStreamRunnerFuture = scheduledExecutor.schedule(refreshMediaStreamRunner, 1, TimeUnit.SECONDS);
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
                    refreshMediaStreamRunnerFuture = scheduledExecutor.schedule(refreshMediaStreamRunner, seconds, TimeUnit.SECONDS);
                })
                .onFailure(event -> {
                    log.warn("{} get MediaStream failed from {} with {}", name, mediaUrl, event.toString());
                    clearAllJobs();
                    // schedule to refresh media url runner
                    refreshMediaUrlRunnerFuture = scheduledExecutor.schedule(refreshMediaUrlRunner, 0, TimeUnit.NANOSECONDS);
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
