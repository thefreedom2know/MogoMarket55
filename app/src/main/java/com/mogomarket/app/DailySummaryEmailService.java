package com.mogomarket.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;


public class DailySummaryEmailService extends BroadcastReceiver {

    private static final String TAG = "DailySummaryEmail";
    private static final String CHANNEL_ID = "daily_summary_channel";
    private static final int NOTIFICATION_ID = 1001;
    private static final int REQUEST_CODE = 1002;
    private static final String ACTION_DAILY = "com.mogomarket.app.DAILY_EMAIL_SUMMARY";
    private static final String ACTION_WEEKLY = "com.mogomarket.app.WEEKLY_EMAIL_SUMMARY";
    private static final int WEEKLY_REQUEST_CODE = 1003;

    public static final String PREFS_NAME = "app_prefs";
    public static final String KEY_ENABLED = "daily_email_enabled";
    public static final String KEY_HOUR = "daily_email_hour";
    public static final String KEY_MINUTE = "daily_email_minute";
    public static final String KEY_RECIPIENT = "daily_email_recipient";
    public static final String KEY_ITEM_PREFIX = "daily_email_item_";
    public static final String KEY_LANGUAGE = "daily_email_language"; // "en" or "he"

    // Weekly summary: its own on/off, day and time. Content, language and recipient are
    // shared with the daily summary.
    public static final String KEY_WEEKLY_ENABLED = "weekly_email_enabled";
    public static final String KEY_WEEKLY_DAY = "weekly_email_day"; // Calendar.SUNDAY..SATURDAY
    public static final String KEY_WEEKLY_HOUR = "weekly_email_hour";
    public static final String KEY_WEEKLY_MINUTE = "weekly_email_minute";
    public static final int DEFAULT_WEEKLY_DAY = Calendar.SATURDAY;
    public static final int DEFAULT_WEEKLY_HOUR = 9;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action)) {
            if (isEnabled(context)) scheduleDailySummary(context);
            if (isWeeklyEnabled(context)) scheduleWeeklySummary(context);
            return;
        }

        if (ACTION_WEEKLY.equals(action)) {
            if (!isWeeklyEnabled(context)) return;
            scheduleWeeklySummary(context);
            DailySummaryEmailWorker.enqueueWeekly(context);
            return;
        }

        if (!ACTION_DAILY.equals(action)) return;

        if (!isEnabled(context)) {
            return;
        }

        scheduleDailySummary(context);
        if (isSummaryDay(Calendar.getInstance().get(Calendar.DAY_OF_WEEK))) {
            DailySummaryEmailWorker.enqueue(context, false);
        }
    }

    public static void sendNow(Context context, boolean showSuccessNotification) {
        DailySummaryEmailWorker.enqueueManual(context, showSuccessNotification);
    }

    public static void sendWeeklyNow(Context context, boolean showSuccessNotification) {
        DailySummaryEmailWorker.enqueueWeeklyManual(context, showSuccessNotification);
    }

    /** Weekly report window: the 7 days that end at midnight today. */
    static long[] weeklyReportWindow() {
        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.DAY_OF_YEAR, -7);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    /** Report window: since the previous summary day (Monday also covers the weekend). */
    static long[] defaultReportWindow() {
        Calendar end = Calendar.getInstance();
        end.set(Calendar.HOUR_OF_DAY, 0);
        end.set(Calendar.MINUTE, 0);
        end.set(Calendar.SECOND, 0);
        end.set(Calendar.MILLISECOND, 0);
        Calendar start = (Calendar) end.clone();
        start.add(Calendar.DAY_OF_YEAR,
                end.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY ? -3 : -1);
        return new long[]{start.getTimeInMillis(), end.getTimeInMillis()};
    }

    public static boolean isItemEnabled(SharedPreferences prefs, String itemId) {
        return prefs.getBoolean(KEY_ITEM_PREFIX + itemId, true);
    }

    static boolean sendSummaryNow(Context context, long startMillis, long endMillis,
                                  boolean showSuccessNotification) {
        return sendSummaryNow(context, startMillis, endMillis, showSuccessNotification, false);
    }

    static boolean sendSummaryNow(Context context, long startMillis, long endMillis,
                                  boolean showSuccessNotification, boolean weekly) {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null || user.isAnonymous()) {
            return fail(context, "You are not signed in with an account (guest mode can't send email).");
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String recipients = prefs.getString(KEY_RECIPIENT, "");
        String toEmail = recipients != null && !recipients.trim().isEmpty()
                ? recipients.trim() : user.getEmail();
        if (toEmail == null || toEmail.trim().isEmpty()) {
            return fail(context, "No recipient email. Set one under 'Send to'.");
        }

        try {
            DailySummaryReport.Input input = new DailySummaryReport.Input();
            for (String id : DailySummaryReport.ITEM_IDS) {
                if (isItemEnabled(prefs, id)) input.enabledItems.add(id);
            }
            boolean hebrew = "he".equals(prefs.getString(KEY_LANGUAGE, "en"));
            input.hebrew = hebrew;
            input.weekly = weekly;
            input.periodStart = startMillis;
            Locale mailLocale = hebrew ? new Locale("he", "IL") : Locale.US;
            if (weekly) {
                SimpleDateFormat day = new SimpleDateFormat("d MMM", mailLocale);
                // The window ends at midnight, so its last day is the day before endMillis.
                input.dateTitle = day.format(new Date(startMillis)) + " - "
                        + new SimpleDateFormat("d MMM yyyy", mailLocale)
                        .format(new Date(endMillis - 1));
            } else {
                input.dateTitle = new SimpleDateFormat("EEEE, d MMMM yyyy", mailLocale)
                        .format(new Date());
            }

            com.google.firebase.database.DatabaseReference userRef =
                    FirebaseDatabase.getInstance().getReference("users").child(user.getUid());

            if (input.enabledItems.contains(DailySummaryReport.ITEM_TRADES)) {
                DataSnapshot eventsSnapshot = Tasks.await(
                        userRef.child("daily-trade-events").get(), 30, TimeUnit.SECONDS);
                for (DataSnapshot child : eventsSnapshot.getChildren()) {
                    DailyTradeEvent event = child.getValue(DailyTradeEvent.class);
                    if (event != null && event.closedAt >= startMillis
                            && event.closedAt < endMillis) {
                        input.trades.add(event);
                    }
                }
            }

            if (input.enabledItems.contains(DailySummaryReport.ITEM_PORTFOLIO)) {
                DataSnapshot openSnapshot = Tasks.await(
                        userRef.child("portfolio-stocks").get(), 30, TimeUnit.SECONDS);
                DataSnapshot closedSnapshot = Tasks.await(
                        userRef.child("closed-trades").get(), 30, TimeUnit.SECONDS);
                List<StockData> openTrades = readTrades(openSnapshot);
                List<StockData> closedTrades = readTrades(closedSnapshot);
                input.openPositions = openTrades.size();
                input.portfolio = PortfolioDailyPerformance.calculate(
                        openTrades, closedTrades, startMillis, endMillis, weekly);
            }

            if (input.enabledItems.contains(DailySummaryReport.ITEM_WATCHLIST)) {
                DataSnapshot watchSnapshot = Tasks.await(
                        userRef.child("watchlist-stocks").get(), 30, TimeUnit.SECONDS);
                for (DataSnapshot child : watchSnapshot.getChildren()) {
                    String symbol = child.child("symbol").getValue(String.class);
                    if (symbol != null && !symbol.trim().isEmpty() && !symbol.contains(":")) {
                        input.watchlistSymbols.add(symbol.trim());
                    }
                }
            }

            DailySummaryReport.Rendered rendered = DailySummaryReport.build(input);
            String subject = weekly
                    ? (hebrew ? "MogoMarket סיכום שבועי - " : "MogoMarket Weekly Summary - ")
                            + input.dateTitle
                    : (hebrew ? "MogoMarket סיכום יומי - " : "MogoMarket Daily Summary - ")
                            + new SimpleDateFormat("EEE d MMM", mailLocale).format(new Date());
            return sendEmail(context, toEmail, subject, rendered.text, rendered.html,
                    showSuccessNotification, weekly);
        } catch (Exception e) {
            return fail(context, "Could not build the summary: " + describe(e));
        }
    }

    public static final String KEY_LAST_ERROR = "daily_email_last_error";

    private static boolean fail(Context context, String message) {
        Log.e(TAG, message);
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_LAST_ERROR, message).apply();
        return false;
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static List<StockData> readTrades(DataSnapshot snapshot) {
        List<StockData> trades = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            StockData trade = child.getValue(StockData.class);
            if (trade != null) trades.add(trade);
        }
        return trades;
    }

    private static boolean sendEmail(Context context, String toEmail, String subject,
                                     String plainText, String html,
                                     boolean showSuccessNotification, boolean weekly) {
        return sendViaServer(context, toEmail, subject, plainText, html, showSuccessNotification,
                weekly);
    }

    /**
     * The app holds no SMTP login: the "sendSummaryEmail" Firebase Function sends the mail
     * with credentials stored on the server (see functions/README.md).
     */
    private static boolean sendViaServer(Context context, String toEmail, String subject,
                                         String plainText, String html,
                                         boolean showSuccessNotification, boolean weekly) {
        try {
            java.util.Map<String, Object> data = new java.util.HashMap<>();
            data.put("to", toEmail);
            data.put("subject", subject);
            data.put("text", plainText);
            data.put("html", html);
            Tasks.await(com.google.firebase.functions.FirebaseFunctions.getInstance()
                    .getHttpsCallable("sendSummaryEmail").call(data), 60, TimeUnit.SECONDS);
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .remove(KEY_LAST_ERROR).apply();
            if (showSuccessNotification) {
                showNotification(context, weekly ? "Weekly summary sent" : "Daily summary sent",
                        "The email was sent to " + toEmail);
            }
            return true;
        } catch (Exception e) {
            String reason = e.getMessage();
            Throwable cause = e.getCause();
            if (cause instanceof com.google.firebase.functions.FirebaseFunctionsException) {
                com.google.firebase.functions.FirebaseFunctionsException fe =
                        (com.google.firebase.functions.FirebaseFunctionsException) cause;
                reason = fe.getCode() == com.google.firebase.functions.FirebaseFunctionsException
                        .Code.NOT_FOUND
                        ? "The email server function is not deployed yet (see functions/README.md)."
                        : fe.getMessage();
            }
            return fail(context, "Server email failed: " + reason);
        }
    }

    private static String formatMoney(double value) {
        return String.format(Locale.US, "$%,.2f", value);
    }

    private static String formatSignedMoney(double value) {
        return String.format(Locale.US, "%s$%,.2f", value >= 0 ? "+" : "-", Math.abs(value));
    }

    private static String safe(String value) {
        return value == null || value.trim().isEmpty() ? "N/A" : value;
    }

    private static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    public static void scheduleDailySummary(Context context) {
        if (!isEnabled(context)) {
            cancelDailySummary(context);
            return;
        }

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "Cannot schedule daily summary: AlarmManager is unavailable.");
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
        Calendar now = Calendar.getInstance();
        Calendar nextRun = (Calendar) now.clone();
        nextRun.set(Calendar.HOUR_OF_DAY, preferences.getInt(KEY_HOUR, 8));
        nextRun.set(Calendar.MINUTE, preferences.getInt(KEY_MINUTE, 0));
        nextRun.set(Calendar.SECOND, 0);
        nextRun.set(Calendar.MILLISECOND, 0);
        if (nextRun.getTimeInMillis() <= now.getTimeInMillis()) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }
        while (!isSummaryDay(nextRun.get(Calendar.DAY_OF_WEEK))) {
            nextRun.add(Calendar.DAY_OF_YEAR, 1);
        }

        PendingIntent pendingIntent = buildPendingIntent(context);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, nextRun.getTimeInMillis(), pendingIntent);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, nextRun.getTimeInMillis(), pendingIntent);
            } else {
                alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP, nextRun.getTimeInMillis(), pendingIntent);
            }

            Log.i(TAG, "Daily summary scheduled for " + nextRun.getTime());
        } catch (SecurityException e) {
            Log.e(TAG, "Exact alarm permission was revoked; scheduling an inexact alarm.", e);
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, nextRun.getTimeInMillis(), pendingIntent);
        }
    }

    static boolean isSummaryDay(int dayOfWeek) {
        return dayOfWeek != Calendar.SUNDAY; // Monday through Saturday
    }

    static boolean isWeeklyEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_WEEKLY_ENABLED, false);
    }

    /** Next time the weekly summary is due, strictly after {@code now}. */
    static Calendar nextWeeklyRun(Calendar now, int dayOfWeek, int hour, int minute) {
        Calendar next = (Calendar) now.clone();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        while (next.get(Calendar.DAY_OF_WEEK) != dayOfWeek
                || next.getTimeInMillis() <= now.getTimeInMillis()) {
            next.add(Calendar.DAY_OF_YEAR, 1);
        }
        return next;
    }

    public static void scheduleWeeklySummary(Context context) {
        if (!isWeeklyEnabled(context)) {
            cancelWeeklySummary(context);
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.e(TAG, "Cannot schedule weekly summary: AlarmManager is unavailable.");
            return;
        }
        SharedPreferences preferences = context.getSharedPreferences(
                PREFS_NAME, Context.MODE_PRIVATE);
        Calendar nextRun = nextWeeklyRun(Calendar.getInstance(),
                preferences.getInt(KEY_WEEKLY_DAY, DEFAULT_WEEKLY_DAY),
                preferences.getInt(KEY_WEEKLY_HOUR, DEFAULT_WEEKLY_HOUR),
                preferences.getInt(KEY_WEEKLY_MINUTE, 0));
        PendingIntent pendingIntent = buildWeeklyPendingIntent(context);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, nextRun.getTimeInMillis(), pendingIntent);
            } else {
                alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, nextRun.getTimeInMillis(), pendingIntent);
            }
            Log.i(TAG, "Weekly summary scheduled for " + nextRun.getTime());
        } catch (SecurityException e) {
            Log.e(TAG, "Exact alarm permission was revoked; scheduling an inexact alarm.", e);
            alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, nextRun.getTimeInMillis(), pendingIntent);
        }
    }

    public static void cancelWeeklySummary(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(buildWeeklyPendingIntent(context));
    }

    private static PendingIntent buildWeeklyPendingIntent(Context context) {
        Intent intent = new Intent(context, DailySummaryEmailService.class).setAction(ACTION_WEEKLY);
        return PendingIntent.getBroadcast(context, WEEKLY_REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancelDailySummary(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(buildPendingIntent(context));
    }

    private static PendingIntent buildPendingIntent(Context context) {
        Intent intent = new Intent(context, DailySummaryEmailService.class).setAction(ACTION_DAILY);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void showNotification(Context context, String title, String text) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Daily Summary", NotificationManager.IMPORTANCE_DEFAULT));
        }

        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true);
        try {
            manager.notify(NOTIFICATION_ID, notification.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission is unavailable.", e);
        }
    }

    static void showFailureNotification(Context context) {
        showFailureNotification(context, false);
    }

    static void showFailureNotification(Context context, boolean weekly) {
        String reason = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_ERROR, "Check your sign-in, network, and email settings.");
        showNotification(context, weekly ? "Weekly summary failed" : "Daily summary failed",
                reason);
    }
}
