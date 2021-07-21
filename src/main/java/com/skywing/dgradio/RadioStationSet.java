package com.skywing.dgradio;

import com.skywing.dgradio.model.RadioStation;
import io.quarkus.runtime.StartupEvent;
import org.apache.http.client.HttpClient;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.impl.client.HttpClientBuilder;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.skywing.dgradio.model.RadioStation.DEFAULT_TIMEOUT_MILLI;
import static com.skywing.dgradio.model.RadioStation.USER_AGENT;

@ApplicationScoped
public class RadioStationSet {

    public static final String FM_104 = "东莞电台声动104";
    public static final String FM_1008 = "东莞电台阳光1008";
    public static final String FM_1075 = "东莞电台畅享1075";

    private Map<String, RadioStation> stations = new LinkedHashMap<>();;

    void startup(@Observes StartupEvent event) {
    }

    @PostConstruct
    public void init() {
        RequestConfig requestConfig = RequestConfig.custom().setConnectTimeout(DEFAULT_TIMEOUT_MILLI)
                .setSocketTimeout(DEFAULT_TIMEOUT_MILLI).build();
        HttpClient httpClient = HttpClientBuilder.create().setDefaultRequestConfig(requestConfig)
                .setUserAgent(USER_AGENT).build();

        stations.put(FM_104, new RadioStation(
                FM_104, "https://dgrtv.sun0769.com/index.php/online2/3", 8, httpClient));
        stations.put(FM_1008, new RadioStation(
                FM_1008, "https://dgrtv.sun0769.com/index.php/online2/1", 8, httpClient));
        stations.put(FM_1075, new RadioStation(
                FM_1075, "https://dgrtv.sun0769.com/index.php/online2/2", 8, httpClient));

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
