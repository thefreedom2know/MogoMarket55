package com.mogomarket.app;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Gathers market data and renders the daily summary e-mail (HTML + plain text). */
final class DailySummaryReport {
    private DailySummaryReport() {}

    // ---- Selectable content items (ids are stored in preferences) ----
    static final String ITEM_VIX = "vix";
    static final String ITEM_OIL = "oil";
    static final String ITEM_SPX = "spx";
    static final String ITEM_RSP = "rsp";
    static final String ITEM_QQQ = "qqq";
    static final String ITEM_FEAR_GREED = "feargreed";
    static final String ITEM_PORTFOLIO = "portfolio";
    static final String ITEM_TRADES = "trades";
    static final String ITEM_WATCHLIST = "watchlist";
    static final String ITEM_SECTORS = "sectors";

    static final String[] ITEM_IDS = {ITEM_VIX, ITEM_OIL, ITEM_SPX, ITEM_RSP, ITEM_QQQ,
            ITEM_FEAR_GREED, ITEM_PORTFOLIO, ITEM_TRADES, ITEM_WATCHLIST, ITEM_SECTORS};
    static final String[] ITEM_LABELS = {"VIX (volatility)", "Oil (WTI crude)",
            "S&P 500 (SPX)", "RSP (equal-weight S&P)", "QQQ (Nasdaq 100)", "Fear & Greed index",
            "My portfolio P&L", "Trades I closed", "Watchlist top movers",
            "Best / worst S&P sectors"};

    private static final String[][] SECTORS = {
            {"XLK", "Technology"}, {"XLF", "Financials"}, {"XLV", "Health Care"},
            {"XLY", "Consumer Discretionary"}, {"XLP", "Consumer Staples"},
            {"XLE", "Energy"}, {"XLI", "Industrials"}, {"XLB", "Materials"},
            {"XLU", "Utilities"}, {"XLRE", "Real Estate"}, {"XLC", "Communication Services"}};

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final String GREEN = "#16a34a";
    private static final String RED = "#dc2626";
    private static final String GRAY = "#6b7280";

    static final class Quote {
        final String label;
        final String symbol;
        double price = Double.NaN;
        double changePercent = Double.NaN;

        Quote(String label, String symbol) {
            this.label = label;
            this.symbol = symbol;
        }

        boolean ok() {
            return !Double.isNaN(price) && !Double.isNaN(changePercent);
        }
    }

    static final class Rendered {
        String html;
        String text;
    }

    static final class Input {
        List<String> enabledItems = new ArrayList<>();
        String dateTitle;
        List<DailyTradeEvent> trades = new ArrayList<>();
        PortfolioDailyPerformance.Result portfolio = new PortfolioDailyPerformance.Result();
        int openPositions;
        List<String> watchlistSymbols = new ArrayList<>();
    }

    // -------------------------------------------------------------------------------------
    // Market data
    // -------------------------------------------------------------------------------------

    static void loadQuote(Quote q) {
        try {
            String url = ApiConfig.YAHOO_CHART
                    + URLEncoder.encode(q.symbol, StandardCharsets.UTF_8).replace("+", "%20")
                    + "?range=5d&interval=1d";
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response r = CLIENT.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return;
                JSONArray result = new JSONObject(r.body().string())
                        .getJSONObject("chart").optJSONArray("result");
                if (result == null || result.length() == 0) return;
                YahooQuoteMath.Quote parsed = YahooQuoteMath.fromChartItem(result.get(0));
                q.price = parsed.price > 0 ? parsed.price : Double.NaN;
                q.changePercent = parsed.changePercent;
            }
        } catch (Exception ignored) {
        }
    }

    private static void loadAll(List<Quote> quotes) {
        if (quotes.isEmpty()) return;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(6, quotes.size()));
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (final Quote q : quotes) {
                futures.add(pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        loadQuote(q);
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get(40, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // -------------------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------------------

    static Rendered build(Input in) {
        List<Quote> market = new ArrayList<>();
        addIf(in, market, ITEM_SPX, "S&P 500", "^GSPC");
        addIf(in, market, ITEM_QQQ, "QQQ (Nasdaq 100)", "QQQ");
        addIf(in, market, ITEM_RSP, "RSP (equal-weight S&P)", "RSP");
        addIf(in, market, ITEM_VIX, "VIX (volatility)", "^VIX");
        addIf(in, market, ITEM_OIL, "Oil (WTI crude)", "CL=F");

        List<Quote> sectors = new ArrayList<>();
        if (in.enabledItems.contains(ITEM_SECTORS)) {
            for (String[] s : SECTORS) sectors.add(new Quote(s[1] + " (" + s[0] + ")", s[0]));
        }

        List<Quote> watch = new ArrayList<>();
        if (in.enabledItems.contains(ITEM_WATCHLIST)) {
            for (String sym : in.watchlistSymbols) {
                if (watch.size() >= 25) break;
                watch.add(new Quote(sym, sym));
            }
        }

        List<Quote> all = new ArrayList<>(market);
        all.addAll(sectors);
        all.addAll(watch);
        loadAll(all);

        FearGreedFetcher.Snapshot fg = in.enabledItems.contains(ITEM_FEAR_GREED)
                ? FearGreedFetcher.fetchBlocking() : null;

        StringBuilder h = new StringBuilder();
        StringBuilder t = new StringBuilder();

        h.append("<html><body style=\"margin:0;padding:0;background:#f3f4f6;\">")
                .append("<div style=\"max-width:600px;margin:0 auto;padding:16px;")
                .append("font-family:Arial,Helvetica,sans-serif;color:#111827;\">")
                .append("<div style=\"background:#0f172a;color:#ffffff;padding:20px 22px;")
                .append("border-radius:14px 14px 0 0;\">")
                .append("<div style=\"font-size:13px;color:#94a3b8;letter-spacing:1px;\">")
                .append("MOGOMARKET</div>")
                .append("<div style=\"font-size:24px;font-weight:bold;margin-top:4px;\">")
                .append("Daily Summary</div>")
                .append("<div style=\"font-size:14px;color:#cbd5e1;margin-top:4px;\">")
                .append(esc(in.dateTitle)).append("</div></div>")
                .append("<div style=\"background:#ffffff;padding:6px 22px 22px;")
                .append("border-radius:0 0 14px 14px;\">");
        t.append("MogoMarket Daily Summary\n").append(in.dateTitle).append("\n\n");

        // --- Market snapshot ---
        if (!market.isEmpty()) {
            section(h, "Market snapshot");
            t.append("MARKET SNAPSHOT\n");
            h.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                    .append("style=\"border-collapse:collapse;\">");
            for (Quote q : market) {
                quoteRow(h, q.label, q);
                t.append(String.format(Locale.US, "%-26s %s  %s%n", q.label,
                        q.ok() ? fmtPrice(q.price) : "n/a", fmtPct(q.changePercent)));
            }
            h.append("</table>");
            t.append('\n');
        }

        // --- Fear & Greed ---
        if (in.enabledItems.contains(ITEM_FEAR_GREED)) {
            section(h, "Fear &amp; Greed index");
            t.append("FEAR & GREED (CNN)\n");
            if (fg == null) {
                h.append(muted("Currently unavailable."));
                t.append("Currently unavailable.\n\n");
            } else {
                String color = fgColor(fg.score);
                h.append("<div style=\"font-size:34px;font-weight:bold;color:").append(color)
                        .append(";\">").append(fg.score).append("<span style=\"font-size:16px;")
                        .append("margin-left:10px;\">").append(esc(cap(fg.rating)))
                        .append("</span></div>")
                        .append("<div style=\"background:#e5e7eb;border-radius:6px;height:10px;")
                        .append("margin:8px 0 10px;\"><div style=\"background:").append(color)
                        .append(";height:10px;border-radius:6px;width:")
                        .append(Math.max(2, Math.min(100, fg.score))).append("%;\"></div></div>")
                        .append("<div style=\"font-size:13px;color:").append(GRAY).append(";\">")
                        .append("Previous close: <b>").append(fmtInt(fg.previousClose)).append("</b>")
                        .append(" &nbsp;|&nbsp; Week ago: <b>").append(fmtInt(fg.previousWeek))
                        .append("</b> &nbsp;|&nbsp; Month ago: <b>")
                        .append(fmtInt(fg.previousMonth)).append("</b></div>");
                t.append(fg.score).append(" - ").append(cap(fg.rating))
                        .append("  (prev close ").append(fmtInt(fg.previousClose))
                        .append(", week ").append(fmtInt(fg.previousWeek))
                        .append(", month ").append(fmtInt(fg.previousMonth)).append(")\n\n");
            }
        }

        // --- Sectors ---
        if (in.enabledItems.contains(ITEM_SECTORS)) {
            List<Quote> ok = new ArrayList<>();
            for (Quote q : sectors) if (q.ok()) ok.add(q);
            Collections.sort(ok, (a, b) -> Double.compare(b.changePercent, a.changePercent));
            section(h, "S&amp;P 500 sectors");
            t.append("S&P 500 SECTORS\n");
            if (ok.isEmpty()) {
                h.append(muted("Currently unavailable."));
                t.append("Currently unavailable.\n\n");
            } else {
                h.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                        .append("style=\"border-collapse:collapse;\">");
                int count = Math.min(3, ok.size());
                for (int i = 0; i < count; i++) {
                    quoteRow(h, "Best: " + ok.get(i).label, ok.get(i));
                    t.append("Best:  ").append(ok.get(i).label).append(' ')
                            .append(fmtPct(ok.get(i).changePercent)).append('\n');
                }
                for (int i = 0; i < count; i++) {
                    Quote q = ok.get(ok.size() - 1 - i);
                    quoteRow(h, "Worst: " + q.label, q);
                    t.append("Worst: ").append(q.label).append(' ')
                            .append(fmtPct(q.changePercent)).append('\n');
                }
                h.append("</table>");
                t.append('\n');
            }
        }

        // --- Portfolio ---
        if (in.enabledItems.contains(ITEM_PORTFOLIO)) {
            PortfolioDailyPerformance.Result p = in.portfolio;
            section(h, "My portfolio");
            t.append("MY PORTFOLIO\n");
            String pnlColor = p.profitLoss >= 0 ? GREEN : RED;
            h.append("<div style=\"font-size:30px;font-weight:bold;color:").append(pnlColor)
                    .append(";\">").append(fmtSignedMoney(p.profitLoss)).append("</div>")
                    .append("<div style=\"font-size:13px;color:").append(GRAY)
                    .append(";margin-top:2px;\">Daily P&amp;L from held positions</div>")
                    .append("<div style=\"font-size:13px;color:").append(GRAY)
                    .append(";margin-top:8px;\">Open positions: <b>").append(in.openPositions)
                    .append("</b> &nbsp;|&nbsp; Valued: <b>").append(p.valuedPositions)
                    .append("</b>");
            t.append("Daily P&L: ").append(fmtSignedMoney(p.profitLoss)).append('\n')
                    .append("Open positions: ").append(in.openPositions)
                    .append(", valued: ").append(p.valuedPositions).append('\n');
            if (p.positionsWithoutAmount > 0) {
                h.append(" &nbsp;|&nbsp; Without amount (excluded): <b>")
                        .append(p.positionsWithoutAmount).append("</b>");
                t.append("Excluded (no investment amount): ").append(p.positionsWithoutAmount)
                        .append('\n');
            }
            h.append("</div>");
            t.append('\n');
        }

        // --- Closed trades ---
        if (in.enabledItems.contains(ITEM_TRADES)) {
            section(h, "Trades closed");
            t.append("TRADES CLOSED\n");
            if (in.trades.isEmpty()) {
                h.append(muted("No trades were closed in this period."));
                t.append("No trades were closed in this period.\n\n");
            } else {
                double total = 0;
                int wins = 0;
                int losses = 0;
                StringBuilder rows = new StringBuilder();
                for (DailyTradeEvent tr : in.trades) {
                    total += tr.profitLoss;
                    if (tr.profitLoss > 0) wins++;
                    else if (tr.profitLoss < 0) losses++;
                    String c = tr.profitLoss >= 0 ? GREEN : RED;
                    rows.append("<tr><td style=\"padding:7px 0;border-top:1px solid #e5e7eb;")
                            .append("font-weight:bold;\">").append(esc(safe(tr.symbol)))
                            .append("</td><td style=\"padding:7px 0;border-top:1px solid #e5e7eb;")
                            .append("color:").append(GRAY).append(";font-size:13px;\">")
                            .append(fmtMoney(tr.buyPrice)).append(" &rarr; ")
                            .append(fmtMoney(tr.sellPrice)).append("</td>")
                            .append("<td align=\"right\" style=\"padding:7px 0;")
                            .append("border-top:1px solid #e5e7eb;font-weight:bold;color:")
                            .append(c).append(";\">").append(fmtSignedMoney(tr.profitLoss))
                            .append("</td></tr>");
                    t.append(String.format(Locale.US, "%-8s %s -> %s  %s%n", safe(tr.symbol),
                            fmtMoney(tr.buyPrice), fmtMoney(tr.sellPrice),
                            fmtSignedMoney(tr.profitLoss)));
                }
                int winRate = Math.round(wins * 100f / in.trades.size());
                h.append("<div style=\"font-size:13px;color:").append(GRAY).append(";\">")
                        .append("Closed: <b>").append(in.trades.size()).append("</b> &nbsp;|&nbsp; ")
                        .append("Wins: <b style=\"color:").append(GREEN).append(";\">").append(wins)
                        .append("</b> &nbsp;|&nbsp; Losses: <b style=\"color:").append(RED)
                        .append(";\">").append(losses).append("</b> &nbsp;|&nbsp; Win rate: <b>")
                        .append(winRate).append("%</b> &nbsp;|&nbsp; Total: <b style=\"color:")
                        .append(total >= 0 ? GREEN : RED).append(";\">")
                        .append(fmtSignedMoney(total)).append("</b></div>")
                        .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                        .append("style=\"border-collapse:collapse;margin-top:8px;\">")
                        .append(rows).append("</table>");
                t.append("Closed: ").append(in.trades.size()).append(", wins ").append(wins)
                        .append(", losses ").append(losses).append(", win rate ").append(winRate)
                        .append("%, total ").append(fmtSignedMoney(total)).append("\n\n");
            }
        }

        // --- Watchlist movers ---
        if (in.enabledItems.contains(ITEM_WATCHLIST)) {
            List<Quote> ok = new ArrayList<>();
            for (Quote q : watch) if (q.ok()) ok.add(q);
            Collections.sort(ok, (a, b) -> Double.compare(b.changePercent, a.changePercent));
            section(h, "Watchlist movers");
            t.append("WATCHLIST MOVERS\n");
            if (ok.isEmpty()) {
                h.append(muted("Your watchlist is empty or unavailable."));
                t.append("Your watchlist is empty or unavailable.\n\n");
            } else {
                h.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                        .append("style=\"border-collapse:collapse;\">");
                int count = Math.min(3, ok.size());
                for (int i = 0; i < count; i++) {
                    Quote q = ok.get(i);
                    quoteRow(h, q.label, q);
                    t.append(q.label).append(' ').append(fmtPct(q.changePercent)).append('\n');
                }
                for (int i = 0; i < count; i++) {
                    int index = ok.size() - 1 - i;
                    if (index < count) continue; // avoid repeating a row on short lists
                    Quote q = ok.get(index);
                    quoteRow(h, q.label, q);
                    t.append(q.label).append(' ').append(fmtPct(q.changePercent)).append('\n');
                }
                h.append("</table>");
                t.append('\n');
            }
        }

        h.append("<div style=\"margin-top:22px;font-size:11px;color:#9ca3af;")
                .append("text-align:center;\">Generated automatically by MogoMarket. ")
                .append("Market data may be delayed. Not investment advice.</div>")
                .append("</div></div></body></html>");
        t.append("Generated automatically by MogoMarket. Market data may be delayed.\n");

        Rendered out = new Rendered();
        out.html = h.toString();
        out.text = t.toString();
        return out;
    }

    private static void addIf(Input in, List<Quote> list, String id, String label, String symbol) {
        if (in.enabledItems.contains(id)) list.add(new Quote(label, symbol));
    }

    // ---- HTML helpers ----

    private static void section(StringBuilder h, String title) {
        h.append("<div style=\"font-size:12px;font-weight:bold;letter-spacing:1px;")
                .append("text-transform:uppercase;color:#0891b2;margin:22px 0 8px;")
                .append("border-bottom:2px solid #e5e7eb;padding-bottom:6px;\">")
                .append(title).append("</div>");
    }

    private static String muted(String text) {
        return "<div style=\"font-size:13px;color:" + GRAY + ";\">" + text + "</div>";
    }

    private static void quoteRow(StringBuilder h, String label, Quote q) {
        String color = !q.ok() ? GRAY : (q.changePercent >= 0 ? GREEN : RED);
        h.append("<tr><td style=\"padding:8px 0;border-top:1px solid #e5e7eb;font-size:14px;\">")
                .append(esc(label)).append("</td>")
                .append("<td align=\"right\" style=\"padding:8px 0;border-top:1px solid #e5e7eb;")
                .append("font-size:14px;\">").append(q.ok() ? fmtPrice(q.price) : "n/a")
                .append("</td><td align=\"right\" style=\"padding:8px 0 8px 14px;")
                .append("border-top:1px solid #e5e7eb;font-size:14px;font-weight:bold;color:")
                .append(color).append(";width:80px;\">").append(fmtPct(q.changePercent))
                .append("</td></tr>");
    }

    private static String fgColor(int score) {
        if (score < 25) return "#dc2626";
        if (score < 45) return "#ea580c";
        if (score < 55) return "#ca8a04";
        if (score < 75) return "#65a30d";
        return "#16a34a";
    }

    private static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String esc(@Nullable String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String safe(@Nullable String v) {
        return v == null || v.trim().isEmpty() ? "N/A" : v;
    }

    private static String fmtPrice(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }

    private static String fmtInt(double v) {
        return Double.isNaN(v) ? "-" : String.valueOf(Math.round(v));
    }

    private static String fmtPct(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.US, "%+.2f%%", v);
    }

    private static String fmtMoney(double v) {
        return String.format(Locale.US, "$%,.2f", v);
    }

    private static String fmtSignedMoney(double v) {
        return String.format(Locale.US, "%s$%,.2f", v >= 0 ? "+" : "-", Math.abs(v));
    }
}
