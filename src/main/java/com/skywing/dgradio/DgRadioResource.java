package com.skywing.dgradio;

import org.jboss.resteasy.annotations.cache.NoCache;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

@Path("/")
public class DgRadioResource {

    @Inject
    RadioStationSet radioStationSet;

    @GET
    @Path("/hello")
    @Produces(MediaType.TEXT_PLAIN)
    public String hello() {
        return "Hello RESTEasy";
    }

    @GET
    @Path("/dg/{name}.m3u8")
    @Produces("application/vnd.apple.mpegurl")
    @NoCache
    public String fm(@PathParam("name") String name) {
        switch (name) {
            case "fm104":
                return radioStationSet.getRadioStation(RadioStationSet.FM_104).m3u8();
            case "fm1008":
                return radioStationSet.getRadioStation(RadioStationSet.FM_1008).m3u8();
            case "fm1075":
                return radioStationSet.getRadioStation(RadioStationSet.FM_1075).m3u8();
        }
        return "";
    }
}