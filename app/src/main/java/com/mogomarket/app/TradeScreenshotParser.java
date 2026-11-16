package com.mogomarket.app;

import java.util.ArrayList;
import java.util.List;

public class TradeScreenshotParser {

    public static class ParsedTrade {
        public String symbol;
        public double shares;
        public double buyPrice;
        public double tradeAmount;

        public ParsedTrade() {}

        public ParsedTrade(String symbol, double shares, double buyPrice, double tradeAmount) {
            this.symbol = symbol;
            this.shares = shares;
            this.buyPrice = buyPrice;
            this.tradeAmount = tradeAmount;
        }
    }

    public static ParsedTrade parse(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }

        ParsedTrade trade = new ParsedTrade();
        String[] lines = text.split("\n");
        List<Double> dollarValues = new ArrayList<>();

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            if (trade.symbol == null && line.matches("^[A-Z]{1,5}$")) {
                trade.symbol = line;
            } else if (trade.shares == 0 && line.matches("^[0-9]+\\.[0-9]+$")) {
                try {
                    trade.shares = Double.parseDouble(line);
                } catch (Exception ignored) {}
            } else if (line.contains("$")) {
                double val = parseDollarAmount(line);
                if (val > 0) {
                    dollarValues.add(val);
                }
            }
        }

        if (dollarValues.size() >= 2) {
            double totalValue = dollarValues.get(0);
            double lastGain = dollarValues.get(dollarValues.size() - 1);
            if (totalValue > lastGain && lastGain > 0) {
                trade.tradeAmount = totalValue - lastGain;
            } else {
                trade.tradeAmount = totalValue;
            }
        } else if (dollarValues.size() == 1) {
            trade.tradeAmount = dollarValues.get(0);
        }

        if (trade.shares > 0 && trade.tradeAmount > 0) {
            trade.buyPrice = trade.tradeAmount / trade.shares;
        }

        return trade.symbol != null ? trade : null;
    }

    private static double parseDollarAmount(String line) {
        try {
            int startPos = line.indexOf('$');
            if (startPos >= 0) {
                String sub = line.substring(startPos + 1).split("[\\s(]")[0];
                String clean = sub.replaceAll("[^0-9.]", "");
                if (!clean.isEmpty()) {
                    return Double.parseDouble(clean);
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }
}
