package com.alberto.tvfeeder;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Daily periodic sync, the boot sync and the immediate run the activity schedules. */
public final class SyncJobService extends JobService {

    private static final int JOB_PERIODIC = 1;
    private static final int JOB_NOW = 2;
    private static final long PERIOD_MS = 24 * 60 * 60 * 1000L;

    static void schedulePeriodic(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(new JobInfo.Builder(JOB_PERIODIC, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .setPeriodic(PERIOD_MS)
                .build());
    }

    static void syncNow(Context context) {
        JobScheduler scheduler = scheduler(context);
        if (scheduler == null) {
            return;
        }
        scheduler.schedule(new JobInfo.Builder(JOB_NOW, component(context))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setOverrideDeadline(0)
                .build());
    }

    private static JobScheduler scheduler(Context context) {
        return (JobScheduler) context.getSystemService(Context.JOB_SCHEDULER_SERVICE);
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, SyncJobService.class);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Feeder.storeReport(SyncJobService.this, Feeder.sync(SyncJobService.this));
                } finally {
                    jobFinished(params, false);
                }
            }
        }, "tvfeeder-sync").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
