package com.mogomarket.app;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/**
 * Backup for the AlarmManager check: WorkManager survives reboots, app updates and aggressive
 * battery managers, so price alerts keep firing even if the app was never reopened.
 */
public class PriceAlertWorker extends Worker {

    public PriceAlertWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        boolean enabled = context.getSharedPreferences(
                        PriceAlertScheduler.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PriceAlertScheduler.KEY_ENABLED, false);
        if (!enabled) return Result.success();
        Intent intent = new Intent(context, PriceAlertReceiver.class);
        intent.setAction(PriceAlertScheduler.ACTION);
        context.sendBroadcast(intent);
        return Result.success();
    }
}
