package com.mogomarket.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * מתזמן בדיקות מחיר תקופתיות עם AlarmManager.
 * משתמש ב-inexact alarm כדי לא לדרוש הרשאת SCHEDULE_EXACT_ALARM שפותחת הגדרות.
 */
public class PriceAlertScheduler {

    public static final String PREFS_NAME = "app_prefs";
    public static final String KEY_ENABLED = "price_alerts_enabled";

    private static final long INTERVAL_MS = 15 * 60 * 1000L;
    static final String ACTION            = "com.example.chart.CHECK_PRICE_ALERTS";
    private static final String WORK_NAME = "price_alerts_periodic";
    private static final int REQUEST_CODE = 1001;

    /** Keeps the periodic WorkManager backup running (safe to call repeatedly). */
    private static void ensureBackupWork(Context context) {
        androidx.work.PeriodicWorkRequest request = new androidx.work.PeriodicWorkRequest.Builder(
                PriceAlertWorker.class, 15, java.util.concurrent.TimeUnit.MINUTES)
                .setConstraints(new androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED).build())
                .build();
        androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, androidx.work.ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void schedule(Context context) {
        ensureBackupWork(context);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;

        PendingIntent pendingIntent = buildPendingIntent(context);
        long triggerAtMillis = System.currentTimeMillis() + INTERVAL_MS;

        // תמיד משתמשים ב-inexact (ללא צורך בהרשאה מיוחדת)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    public static void cancel(Context context) {
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        alarmManager.cancel(buildPendingIntent(context));
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, PriceAlertReceiver.class);
        intent.setAction(ACTION);
        return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
