package com.andre.hibyr4sound;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** SongateOS update check on Wi-Fi: daily (Updater.schedule) and once after every boot (Updater.scheduleBootCheck),
 *  while "check automatically" is on. */
public class UpdateJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        boolean started = Updater.checkAsync(this, false, () -> jobFinished(params, false));
        return started;
    }

    @Override
    public boolean onStopJob(JobParameters params) { return false; }
}
