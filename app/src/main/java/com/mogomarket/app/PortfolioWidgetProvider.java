package com.mogomarket.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.util.Log;
import android.widget.RemoteViews;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Home-screen widget: portfolio value, today's P&L and the first few watchlist tickers.
 * Refreshes about every 30 minutes (Android's limit) and when the refresh arrow is tapped.
 */
public class PortfolioWidgetProvider extends AppWidgetProvider {

    private static final String TAG = "PortfolioWidget";
    static final String ACTION_REFRESH = "com.mogomarket.app.WIDGET_REFRESH";
    private static final int GREEN = Color.parseColor("#00C896");
    private static final int RED = Color.parseColor("#FF4D4D");
    private static final int GREY = Color.parseColor("#9AA7B8");

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        refresh(context, goAsync());
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_REFRESH.equals(intent.getAction())) {
            refresh(context, goAsync());
            return;
        }
        super.onReceive(context, intent);
    }

    private static void refresh(Context context, PendingResult pending) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            try {
                RemoteViews views = build(app);
                AppWidgetManager manager = AppWidgetManager.getInstance(app);
                int[] ids = manager.getAppWidgetIds(
                        new ComponentName(app, PortfolioWidgetProvider.class));
                for (int id : ids) manager.updateAppWidget(id, views);
            } catch (Exception e) {
                Log.e(TAG, "Widget refresh failed", e);
            } finally {
                if (pending != null) pending.finish();
            }
        }).start();
    }

    private static RemoteViews build(Context context) {
        RemoteViews v = new RemoteViews(context.getPackageName(), R.layout.widget_portfolio);

        Intent open = new Intent(context, SplashActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        v.setOnClickPendingIntent(R.id.widgetRoot, PendingIntent.getActivity(context, 7001, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        Intent refresh = new Intent(context, PortfolioWidgetProvider.class);
        refresh.setAction(ACTION_REFRESH);
        v.setOnClickPendingIntent(R.id.widgetRefresh, PendingIntent.getBroadcast(context, 7002,
                refresh, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
        if (user == null) {
            v.setTextViewText(R.id.widgetValue, "Log in");
            v.setTextViewText(R.id.widgetDay, "Open MogoMarket to sign in");
            v.setTextColor(R.id.widgetDay, GREY);
            return v;
        }

        try {
            DatabaseReference u = FirebaseDatabase.getInstance().getReference("users")
                    .child(user.getUid());
            DataSnapshot openSnap = Tasks.await(u.child("portfolio-stocks").get(), 30,
                    TimeUnit.SECONDS);
            List<StockData> open2 = new ArrayList<>();
            for (DataSnapshot c : openSnap.getChildren()) {
                StockData s = c.getValue(StockData.class);
                if (s != null) open2.add(s);
            }
            PortfolioDailyPerformance.Result r = PortfolioDailyPerformance.calculate(
                    open2, new ArrayList<>(), 0L, 0L);
            v.setTextViewText(R.id.widgetValue, String.format(Locale.US, "$%,.2f",
                    r.marketValue));
            v.setTextViewText(R.id.widgetDay, String.format(Locale.US, "Today %s%.2f%% (%s$%,.2f)",
                    r.dailyPnl >= 0 ? "+" : "-", Math.abs(r.dailyReturnPercent),
                    r.dailyPnl >= 0 ? "+" : "-", Math.abs(r.dailyPnl)));
            v.setTextColor(R.id.widgetDay, r.dailyPnl >= 0 ? GREEN : RED);
            v.setTextViewText(R.id.widgetTotal, String.format(Locale.US,
                    "Total %s$%,.2f", r.totalPnl >= 0 ? "+" : "-", Math.abs(r.totalPnl)));
            v.setTextColor(R.id.widgetTotal, r.totalPnl >= 0 ? GREEN : RED);

            DataSnapshot watch = Tasks.await(u.child("watchlist-stocks").get(), 30,
                    TimeUnit.SECONDS);
            int[] rows = {R.id.widgetW1, R.id.widgetW2, R.id.widgetW3};
            int n = 0;
            for (DataSnapshot c : watch.getChildren()) {
                if (n >= rows.length) break;
                String symbol = c.child("symbol").getValue(String.class);
                if (symbol == null || symbol.trim().isEmpty()) continue;
                String shown = symbol.contains(":")
                        ? symbol.substring(symbol.indexOf(':') + 1) : symbol;
                String quoteSymbol = shown;
                if (symbol.contains(":") && quoteSymbol.endsWith("USDT")) {
                    quoteSymbol = quoteSymbol.substring(0, quoteSymbol.length() - 4) + "-USD";
                }
                try {
                    PaperEngine.Quote q = PaperEngine.fetchBlocking(quoteSymbol);
                    v.setTextViewText(rows[n], String.format(Locale.US, "%s  $%,.2f  %+.2f%%",
                            shown, q.price, q.changePct()));
                    v.setTextColor(rows[n], q.changePct() >= 0 ? GREEN : RED);
                } catch (Exception e) {
                    v.setTextViewText(rows[n], shown + "  --");
                    v.setTextColor(rows[n], GREY);
                }
                n++;
            }
            v.setTextViewText(R.id.widgetUpdated, "Updated "
                    + new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
        } catch (Exception e) {
            Log.e(TAG, "Widget data failed", e);
            v.setTextViewText(R.id.widgetDay, "Could not load data - tap ⟳");
            v.setTextColor(R.id.widgetDay, GREY);
        }
        return v;
    }
}
