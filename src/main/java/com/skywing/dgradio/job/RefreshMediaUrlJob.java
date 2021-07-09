package com.skywing.dgradio.job;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@Slf4j
@ToString(callSuper = true)
public class RefreshMediaUrlJob extends AbstractRadioJob {

    public static final String REFRESH_MEDIA_URL = "RefreshMediaUrl";

    public RefreshMediaUrlJob() {
        super();
    }

    public RefreshMediaUrlJob(String radioName) {
        super(radioName);
    }

    @Override
    public String getGroup() {
        return REFRESH_MEDIA_URL;
    }

    @Override
    public void executeInternal(JobExecutionContext context) throws JobExecutionException {
        stationSet.getRadioStation(radioName).refreshMediaUrl();
    }
}
