package com.mogomarket.app;

import java.util.List;

public class PortfolioDailyPerformance {

    public static class Result {
        public double profitLoss;
        public double totalPnl;
        public double dailyPnl;
        public double dailyReturnPercent;
        public int valuedPositions;
        public int positionsWithoutAmount;

        public Result() {}
    }

    public static double calculatePnl(double portfolioValue, double positionValue, double prevPrice, double currPrice) {
        if (positionValue == 0) return 0;
        return (currPrice - prevPrice) * (portfolioValue / positionValue);
    }

    public static Result calculate(List<StockData> openTrades, List<StockData> closedTrades, double initialCash) {
        return new Result();
    }

    public static Result calculate(List<StockData> openTrades, List<StockData> closedTrades, long startTimestamp, long endTimestamp) {
        return new Result();
    }
}
