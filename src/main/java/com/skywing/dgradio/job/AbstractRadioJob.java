package com.skywing.dgradio.job;

import com.skywing.dgradio.RadioStationSet;
import lombok.Data;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.quartz.*;

import javax.inject.Inject;

@Data
@Slf4j
public abstract class AbstractRadioJob implements Job {

    public static final String RADIO_NAME = "radioName";

    @ToString.Exclude
    @Inject
    RadioStationSet stationSet;

    String radioName;

    public AbstractRadioJob() {
    }

    public AbstractRadioJob(String radioName) {
        this.radioName = radioName;
    }

    public abstract String getGroup();

    public JobKey getJobKey() {
        return new JobKey(radioName, getGroup());
    }

    public TriggerKey getTriggerKey() {
        return new TriggerKey(radioName, getGroup());
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        radioName = context.getMergedJobDataMap().getString(RADIO_NAME);
        if (log.isDebugEnabled()) {
            log.debug("stationSet {}, radioName {}", stationSet, radioName);
        }
        executeInternal(context);
    }

    public abstract void executeInternal(JobExecutionContext context) throws JobExecutionException;
}
