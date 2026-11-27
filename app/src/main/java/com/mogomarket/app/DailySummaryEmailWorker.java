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
    private static final String KEY_WEEKLY = "weekly";
    private static final int MAX_ATTEMPTS = 3;

    public DailySummaryEmailWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    public static void enqueue(Context context, boolean force) {
        schedule(context, false, false, false, "daily_summary_scheduled");
    }

    public static void enqueueManual(Context context, boolean showSuccessNotification) {
        schedule(context, true, showSuccessNotification, false, "daily_summary_manual");
    }

    public static void enqueueWeekly(Context context) {
        schedule(context, false, false, true, "weekly_summary_scheduled");
    }

    public static void enqueueWeeklyManual(Context context, boolean showSuccessNotification) {
        schedule(context, true, showSuccessNotification, true, "weekly_summary_manual");
    }

    private static void schedule(Context context, boolean manual, boolean notify, boolean weekly,
                                 String name) {
        Data input = new Data.Builder()
                .putBoolean(KEY_MANUAL, manual)
                .putBoolean(KEY_NOTIFY, notify)
                .putBoolean(KEY_WEEKLY, weekly)
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
        boolean weekly = getInputData().getBoolean(KEY_WEEKLY, false);

        long[] window = weekly ? DailySummaryEmailService.weeklyReportWindow()
                : DailySummaryEmailService.defaultReportWindow();
        boolean sent = DailySummaryEmailService.sendSummaryNow(
                context, window[0], window[1], notify, weekly);
        if (sent) return Result.success();

        if (manual || getRunAttemptCount() + 1 >= MAX_ATTEMPTS) {
            DailySummaryEmailService.showFailureNotification(context, weekly);
            return Result.failure();
        }
        return Result.retry();
    }
}
