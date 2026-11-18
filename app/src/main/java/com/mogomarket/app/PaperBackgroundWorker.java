package com.mogomarket.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs the simulator's pending orders / take-profit / stop-loss in the background (about every
 * 15 minutes, Android's minimum for periodic work), so they work with the app closed.
 * Fills are done by the same transactional engine as in the app, so a fill can never happen
 * twice even if the screen is open at the same time.
 */
public class PaperBackgroundWorker extends Worker {

    private static final String TAG = "PaperBackground";
    private static final String WORK_NAME = "paper_background_orders";
    static final String CHANNEL_ID = "paper_trading_fills";

    public PaperBackgroundWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /** Safe to call repeatedly. */
    static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PaperBackgroundWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    @NonNull
    @Override
    public Result doWork() {
        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) return Result.success();
        final DatabaseReference acct = FirebaseDatabase.getInstance().getReference("users")
                .child(user.getUid()).child("paper-trading");

        final DataSnapshot[] holder = new DataSnapshot[1];
        final CountDownLatch loaded = new CountDownLatch(1);
        acct.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                holder[0] = snapshot;
                loaded.countDown();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                loaded.countDown();
            }
        });
        try {
            if (!loaded.await(40, TimeUnit.SECONDS) || holder[0] == null) return Result.retry();
        } catch (InterruptedException e) {
            return Result.retry();
        }
        DataSnapshot snap = holder[0];

        // collect what could trigger
        Set<String> symbols = new HashSet<>();
        for (DataSnapshot o : snap.child("orders").getChildren()) {
            String s = o.child("symbol").getValue(String.class);
            if (s != null) symbols.add(s);
        }
        for (DataSnapshot p : snap.child("positions").getChildren()) {
            String s = p.child("symbol").getValue(String.class);
            Double tp = p.child("tp").getValue(Double.class);
            Double sl = p.child("sl").getValue(Double.class);
            if (s != null && ((tp != null && tp > 0) || (sl != null && sl > 0))) symbols.add(s);
        }
        if (symbols.isEmpty()) return Result.success();

        Map<String, PaperEngine.Quote> quotes = new HashMap<>();
        for (String symbol : symbols) {
            try {
                quotes.put(symbol, PaperEngine.fetchBlocking(symbol));
            } catch (Exception e) {
                Log.w(TAG, "No quote for " + symbol + ": " + e.getMessage());
            }
        }
        if (quotes.isEmpty()) return Result.retry();

        final Context context = getApplicationContext();
        final AtomicInteger pending = new AtomicInteger(0);
        final CountDownLatch done = new CountDownLatch(1);

        // orders
        for (DataSnapshot o : snap.child("orders").getChildren()) {
            String symbol = o.child("symbol").getValue(String.class);
            String side = o.child("side").getValue(String.class);
            String type = o.child("type").getValue(String.class);
            Double shares = o.child("shares").getValue(Double.class);
            Double trigger = o.child("price").getValue(Double.class);
            Double tp = o.child("tp").getValue(Double.class);
            Double sl = o.child("sl").getValue(Double.class);
            PaperEngine.Quote q = symbol == null ? null : quotes.get(symbol);
            if (q == null || side == null || type == null || shares == null || trigger == null
                    || o.getKey() == null) continue;
            boolean buy = "BUY".equals(side);
            boolean limit = "LIMIT".equals(type);
            boolean fire = buy ? (limit ? q.price <= trigger : q.price >= trigger)
                    : (limit ? q.price >= trigger : q.price <= trigger);
            if (!fire) continue;
            pending.incrementAndGet();
            PaperEngine.trade(acct, symbol, side, shares, q.price, type,
                    "Pending " + type + " order filled", tp == null ? 0 : tp, sl == null ? 0 : sl,
                    o.getKey(), null, (ok, msg) -> {
                        if (ok) notifyFill(context, "Order filled", msg);
                        if (pending.decrementAndGet() == 0) done.countDown();
                    });
        }
        // take profit / stop loss
        for (DataSnapshot p : snap.child("positions").getChildren()) {
            String symbol = p.child("symbol").getValue(String.class);
            Double shares = p.child("shares").getValue(Double.class);
            Double tp = p.child("tp").getValue(Double.class);
            Double sl = p.child("sl").getValue(Double.class);
            PaperEngine.Quote q = symbol == null ? null : quotes.get(symbol);
            if (q == null || shares == null || shares <= 0) continue;
            String exit = null;
            if (tp != null && tp > 0 && q.price >= tp) exit = "TP";
            else if (sl != null && sl > 0 && q.price <= sl) exit = "SL";
            if (exit == null) continue;
            final boolean tpHit = "TP".equals(exit);
            pending.incrementAndGet();
            PaperEngine.trade(acct, symbol, "SELL", shares, q.price,
                    tpHit ? "TAKE_PROFIT" : "STOP_LOSS",
                    tpHit ? "Take profit hit" : "Stop loss hit", 0, 0, null, exit,
                    (ok, msg) -> {
                        if (ok) notifyFill(context, tpHit ? "Take profit hit" : "Stop loss hit",
                                msg);
                        if (pending.decrementAndGet() == 0) done.countDown();
                    });
        }
        if (pending.get() == 0) return Result.success();
        try {
            done.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            // the transactions continue on their own
        }
        return Result.success();
    }

    private static void notifyFill(Context context, String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "Simulator order fills", NotificationManager.IMPORTANCE_DEFAULT);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
        Intent open = new Intent(context, SplashActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        PendingIntent pi = PendingIntent.getActivity(context, 4242, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Simulator: " + title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try {
            NotificationManagerCompat.from(context).notify(
                    (int) (System.currentTimeMillis() % 100000) + 5000, b.build());
        } catch (SecurityException e) {
            Log.w(TAG, "Notification permission is unavailable", e);
        }
    }
}
