package com.andre.hibyr4sound;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Daily SongateOS update check on Wi-Fi (scheduled by Updater.schedule while "check automatically" is on). */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        boolean started = Updater.checkAsync(this, false, () -> jobFinished(params, false));
        return started;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}
