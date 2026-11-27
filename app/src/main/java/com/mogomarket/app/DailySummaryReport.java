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

/** Gathers market data and renders the daily summary e-mail (HTML + plain text, EN or HE). */
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

    // {ticker, English name, Hebrew name}
    private static final String[][] SECTORS = {
            {"XLK", "Technology", "טכנולוגיה"}, {"XLF", "Financials", "פיננסים"},
            {"XLV", "Health Care", "בריאות"},
            {"XLY", "Consumer Discretionary", "צריכה מחזורית"},
            {"XLP", "Consumer Staples", "צריכה בסיסית"},
            {"XLE", "Energy", "אנרגיה"}, {"XLI", "Industrials", "תעשייה"},
            {"XLB", "Materials", "חומרי גלם"}, {"XLU", "Utilities", "תשתיות"},
            {"XLRE", "Real Estate", "נדל\"ן"}, {"XLC", "Communication Services", "תקשורת"}};

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    private static final String GREEN = "#16a34a";
    private static final String RED = "#dc2626";
    private static final String GRAY = "#6b7280";

    // Language of the report being built (set at the start of build()).
    private static boolean he;
    // Weekly report: changes are since the start of the week instead of the previous close.
    private static boolean weekly;

    private static String s(String en, String hebrew) {
        return he ? hebrew : en;
    }

    private static String endAlign() {
        return he ? "left" : "right";
    }

    static final class Quote {
        final String label;
        final String symbol;
        double price = Double.NaN;
        double changePercent = Double.NaN;
        /** 0 = daily change; otherwise change since the last close before this time (ms). */
        long changeSince;

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
        boolean hebrew;
        /** Weekly summary: every change is measured since {@link #periodStart}. */
        boolean weekly;
        long periodStart;
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
                    + (q.changeSince > 0 ? "?range=1mo&interval=1d" : "?range=5d&interval=1d");
            Request req = new Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0").build();
            try (Response r = CLIENT.newCall(req).execute()) {
                if (!r.isSuccessful() || r.body() == null) return;
                JSONArray result = new JSONObject(r.body().string())
                        .getJSONObject("chart").optJSONArray("result");
                if (result == null || result.length() == 0) return;
                YahooQuoteMath.Quote parsed = YahooQuoteMath.fromChartItem(result.get(0));
                q.price = parsed.price > 0 ? parsed.price : Double.NaN;
                q.changePercent = q.changeSince > 0
                        ? YahooQuoteMath.changeSince(result.get(0), q.price, q.changeSince)
                        : parsed.changePercent;
            }
        } catch (Exception ignored) {
        }
    }

    static void loadAll(List<Quote> quotes) {
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
        he = in.hebrew;
        weekly = in.weekly;

        List<Quote> market = new ArrayList<>();
        addIf(in, market, ITEM_SPX, "S&P 500", "S&P 500", "^GSPC");
        addIf(in, market, ITEM_QQQ, "QQQ (Nasdaq 100)", "QQQ (נאסד\"ק 100)", "QQQ");
        addIf(in, market, ITEM_RSP, "RSP (equal-weight S&P)", "RSP (S&P במשקל שווה)", "RSP");
        addIf(in, market, ITEM_VIX, "VIX (volatility)", "VIX (מדד התנודתיות)", "^VIX");
        addIf(in, market, ITEM_OIL, "Oil (WTI crude)", "נפט (WTI)", "CL=F");

        List<Quote> sectors = new ArrayList<>();
        if (in.enabledItems.contains(ITEM_SECTORS)) {
            for (String[] sec : SECTORS) {
                sectors.add(new Quote((he ? sec[2] : sec[1]) + " (" + sec[0] + ")", sec[0]));
            }
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
        if (weekly) for (Quote q : all) q.changeSince = in.periodStart;
        loadAll(all);

        FearGreedFetcher.Snapshot fg = in.enabledItems.contains(ITEM_FEAR_GREED)
                ? FearGreedFetcher.fetchBlocking() : null;

        StringBuilder h = new StringBuilder();
        StringBuilder t = new StringBuilder();
        String dir = he ? "rtl" : "ltr";
        String align = he ? "right" : "left";
        String na = s("n/a", "-");
        String unavailable = s("Currently unavailable.", "לא זמין כרגע.");

        h.append("<html dir=\"").append(dir).append("\"><body dir=\"").append(dir)
                .append("\" style=\"margin:0;padding:0;background:#f3f4f6;\">")
                .append("<div dir=\"").append(dir).append("\" style=\"max-width:600px;")
                .append("margin:0 auto;padding:16px;text-align:").append(align)
                .append(";font-family:Arial,Helvetica,sans-serif;color:#111827;\">")
                .append("<div style=\"background:#0f172a;color:#ffffff;padding:20px 22px;")
                .append("border-radius:14px 14px 0 0;\">")
                .append("<div style=\"font-size:13px;color:#94a3b8;letter-spacing:1px;\">")
                .append("MOGOMARKET</div>")
                .append("<div style=\"font-size:24px;font-weight:bold;margin-top:4px;\">")
                .append(title()).append("</div>")
                .append("<div style=\"font-size:14px;color:#cbd5e1;margin-top:4px;\">")
                .append(esc(in.dateTitle)).append("</div></div>")
                .append("<div style=\"background:#ffffff;padding:6px 22px 22px;")
                .append("border-radius:0 0 14px 14px;\">");
        t.append("MogoMarket - ").append(title()).append('\n')
                .append(in.dateTitle).append("\n\n");

        // --- Market snapshot ---
        if (!market.isEmpty()) {
            section(h, weekly ? s("Market this week", "השוק השבוע")
                    : s("Market snapshot", "תמונת שוק"));
            t.append(weekly ? s("MARKET THIS WEEK", "השוק השבוע")
                    : s("MARKET SNAPSHOT", "תמונת שוק")).append('\n');
            tableOpen(h);
            for (Quote q : market) {
                quoteRow(h, q.label, q);
                t.append(q.label).append(": ").append(q.ok() ? fmtPrice(q.price) : na)
                        .append("  ").append(fmtPct(q.changePercent)).append('\n');
            }
            h.append("</table>");
            t.append('\n');
        }

        // --- Fear & Greed ---
        if (in.enabledItems.contains(ITEM_FEAR_GREED)) {
            section(h, s("Fear &amp; Greed index", "מדד הפחד והחמדנות"));
            t.append(s("FEAR & GREED (CNN)", "מדד הפחד והחמדנות (CNN)")).append('\n');
            if (fg == null) {
                h.append(muted(unavailable));
                t.append(unavailable).append("\n\n");
            } else {
                String color = fgColor(fg.score);
                String rating = ratingText(fg.rating);
                h.append("<div style=\"font-size:34px;font-weight:bold;color:").append(color)
                        .append(";\">").append(fg.score).append("<span style=\"font-size:16px;")
                        .append("margin:0 10px;\">").append(esc(rating))
                        .append("</span></div>")
                        .append("<div style=\"background:#e5e7eb;border-radius:6px;height:10px;")
                        .append("margin:8px 0 10px;\"><div style=\"background:").append(color)
                        .append(";height:10px;border-radius:6px;width:")
                        .append(Math.max(2, Math.min(100, fg.score))).append("%;\"></div></div>")
                        .append("<div style=\"font-size:13px;color:").append(GRAY).append(";\">")
                        .append(s("Previous close", "סגירה קודמת")).append(": <b>")
                        .append(fmtInt(fg.previousClose)).append("</b> &nbsp;|&nbsp; ")
                        .append(s("Week ago", "לפני שבוע")).append(": <b>")
                        .append(fmtInt(fg.previousWeek)).append("</b> &nbsp;|&nbsp; ")
                        .append(s("Month ago", "לפני חודש")).append(": <b>")
                        .append(fmtInt(fg.previousMonth)).append("</b></div>");
                t.append(fg.score).append(" - ").append(rating).append("  (")
                        .append(s("prev close ", "סגירה קודמת ")).append(fmtInt(fg.previousClose))
                        .append(", ").append(s("week ", "שבוע ")).append(fmtInt(fg.previousWeek))
                        .append(", ").append(s("month ", "חודש ")).append(fmtInt(fg.previousMonth))
                        .append(")\n\n");
            }
        }

        // --- Sectors ---
        if (in.enabledItems.contains(ITEM_SECTORS)) {
            List<Quote> ok = new ArrayList<>();
            for (Quote q : sectors) if (q.ok()) ok.add(q);
            Collections.sort(ok, (a, b) -> Double.compare(b.changePercent, a.changePercent));
            section(h, s("S&amp;P 500 sectors", "סקטורים ב-S&amp;P 500")
                    + (weekly ? s(" this week", " השבוע") : ""));
            t.append(s("S&P 500 SECTORS", "סקטורים ב-S&P 500"))
                    .append(weekly ? s(" THIS WEEK", " השבוע") : "").append('\n');
            if (ok.isEmpty()) {
                h.append(muted(unavailable));
                t.append(unavailable).append("\n\n");
            } else {
                String best = s("Best: ", "מובילים: ");
                String worst = s("Worst: ", "חלשים: ");
                tableOpen(h);
                int count = Math.min(3, ok.size());
                for (int i = 0; i < count; i++) {
                    quoteRow(h, best + ok.get(i).label, ok.get(i));
                    t.append(best).append(ok.get(i).label).append(' ')
                            .append(fmtPct(ok.get(i).changePercent)).append('\n');
                }
                for (int i = 0; i < count; i++) {
                    Quote q = ok.get(ok.size() - 1 - i);
                    quoteRow(h, worst + q.label, q);
                    t.append(worst).append(q.label).append(' ')
                            .append(fmtPct(q.changePercent)).append('\n');
                }
                h.append("</table>");
                t.append('\n');
            }
        }

        // --- Portfolio ---
        if (in.enabledItems.contains(ITEM_PORTFOLIO)) {
            PortfolioDailyPerformance.Result p = in.portfolio;
            section(h, s("My portfolio", "התיק שלי"));
            t.append(s("MY PORTFOLIO", "התיק שלי")).append('\n');
            if (p.valuedPositions == 0) {
                h.append(muted(s("No open positions with an investment amount.",
                        "אין פוזיציות פתוחות עם סכום השקעה.")));
                t.append(s("No open positions with an investment amount.",
                        "אין פוזיציות פתוחות עם סכום השקעה.")).append("\n\n");
            } else {
                String dayColor = p.dailyPnl >= 0 ? GREEN : RED;
                String totalColor = p.totalPnl >= 0 ? GREEN : RED;
                h.append("<div style=\"font-size:30px;font-weight:bold;color:").append(dayColor)
                        .append(";\">").append(fmtSignedMoney(p.dailyPnl))
                        .append(" <span style=\"font-size:16px;\">(")
                        .append(fmtPct(p.dailyReturnPercent)).append(")</span></div>")
                        .append("<div style=\"font-size:13px;color:").append(GRAY)
                        .append(";margin-top:2px;\">")
                        .append(weekly
                                ? s("This week's P&amp;L from open positions",
                                        "רווח/הפסד שבועי מהפוזיציות הפתוחות")
                                : s("Today's P&amp;L from open positions",
                                        "רווח/הפסד יומי מהפוזיציות הפתוחות")).append("</div>")
                        .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                        .append("style=\"border-collapse:collapse;margin-top:10px;\">");
                kvRow(h, s("Portfolio value", "שווי התיק"), fmtMoney(p.marketValue), null);
                kvRow(h, s("Invested", "הושקע"), fmtMoney(p.invested), null);
                kvRow(h, s("Total P&amp;L", "רווח/הפסד כולל"), fmtSignedMoney(p.totalPnl),
                        totalColor);
                kvRow(h, s("Open positions", "פוזיציות פתוחות"),
                        String.valueOf(in.openPositions), null);
                h.append("</table>");
                if (!p.positions.isEmpty()) {
                    List<PortfolioDailyPerformance.Position> sorted = new ArrayList<>(p.positions);
                    Collections.sort(sorted, (a, b) -> Double.compare(b.dailyPnl, a.dailyPnl));
                    h.append("<div style=\"font-size:12px;font-weight:bold;color:").append(GRAY)
                            .append(";margin:16px 0 4px;\">")
                            .append(s("Each position", "כל פוזיציה")).append("</div>")
                            .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                            .append("style=\"border-collapse:collapse;\">")
                            .append("<tr><td style=\"font-size:11px;color:").append(GRAY)
                            .append(";padding:4px 0;\">").append(s("Stock", "מניה")).append("</td>")
                            .append("<td align=\"").append(endAlign())
                            .append("\" style=\"font-size:11px;color:").append(GRAY)
                            .append(";padding:4px 0;\">").append(weekly ? s("This week", "השבוע") : s("Today", "היום")).append("</td>")
                            .append("<td align=\"").append(endAlign())
                            .append("\" style=\"font-size:11px;color:").append(GRAY)
                            .append(";padding:4px 0 4px 14px;\">")
                            .append(s("Since entry", "מאז הכניסה")).append("</td></tr>");
                    t.append(weekly
                            ? s("Each position (this week | since entry):",
                                    "כל פוזיציה (השבוע | מאז הכניסה):")
                            : s("Each position (today | since entry):",
                                    "כל פוזיציה (היום | מאז הכניסה):")).append('\n');
                    for (PortfolioDailyPerformance.Position pos : sorted) {
                        h.append("<tr><td style=\"padding:8px 0;border-top:1px solid #e5e7eb;")
                                .append("font-weight:bold;font-size:14px;\">")
                                .append(esc(pos.symbol)).append("</td>");
                        moneyPctCell(h, pos.dailyPnl, pos.dailyPercent, "8px 0");
                        moneyPctCell(h, pos.totalPnl, pos.totalPercent, "8px 0 8px 14px");
                        h.append("</tr>");
                        t.append(pos.symbol).append(": ").append(fmtSignedMoney(pos.dailyPnl))
                                .append(" (").append(fmtPct(pos.dailyPercent)).append(")  |  ")
                                .append(fmtSignedMoney(pos.totalPnl)).append(" (")
                                .append(fmtPct(pos.totalPercent)).append(")\n");
                    }
                    h.append("</table>");
                    t.append('\n');
                }
                t.append(weekly ? s("This week's P&L: ", "רווח/הפסד שבועי: ")
                                : s("Today's P&L: ", "רווח/הפסד יומי: ")).append(fmtSignedMoney(p.dailyPnl))
                        .append(" (").append(fmtPct(p.dailyReturnPercent)).append(")\n")
                        .append(s("Portfolio value: ", "שווי התיק: ")).append(fmtMoney(p.marketValue))
                        .append('\n').append(s("Total P&L: ", "רווח/הפסד כולל: "))
                        .append(fmtSignedMoney(p.totalPnl)).append('\n')
                        .append(s("Open positions: ", "פוזיציות פתוחות: ")).append(in.openPositions)
                        .append('\n');
                if (p.positionsWithoutAmount > 0) {
                    h.append(muted(s("Positions without an investment amount (not counted): ",
                            "פוזיציות ללא סכום השקעה (לא נספרו): ") + p.positionsWithoutAmount));
                    t.append(s("Not counted (no investment amount): ",
                            "לא נספרו (ללא סכום השקעה): ")).append(p.positionsWithoutAmount)
                            .append('\n');
                }
                t.append('\n');
            }
        }

        // --- Closed trades ---
        if (in.enabledItems.contains(ITEM_TRADES)) {
            section(h, s("Trades closed", "עסקאות שנסגרו"));
            t.append(s("TRADES CLOSED", "עסקאות שנסגרו")).append('\n');
            if (in.trades.isEmpty()) {
                String none = s("No trades were closed in this period.",
                        "לא נסגרו עסקאות בתקופה זו.");
                h.append(muted(none));
                t.append(none).append("\n\n");
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
                            .append("</td><td dir=\"ltr\" style=\"padding:7px 8px;")
                            .append("border-top:1px solid #e5e7eb;color:").append(GRAY)
                            .append(";font-size:13px;\">")
                            .append(fmtMoney(tr.buyPrice)).append(" &rarr; ")
                            .append(fmtMoney(tr.sellPrice)).append("</td>")
                            .append("<td dir=\"ltr\" align=\"").append(endAlign())
                            .append("\" style=\"padding:7px 0;")
                            .append("border-top:1px solid #e5e7eb;font-weight:bold;color:")
                            .append(c).append(";\">").append(fmtSignedMoney(tr.profitLoss))
                            .append("<div style=\"font-size:12px;font-weight:normal;\">")
                            .append(fmtPct(tradePercent(tr))).append("</div></td></tr>");
                    t.append(safe(tr.symbol)).append("  ").append(fmtMoney(tr.buyPrice))
                            .append(" -> ").append(fmtMoney(tr.sellPrice)).append("  ")
                            .append(fmtSignedMoney(tr.profitLoss)).append(" (")
                            .append(fmtPct(tradePercent(tr))).append(")\n");
                }
                int winRate = Math.round(wins * 100f / in.trades.size());
                h.append("<div style=\"font-size:13px;color:").append(GRAY).append(";\">")
                        .append(s("Closed", "נסגרו")).append(": <b>").append(in.trades.size())
                        .append("</b> &nbsp;|&nbsp; ").append(s("Wins", "רווחיות"))
                        .append(": <b style=\"color:").append(GREEN).append(";\">").append(wins)
                        .append("</b> &nbsp;|&nbsp; ").append(s("Losses", "הפסדיות"))
                        .append(": <b style=\"color:").append(RED).append(";\">").append(losses)
                        .append("</b> &nbsp;|&nbsp; ").append(s("Win rate", "אחוז הצלחה"))
                        .append(": <b>").append(winRate).append("%</b> &nbsp;|&nbsp; ")
                        .append(s("Total", "סה\"כ")).append(": <b style=\"color:")
                        .append(total >= 0 ? GREEN : RED).append(";\">")
                        .append(fmtSignedMoney(total)).append("</b></div>")
                        .append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                        .append("style=\"border-collapse:collapse;margin-top:8px;\">")
                        .append(rows).append("</table>");
                t.append(s("Closed: ", "נסגרו: ")).append(in.trades.size())
                        .append(", ").append(s("wins ", "רווחיות ")).append(wins)
                        .append(", ").append(s("losses ", "הפסדיות ")).append(losses)
                        .append(", ").append(s("win rate ", "אחוז הצלחה ")).append(winRate)
                        .append("%, ").append(s("total ", "סה\"כ ")).append(fmtSignedMoney(total))
                        .append("\n\n");
            }
        }

        // --- Watchlist movers ---
        if (in.enabledItems.contains(ITEM_WATCHLIST)) {
            List<Quote> ok = new ArrayList<>();
            for (Quote q : watch) if (q.ok()) ok.add(q);
            Collections.sort(ok, (a, b) -> Double.compare(b.changePercent, a.changePercent));
            section(h, weekly ? s("Watchlist movers this week", "תנועות ברשימת המעקב השבוע")
                    : s("Watchlist movers", "תנועות ברשימת המעקב"));
            t.append(weekly ? s("WATCHLIST MOVERS THIS WEEK", "תנועות ברשימת המעקב השבוע")
                    : s("WATCHLIST MOVERS", "תנועות ברשימת המעקב")).append('\n');
            if (ok.isEmpty()) {
                String empty = s("Your watchlist is empty or unavailable.",
                        "רשימת המעקב ריקה או לא זמינה.");
                h.append(muted(empty));
                t.append(empty).append("\n\n");
            } else {
                tableOpen(h);
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

        String footer = s("Generated automatically by MogoMarket. Market data may be delayed. "
                        + "Not investment advice.",
                "נוצר אוטומטית על ידי MogoMarket. נתוני השוק עשויים להתעכב. "
                        + "אין זה ייעוץ השקעות.");
        h.append("<div style=\"margin-top:22px;font-size:11px;color:#9ca3af;")
                .append("text-align:center;\">").append(footer).append("</div>")
                .append("</div></div></body></html>");
        t.append(footer).append('\n');

        Rendered out = new Rendered();
        out.html = h.toString();
        out.text = t.toString();
        return out;
    }

    private static String title() {
        return weekly ? s("Weekly Summary", "סיכום שבועי") : s("Daily Summary", "סיכום יומי");
    }

    private static void addIf(Input in, List<Quote> list, String id, String labelEn,
                              String labelHe, String symbol) {
        if (in.enabledItems.contains(id)) list.add(new Quote(he ? labelHe : labelEn, symbol));
    }

    // ---- HTML helpers ----

    private static void tableOpen(StringBuilder h) {
        h.append("<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" ")
                .append("style=\"border-collapse:collapse;\">");
    }

    private static void section(StringBuilder h, String title) {
        h.append("<div style=\"font-size:12px;font-weight:bold;letter-spacing:1px;")
                .append("text-transform:uppercase;color:#0891b2;margin:22px 0 8px;")
                .append("border-bottom:2px solid #e5e7eb;padding-bottom:6px;\">")
                .append(title).append("</div>");
    }

    private static String muted(String text) {
        return "<div style=\"font-size:13px;color:" + GRAY + ";margin-top:6px;\">" + text
                + "</div>";
    }

    private static double tradePercent(DailyTradeEvent tr) {
        return tr.buyPrice > 0 ? (tr.sellPrice - tr.buyPrice) / tr.buyPrice * 100.0 : Double.NaN;
    }

    private static void moneyPctCell(StringBuilder h, double money, double pct, String padding) {
        String color = money >= 0 ? GREEN : RED;
        h.append("<td dir=\"ltr\" align=\"").append(endAlign())
                .append("\" style=\"padding:").append(padding)
                .append(";border-top:1px solid #e5e7eb;font-size:14px;font-weight:bold;color:")
                .append(color).append(";\">").append(fmtSignedMoney(money))
                .append("<div style=\"font-size:12px;font-weight:normal;\">")
                .append(fmtPct(pct)).append("</div></td>");
    }

    private static void kvRow(StringBuilder h, String label, String value, @Nullable String color) {
        h.append("<tr><td style=\"padding:6px 0;border-top:1px solid #e5e7eb;font-size:14px;")
                .append("color:").append(GRAY).append(";\">").append(label).append("</td>")
                .append("<td dir=\"ltr\" align=\"").append(endAlign())
                .append("\" style=\"padding:6px 0;border-top:1px solid #e5e7eb;")
                .append("font-size:14px;font-weight:bold;");
        if (color != null) h.append("color:").append(color).append(";");
        h.append("\">").append(value).append("</td></tr>");
    }

    private static void quoteRow(StringBuilder h, String label, Quote q) {
        String color = !q.ok() ? GRAY : (q.changePercent >= 0 ? GREEN : RED);
        h.append("<tr><td style=\"padding:8px 0;border-top:1px solid #e5e7eb;font-size:14px;\">")
                .append(esc(label)).append("</td>")
                .append("<td dir=\"ltr\" align=\"").append(endAlign())
                .append("\" style=\"padding:8px 0;border-top:1px solid #e5e7eb;")
                .append("font-size:14px;\">").append(q.ok() ? fmtPrice(q.price) : "-")
                .append("</td><td dir=\"ltr\" align=\"").append(endAlign())
                .append("\" style=\"padding:8px 0 8px 14px;")
                .append("border-top:1px solid #e5e7eb;font-size:14px;font-weight:bold;color:")
                .append(color).append(";width:80px;\">").append(fmtPct(q.changePercent))
                .append("</td></tr>");
    }

    private static String ratingText(String rating) {
        String r = rating == null ? "" : rating.trim().toLowerCase(Locale.US);
        if (!he) return cap(r);
        switch (r) {
            case "extreme fear": return "פחד קיצוני";
            case "fear": return "פחד";
            case "neutral": return "ניטרלי";
            case "greed": return "חמדנות";
            case "extreme greed": return "חמדנות קיצונית";
            default: return r;
        }
    }

    private static String fgColor(int score) {
        if (score < 25) return "#dc2626";
        if (score < 45) return "#ea580c";
        if (score < 55) return "#ca8a04";
        if (score < 75) return "#65a30d";
        return "#16a34a";
    }

    private static String cap(String str) {
        if (str == null || str.isEmpty()) return "";
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private static String esc(@Nullable String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
        return Double.isNaN(v) ? "-" : String.format(Locale.US, "%+.2f%%", v);
    }

    private static String fmtMoney(double v) {
        return String.format(Locale.US, "$%,.2f", v);
    }

    private static String fmtSignedMoney(double v) {
        return String.format(Locale.US, "%s$%,.2f", v >= 0 ? "+" : "-", Math.abs(v));
    }
}
