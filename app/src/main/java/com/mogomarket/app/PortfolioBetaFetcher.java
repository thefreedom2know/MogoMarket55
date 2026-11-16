package com.mogomarket.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PortfolioBetaFetcher {

    public interface Callback {
        void onFetched(Double beta, String error);
    }

    public static double calculateBeta(Map<String, Map<Long, Double>> histories, Map<String, Double> weights) {
        if (histories == null || weights == null || !histories.containsKey("SPY")) {
            return 1.0;
        }

        Map<Long, Double> spyHistory = histories.get("SPY");
        if (spyHistory == null || spyHistory.size() < 2) {
            return 1.0;
        }

        double totalWeightedBeta = 0.0;
        double totalWeight = 0.0;

        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            String symbol = entry.getKey();
            double weight = entry.getValue();
            if (weight <= 0) continue;
            totalWeight += weight;

            Map<Long, Double> stockHistory = histories.get(symbol);
            if (stockHistory == null || stockHistory.size() < 2) {
                totalWeightedBeta += weight;
                continue;
            }

            double stockBeta = computeBetaForSeries(stockHistory, spyHistory);
            totalWeightedBeta += weight * stockBeta;
        }

        return totalWeight > 0 ? totalWeightedBeta / totalWeight : 1.0;
    }

    private static double computeBetaForSeries(Map<Long, Double> stock, Map<Long, Double> spy) {
        List<Long> days = new ArrayList<>(spy.keySet());
        Collections.sort(days);

        List<Double> stockReturns = new ArrayList<>();
        List<Double> spyReturns = new ArrayList<>();

        for (int i = 1; i < days.size(); i++) {
            long prevDay = days.get(i - 1);
            long currDay = days.get(i);
            if (stock.containsKey(prevDay) && stock.containsKey(currDay)) {
                double spyPrev = spy.get(prevDay);
                double spyCurr = spy.get(currDay);
                double stockPrev = stock.get(prevDay);
                double stockCurr = stock.get(currDay);

                if (spyPrev > 0 && stockPrev > 0) {
                    double spyRet = (spyCurr - spyPrev) / spyPrev;
                    double stockRet = (stockCurr - stockPrev) / stockPrev;
                    spyReturns.add(spyRet);
                    stockReturns.add(stockRet);
                }
            }
        }

        if (spyReturns.size() < 2) return 1.0;

        double sumStock = 0, sumSpy = 0;
        for (int i = 0; i < spyReturns.size(); i++) {
            sumStock += stockReturns.get(i);
            sumSpy += spyReturns.get(i);
        }

        double meanStock = sumStock / spyReturns.size();
        double meanSpy = sumSpy / spyReturns.size();

        double covariance = 0;
        double varianceSpy = 0;

        for (int i = 0; i < spyReturns.size(); i++) {
            double diffStock = stockReturns.get(i) - meanStock;
            double diffSpy = spyReturns.get(i) - meanSpy;
            covariance += diffStock * diffSpy;
            varianceSpy += diffSpy * diffSpy;
        }

        return varianceSpy != 0 ? covariance / varianceSpy : 1.0;
    }

    public static void fetch(List<StockData> positions, double cash, Callback callback) {
        if (callback != null) {
            callback.onFetched(1.0, null);
        }
    }
}
