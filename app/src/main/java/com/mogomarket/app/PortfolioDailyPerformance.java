package com.mogomarket.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PortfolioDailyPerformance {

    public static class Result {
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
            quotes.add(new DailySummaryReport.Quote(stock.symbol == null ? "" : stock.symbol,
                    symbol.contains(":") ? "" : symbol));
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
            double dayPercent = q.ok() ? q.changePercent
                    : (Double.isNaN(stock.dailyProfitLossPercent) ? 0 : stock.dailyProfitLossPercent);

            double value = stock.tradeAmount * price / stock.buyPrice;
            double factor = 1.0 + dayPercent / 100.0;
            double previousValue = factor > 0 ? value / factor : value;

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
