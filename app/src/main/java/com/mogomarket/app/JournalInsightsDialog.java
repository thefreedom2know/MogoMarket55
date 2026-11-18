package com.mogomarket.app;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.color.MaterialColors;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Trading-journal insights: how the user's closed trades perform when grouped by setup,
 * by emotional state (mood tag), by exit reason and by weekday. Shows where the edge is
 * and which habits cost money, and can hand the whole journal to the AI for a review.
 */
final class JournalInsightsDialog {

    static final String[] MOODS = {"None", "Disciplined", "FOMO", "Revenge trade", "Fear",
            "Greed", "Hesitated", "Over-sized"};

    private JournalInsightsDialog() { }

    private static final class Group {
        int trades;
        int wins;
        double pnl;
    }

    static void show(Context ctx, List<StockData> closed) {
        if (closed == null || closed.isEmpty()) {
            new AlertDialog.Builder(ctx).setTitle("Trading journal")
                    .setMessage("Close some trades first - the journal analyses your closed "
                            + "trades by setup, mood and exit reason.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        int text = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurface, Color.BLACK);
        int sub = MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        int gain = ContextCompat.getColor(ctx, R.color.gain);
        int loss = ContextCompat.getColor(ctx, R.color.loss);
        float d = ctx.getResources().getDisplayMetrics().density;

        Map<String, Group> bySetup = new LinkedHashMap<>();
        Map<String, Group> byMood = new LinkedHashMap<>();
        Map<String, Group> byExit = new LinkedHashMap<>();
        Map<String, Group> byDay = new LinkedHashMap<>();
        for (StockData t : closed) {
            if (t == null) continue;
            double pnl = StockData.calculateProfitLoss(t);
            add(bySetup, blank(t.strategyTag, "No setup tagged"), pnl);
            add(byMood, blank(t.moodTag, "Not tagged"), pnl);
            add(byExit, exitLabel(t.exitReason), pnl);
            if (t.closedAt > 0) {
                add(byDay, Instant.ofEpochMilli(t.closedAt).atZone(ZoneId.systemDefault())
                        .getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.US), pnl);
            }
        }

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding((int) (20 * d), (int) (10 * d), (int) (20 * d), (int) (10 * d));
        StringBuilder aiContext = new StringBuilder("Closed trades: " + closed.size());

        section(ctx, box, "By setup", bySetup, text, sub, gain, loss, d, aiContext);
        section(ctx, box, "By mood (tag it when editing a trade)", byMood, text, sub, gain, loss,
                d, aiContext);
        section(ctx, box, "By exit reason", byExit, text, sub, gain, loss, d, aiContext);
        section(ctx, box, "By weekday", byDay, text, sub, gain, loss, d, aiContext);

        aiContext.append("\n\nRecent trade notes:");
        int shown = 0;
        for (int i = closed.size() - 1; i >= 0 && shown < 15; i--) {
            StockData t = closed.get(i);
            if (t == null || t.notes == null || t.notes.trim().isEmpty()) continue;
            aiContext.append("\n- ").append(t.symbol).append(" (")
                    .append(String.format(Locale.US, "%+.2f", StockData.calculateProfitLoss(t)))
                    .append("): ").append(t.notes.trim());
            shown++;
        }

        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(box, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final String contextText = aiContext.toString();
        new AlertDialog.Builder(ctx)
                .setTitle("Trading journal insights")
                .setView(scroll)
                .setPositiveButton("Ask AI to review", (dlg, w) -> AiChatDialog.show(ctx,
                        "Review my trading journal. Find the patterns that make or lose me "
                                + "money, which setups and moods to avoid, and give me 3 "
                                + "concrete rules to follow.", contextText))
                .setNegativeButton("Close", null)
                .show();
    }

    private static String blank(String s, String fallback) {
        return s == null || s.trim().isEmpty() || "None".equals(s) ? fallback : s.trim();
    }

    private static String exitLabel(String reason) {
        if (reason == null) return "Manual exit";
        switch (reason) {
            case "HIT_TP": return "Hit take profit";
            case "HIT_SL": return "Hit stop loss";
            case "TRAILING_STOP": return "Trailing stop";
            default: return "Manual exit";
        }
    }

    private static void add(Map<String, Group> map, String key, double pnl) {
        Group g = map.get(key);
        if (g == null) {
            g = new Group();
            map.put(key, g);
        }
        g.trades++;
        g.pnl += pnl;
        if (pnl > 0) g.wins++;
    }

    private static void section(Context ctx, LinearLayout box, String title,
                                Map<String, Group> map, int text, int sub, int gain, int loss,
                                float d, StringBuilder aiContext) {
        if (map.isEmpty()) return;
        TextView header = new TextView(ctx);
        header.setText(title.toUpperCase(Locale.US));
        header.setTextSize(11);
        header.setLetterSpacing(0.08f);
        header.setTextColor(sub);
        header.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hp.topMargin = (int) (14 * d);
        box.addView(header, hp);
        aiContext.append("\n\n").append(title).append(":");

        List<Map.Entry<String, Group>> rows = new ArrayList<>(map.entrySet());
        rows.sort((a, b) -> Double.compare(b.getValue().pnl, a.getValue().pnl));
        for (Map.Entry<String, Group> e : rows) {
            Group g = e.getValue();
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout left = new LinearLayout(ctx);
            left.setOrientation(LinearLayout.VERTICAL);
            TextView name = new TextView(ctx);
            name.setText(e.getKey());
            name.setTextColor(text);
            name.setTextSize(14);
            name.setTypeface(null, Typeface.BOLD);
            left.addView(name);
            TextView detail = new TextView(ctx);
            detail.setText(String.format(Locale.US, "%d trades  ·  %.0f%% win rate  ·  "
                            + "avg %+.2f", g.trades, g.wins * 100.0 / g.trades, g.pnl / g.trades));
            detail.setTextColor(sub);
            detail.setTextSize(11);
            left.addView(detail);
            row.addView(left, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView total = new TextView(ctx);
            total.setText(String.format(Locale.US, "%s$%,.2f", g.pnl >= 0 ? "+" : "-",
                    Math.abs(g.pnl)));
            total.setTextColor(g.pnl >= 0 ? gain : loss);
            total.setTextSize(14);
            total.setTypeface(null, Typeface.BOLD);
            row.addView(total);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rp.topMargin = (int) (8 * d);
            box.addView(row, rp);
            aiContext.append("\n- ").append(e.getKey()).append(": ").append(g.trades)
                    .append(" trades, ").append(String.format(Locale.US, "%.0f%% win rate, "
                            + "total %+.2f", g.wins * 100.0 / g.trades, g.pnl));
        }
    }
}
