package com.mogomarket.app;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/** Builds and e-mails the daily summary in the background (with retries on failure). */
public class DailySummaryEmailWorker extends Worker {

    private static final String KEY_MANUAL = "manual";
    private static final String KEY_NOTIFY = "notify";
    private static final int MAX_ATTEMPTS = 3;

    public DailySummaryEmailWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context, boolean force) {
        schedule(context, false, false, "daily_summary_scheduled");
    }

    public static void enqueueManual(Context context, boolean showSuccessNotification) {
        schedule(context, true, showSuccessNotification, "daily_summary_manual");
    }

    private static void schedule(Context context, boolean manual, boolean notify, String name) {
        Data input = new Data.Builder()
                .putBoolean(KEY_MANUAL, manual)
                .putBoolean(KEY_NOTIFY, notify)
                .build();
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(DailySummaryEmailWorker.class)
                .setInputData(input)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 2, TimeUnit.MINUTES)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        boolean manual = getInputData().getBoolean(KEY_MANUAL, false);
        boolean notify = getInputData().getBoolean(KEY_NOTIFY, false);

        long[] window = DailySummaryEmailService.defaultReportWindow();
        boolean sent = DailySummaryEmailService.sendSummaryNow(context, window[0], window[1], notify);
        if (sent) return Result.success();

        if (manual || getRunAttemptCount() + 1 >= MAX_ATTEMPTS) {
            DailySummaryEmailService.showFailureNotification(context);
            return Result.failure();
        }
        return Result.retry();
    }
}
