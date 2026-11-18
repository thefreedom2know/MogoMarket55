package com.mogomarket.app;

import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Builds CSV exports (opens in Excel / Google Sheets) of the user's data. */
final class DataExport {

    static final String[] LABELS = {
            "Closed trades",
            "Open portfolio positions",
            "Simulator trades",
            "Simulator equity history"};
    static final String[] FILE_NAMES = {
            "mogo-closed-trades.csv",
            "mogo-open-positions.csv",
            "mogo-simulator-trades.csv",
            "mogo-simulator-equity.csv"};

    interface Done {
        void onDone(@Nullable String csv, @Nullable String error);
    }

    private DataExport() { }

    static void build(int kind, String uid, Done done) {
        DatabaseReference user = FirebaseDatabase.getInstance().getReference("users").child(uid);
        String path;
        switch (kind) {
            case 0: path = "closed-trades"; break;
            case 1: path = "portfolio-stocks"; break;
            case 2: path = "paper-trading/trades"; break;
            default: path = "paper-equity"; break;
        }
        user.child(path).get().addOnSuccessListener(snapshot -> {
            try {
                switch (kind) {
                    case 0: done.onDone(closedTrades(snapshot), null); break;
                    case 1: done.onDone(openPositions(snapshot), null); break;
                    case 2: done.onDone(paperTrades(snapshot), null); break;
                    default: done.onDone(paperEquity(snapshot), null); break;
                }
            } catch (Exception e) {
                done.onDone(null, "Export failed: " + e.getMessage());
            }
        }).addOnFailureListener(e -> done.onDone(null, "Could not load data: " + e.getMessage()));
    }

    private static String closedTrades(DataSnapshot snapshot) {
        List<StockData> trades = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) {
            StockData trade = child.getValue(StockData.class);
            if (trade != null) trades.add(trade);
        }
        return "﻿" + TradeCsv.exportTrades(trades);
    }

    private static String openPositions(DataSnapshot snapshot) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("symbol,buy_price,invested_amount,current_price,sector,strategy,notes,opened_at\n");
        for (DataSnapshot child : snapshot.getChildren()) {
            StockData s = child.getValue(StockData.class);
            if (s == null) continue;
            sb.append(q(s.symbol)).append(',')
                    .append(s.buyPrice).append(',')
                    .append(s.tradeAmount).append(',')
                    .append(s.currentPrice).append(',')
                    .append(q(s.sector)).append(',')
                    .append(q(s.strategyTag)).append(',')
                    .append(q(s.notes)).append(',')
                    .append(q(date(s.openedAt))).append('\n');
        }
        return sb.toString();
    }

    private static String paperTrades(DataSnapshot snapshot) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("date,symbol,side,type,shares,price,notional,fee,realized_pnl,note\n");
        List<DataSnapshot> rows = new ArrayList<>();
        for (DataSnapshot child : snapshot.getChildren()) rows.add(child);
        rows.sort((a, b) -> Double.compare(num(a, "executedAt"), num(b, "executedAt")));
        for (DataSnapshot r : rows) {
            String side = r.child("side").getValue(String.class);
            String type = r.child("type").getValue(String.class);
            String note = r.child("note").getValue(String.class);
            sb.append(q(date((long) num(r, "executedAt")))).append(',')
                    .append(q(r.child("symbol").getValue(String.class))).append(',')
                    .append(q(side)).append(',')
                    .append(q(type == null ? "MARKET" : type)).append(',')
                    .append(num(r, "shares")).append(',')
                    .append(num(r, "price")).append(',')
                    .append(num(r, "notional")).append(',')
                    .append(num(r, "fee")).append(',')
                    .append(num(r, "realizedPnl")).append(',')
                    .append(q(note)).append('\n');
        }
        return sb.toString();
    }

    private static String paperEquity(DataSnapshot snapshot) {
        StringBuilder sb = new StringBuilder("﻿");
        sb.append("date,equity\n");
        for (DataSnapshot child : snapshot.getChildren()) {
            sb.append(q(date((long) num(child, "t")))).append(',')
                    .append(String.format(Locale.US, "%.2f", num(child, "v"))).append('\n');
        }
        return sb.toString();
    }

    /** Writes the CSV text to the file the user picked. Returns an error text or null. */
    static String write(android.content.Context context, android.net.Uri uri, String csv) {
        try (java.io.OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) return "Could not open the selected file";
            out.write(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return null;
        } catch (java.io.IOException | SecurityException e) {
            return "Export failed: " + e.getMessage();
        }
    }

    static String paperTradesCsvFromUser(DataSnapshot tradesSnapshot) {
        return paperTrades(tradesSnapshot);
    }

    private static double num(DataSnapshot s, String key) {
        Double v = s.child(key).getValue(Double.class);
        return v == null ? 0 : v;
    }

    private static String date(long ms) {
        if (ms <= 0) return "";
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static String q(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
