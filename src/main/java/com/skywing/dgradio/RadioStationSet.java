package com.skywing.dgradio;

import com.skywing.dgradio.model.RadioStation;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import org.quartz.Scheduler;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;

@ApplicationScoped
public class RadioStationSet {

    public static final String FM_104 = "东莞电台声动104";
    public static final String FM_1008 = "东莞电台阳光1008";
    public static final String FM_1075 = "东莞电台畅享1075";

    @Inject
    Scheduler quartz;

    @Inject
    Vertx vertx;

    private Map<String, RadioStation> stations = new LinkedHashMap<>();;

    void startup(@Observes StartupEvent event) {
    }

    @PostConstruct
    public void init() {
        stations.put(FM_104, new RadioStation(
                FM_104, "http://dgrtv.sun0769.com/index.php/online2/3", 5, quartz, vertx));
        stations.put(FM_1008, new RadioStation(
                FM_1008, "http://dgrtv.sun0769.com/index.php/online2/1", 5, quartz, vertx));
        stations.put(FM_1075, new RadioStation(
                FM_1075, "http://dgrtv.sun0769.com/index.php/online2/2", 5, quartz, vertx));

        stations.values().forEach(RadioStation::startMe);
    }

    @PreDestroy
    public void clean() {
        stations.values().forEach(RadioStation::stopMe);
        stations.clear();
    }

    public RadioStation getRadioStation(String name) {
        return stations.get(name);
    }
}
