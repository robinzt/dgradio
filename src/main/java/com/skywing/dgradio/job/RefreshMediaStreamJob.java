package com.skywing.dgradio.job;

import lombok.ToString;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;

@ToString(callSuper = true)
public class RefreshMediaStreamJob extends AbstractRadioJob {

    public static final String REFRESH_MEDIA_STREAM = "RefreshMediaStream";

    public RefreshMediaStreamJob() {
        super();
    }

    public RefreshMediaStreamJob(String radioName) {
        super(radioName);
    }

    @Override
    public String getGroup() {
        return REFRESH_MEDIA_STREAM;
    }

    @Override
    public void executeInternal(JobExecutionContext context) throws JobExecutionException {
        stationSet.getRadioStation(radioName).refreshMediaStream();
    }
}
