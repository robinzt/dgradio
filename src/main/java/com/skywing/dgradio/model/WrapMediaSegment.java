package com.skywing.dgradio.model;

import io.lindstrom.m3u8.model.MediaSegment;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.Objects;

public class WrapMediaSegment {

    private MediaSegment mediaSegment;

    private String liteUri;

    public WrapMediaSegment(MediaSegment mediaSegment, String mediaUrlPrefix) {
        String uri = mediaSegment.uri();
        QueryStringDecoder decoder = new QueryStringDecoder(uri, true);
        liteUri = decoder.path();
//        System.out.println("\""+liteUri+"\"" );
        this.mediaSegment = MediaSegment.builder().from(mediaSegment).uri(mediaUrlPrefix + uri).build();
    }

    public MediaSegment getMediaSegment() {
        return mediaSegment;
    }

    public String getLiteUri() {
        return liteUri;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WrapMediaSegment that = (WrapMediaSegment) o;
        return Objects.equals(liteUri, that.liteUri);
    }

    @Override
    public int hashCode() {
        return Objects.hash(liteUri);
    }
}
