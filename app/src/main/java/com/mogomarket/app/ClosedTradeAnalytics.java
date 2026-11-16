package com.mogomarket.app;

import java.util.List;

public class ClosedTradeAnalytics {
    public double profitFactor;
    public double averageWin;
    public double averageLoss;
    public double averageHoldingDays;
    public double maxDrawdown;

    public String bestStrategy;
    public double bestStrategyWinRate;
    public int bestStrategyTrades;

    public static ClosedTradeAnalytics calculate(List<StockData> trades) {
        ClosedTradeAnalytics analytics = new ClosedTradeAnalytics();
        if (trades == null || trades.isEmpty()) {
            return analytics;
        }

        double totalWinPnl = 0;
        double totalLossPnl = 0;
        int winCount = 0;
        int lossCount = 0;
        double totalHoldingDays = 0;

        double cumulativePnl = 0;
        double peakPnl = 0;
        double maxDd = 0;

        for (StockData trade : trades) {
            double pnl = StockData.calculateProfitLoss(trade);
            if (pnl > 0) {
                totalWinPnl += pnl;
                winCount++;
            } else if (pnl < 0) {
                totalLossPnl += Math.abs(pnl);
                lossCount++;
            }

            if (trade.closedAt > 0 && trade.openedAt > 0 && trade.closedAt >= trade.openedAt) {
                double days = (double) (trade.closedAt - trade.openedAt) / (1000.0 * 60 * 60 * 24);
                totalHoldingDays += days;
            }

            cumulativePnl += pnl;
            if (cumulativePnl > peakPnl) {
                peakPnl = cumulativePnl;
            }
            double dd = peakPnl - cumulativePnl;
            if (dd > maxDd) {
                maxDd = dd;
            }
        }

        analytics.profitFactor = totalLossPnl > 0 ? totalWinPnl / totalLossPnl : (totalWinPnl > 0 ? Double.POSITIVE_INFINITY : 0.0);
        analytics.averageWin = winCount > 0 ? totalWinPnl / winCount : 0.0;
        analytics.averageLoss = lossCount > 0 ? totalLossPnl / lossCount : 0.0;
        analytics.averageHoldingDays = trades.size() > 0 ? totalHoldingDays / trades.size() : 0.0;
        analytics.maxDrawdown = maxDd;

        return analytics;
    }
}
