package com.mogomarket.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PortfolioDailyPerformance {

    /** One open position: how much it moved today and since entry. */
    public static class Position {
        public String symbol;
        public double value;
        public double dailyPnl;
        public double dailyPercent;
        public double totalPnl;
        public double totalPercent;
    }

    public static class Result {
        public List<Position> positions = new ArrayList<>();
        public double profitLoss;          // same as dailyPnl (kept for older callers)
        public double totalPnl;            // unrealized P&L since entry
        public double dailyPnl;            // today's change in dollars
        public double dailyReturnPercent;  // today's change in percent
        public double marketValue;         // current value of counted positions
        public double invested;            // amount put into counted positions
        public int valuedPositions;
        public int positionsWithoutAmount;

        public Result() {}
    }

    public static double calculatePnl(double portfolioValue, double positionValue, double prevPrice, double currPrice) {
        if (positionValue == 0) return 0;
        return (currPrice - prevPrice) * (portfolioValue / positionValue);
    }

    public static Result calculate(List<StockData> openTrades, List<StockData> closedTrades, double initialCash) {
        return calculate(openTrades, closedTrades, 0L, 0L);
    }

    /**
     * Daily P&L of the open positions, computed like the Portfolio screen does: the value of
     * each position now, minus its value at the previous close. Fetches live quotes, so call it
     * from a background thread.
     */
    public static Result calculate(List<StockData> openTrades, List<StockData> closedTrades,
                                   long startTimestamp, long endTimestamp) {
        return calculate(openTrades, closedTrades, startTimestamp, endTimestamp, false);
    }

    /**
     * With {@code weekly} the "daily" fields hold the change since {@code startTimestamp}
     * (the start of the week) instead of since the previous close. A position opened during
     * the week is measured from its entry.
     */
    public static Result calculate(List<StockData> openTrades, List<StockData> closedTrades,
                                   long startTimestamp, long endTimestamp, boolean weekly) {
        Result result = new Result();
        if (openTrades == null) return result;

        List<StockData> counted = new ArrayList<>();
        List<DailySummaryReport.Quote> quotes = new ArrayList<>();
        for (StockData stock : openTrades) {
            if (stock == null) continue;
            if (stock.buyPrice <= 0 || stock.tradeAmount <= 0) {
                result.positionsWithoutAmount++;
                continue;
            }
            counted.add(stock);
            String symbol = quoteSymbol(stock.symbol);
            // Crypto pairs (BINANCE:XXX) are not on Yahoo: those fall back to stored values.
            DailySummaryReport.Quote quote = new DailySummaryReport.Quote(
                    stock.symbol == null ? "" : stock.symbol, symbol.contains(":") ? "" : symbol);
            if (weekly) quote.changeSince = startTimestamp;
            quotes.add(quote);
        }
        List<DailySummaryReport.Quote> toLoad = new ArrayList<>();
        for (DailySummaryReport.Quote q : quotes) if (!q.symbol.isEmpty()) toLoad.add(q);
        DailySummaryReport.loadAll(toLoad);

        double previousValueSum = 0;
        for (int i = 0; i < counted.size(); i++) {
            StockData stock = counted.get(i);
            DailySummaryReport.Quote q = quotes.get(i);

            double price = q.ok() ? q.price
                    : (stock.currentPrice > 0 ? stock.currentPrice : stock.buyPrice);
            double value = stock.tradeAmount * price / stock.buyPrice;
            double dayPercent;
            double previousValue;
            if (weekly && stock.openedAt > 0 && stock.openedAt >= startTimestamp) {
                // Bought this week: the whole move since entry belongs to this week.
                previousValue = stock.tradeAmount;
                dayPercent = (value / stock.tradeAmount - 1.0) * 100.0;
            } else {
                if (q.ok()) {
                    dayPercent = q.changePercent;
                } else if (weekly) {
                    dayPercent = 0; // no weekly history (e.g. crypto): counted as unchanged
                } else {
                    dayPercent = Double.isNaN(stock.dailyProfitLossPercent)
                            ? 0 : stock.dailyProfitLossPercent;
                }
                double factor = 1.0 + dayPercent / 100.0;
                previousValue = factor > 0 ? value / factor : value;
            }

            Position position = new Position();
            position.symbol = stock.symbol == null ? "N/A" : stock.symbol;
            position.value = value;
            position.dailyPnl = value - previousValue;
            position.dailyPercent = dayPercent;
            position.totalPnl = value - stock.tradeAmount;
            position.totalPercent = (value / stock.tradeAmount - 1.0) * 100.0;
            result.positions.add(position);

            result.invested += stock.tradeAmount;
            result.marketValue += value;
            result.totalPnl += value - stock.tradeAmount;
            result.dailyPnl += value - previousValue;
            previousValueSum += previousValue;
            result.valuedPositions++;
        }
        result.profitLoss = result.dailyPnl;
        result.dailyReturnPercent = previousValueSum > 0
                ? result.dailyPnl / previousValueSum * 100.0 : 0.0;
        return result;
    }

    private static String quoteSymbol(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.US);
        if ("SPX".equals(s) || "^SPX".equals(s) || "GSPC".equals(s)) return "^GSPC";
        String mapped = ChartFragment.FOREX_MAP.get(s);
        return mapped != null ? mapped : s;
    }
}
