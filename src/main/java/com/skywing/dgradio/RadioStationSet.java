package com.skywing.dgradio;

import com.skywing.dgradio.model.RadioStation;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.skywing.dgradio.model.RadioStation.USER_AGENT;

@ApplicationScoped
public class RadioStationSet {

    public static final String FM_104 = "东莞电台声动104";
    public static final String FM_1008 = "东莞电台阳光1008";
    public static final String FM_1075 = "东莞电台畅享1075";

    @Inject
    Vertx vertx;

    private Map<String, RadioStation> stations = new LinkedHashMap<>();;

    void startup(@Observes StartupEvent event) {
    }

    @PostConstruct
    public void init() {
        WebClientOptions options = new WebClientOptions()
                .setUserAgent(USER_AGENT)
                .setConnectTimeout(2000);
        WebClient webClient = WebClient.create(vertx, options);

        stations.put(FM_104, new RadioStation(
                FM_104, "https://dgrtv.sun0769.com/index.php/online2/3", 8, webClient));
        stations.put(FM_1008, new RadioStation(
                FM_1008, "https://dgrtv.sun0769.com/index.php/online2/1", 8, webClient));
        stations.put(FM_1075, new RadioStation(
                FM_1075, "https://dgrtv.sun0769.com/index.php/online2/2", 8, webClient));

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
