package com.mogomarket.app;

import org.json.JSONArray;
import org.json.JSONObject;

public class YahooQuoteMath {

    public static class Quote {
        public double currentPrice;
        public double price;
        public double previousClose;
        public double changePercent;

        public double percentChange() {
            return changePercent;
        }
    }

    public static double selectPreviousClose(double v1, double v2, double v3) {
        if (!Double.isNaN(v1) && v1 > 0) return v1;
        if (!Double.isNaN(v2) && v2 > 0) return v2;
        if (!Double.isNaN(v3) && v3 > 0) return v3;
        return Double.NaN;
    }

    /**
     * Builds a quote from one element of a Yahoo v8 chart "result" array.
     * Price comes from meta.regularMarketPrice. The previous close is meta.previousClose when
     * Yahoo sends it (intraday ranges); for daily ranges it is the second-to-last daily close.
     * meta.chartPreviousClose is only a last resort because it is the close before the range.
     */
    public static Quote fromChartItem(Object item) {
        Quote quote = new Quote();
        quote.changePercent = Double.NaN;
        if (!(item instanceof JSONObject)) return quote;
        JSONObject obj = (JSONObject) item;
        JSONObject meta = obj.optJSONObject("meta");

        double lastClose = Double.NaN;
        double secondLastClose = Double.NaN;
        JSONObject indicators = obj.optJSONObject("indicators");
        JSONArray quotes = indicators != null ? indicators.optJSONArray("quote") : null;
        JSONObject first = quotes != null ? quotes.optJSONObject(0) : null;
        JSONArray closes = first != null ? first.optJSONArray("close") : null;
        if (closes != null) {
            for (int i = closes.length() - 1; i >= 0; i--) {
                if (closes.isNull(i)) continue;
                if (Double.isNaN(lastClose)) {
                    lastClose = closes.optDouble(i, Double.NaN);
                } else {
                    secondLastClose = closes.optDouble(i, Double.NaN);
                    break;
                }
            }
        }

        double price = meta != null ? meta.optDouble("regularMarketPrice", Double.NaN) : Double.NaN;
        if (Double.isNaN(price) || price <= 0) price = lastClose;

        double metaPrevious = meta != null ? meta.optDouble("previousClose", Double.NaN) : Double.NaN;
        double chartPrevious = meta != null ? meta.optDouble("chartPreviousClose", Double.NaN) : Double.NaN;
        double previous = selectPreviousClose(metaPrevious, secondLastClose, chartPrevious);

        quote.currentPrice = Double.isNaN(price) ? 0 : price;
        quote.price = quote.currentPrice;
        quote.previousClose = Double.isNaN(previous) ? 0 : previous;
        quote.changePercent = (!Double.isNaN(price) && !Double.isNaN(previous) && previous > 0)
                ? (price - previous) / previous * 100.0 : Double.NaN;
        return quote;
    }
    /**
     * The last valid close strictly before {@code cutoffSec} (Unix seconds), or NaN.
     * Used by the weekly summary: the close just before the week started.
     */
    public static double closeBefore(long[] timestampsSec, double[] closes, long cutoffSec) {
        if (timestampsSec == null || closes == null) return Double.NaN;
        int n = Math.min(timestampsSec.length, closes.length);
        for (int i = n - 1; i >= 0; i--) {
            if (timestampsSec[i] >= cutoffSec) continue;
            double c = closes[i];
            if (!Double.isNaN(c) && c > 0) return c;
        }
        return Double.NaN;
    }

    /** Change in percent since the last close before {@code cutoffMillis} (NaN if unknown). */
    public static double changeSince(Object item, double price, long cutoffMillis) {
        if (!(item instanceof JSONObject) || Double.isNaN(price) || price <= 0) return Double.NaN;
        JSONObject obj = (JSONObject) item;
        JSONArray ts = obj.optJSONArray("timestamp");
        JSONObject indicators = obj.optJSONObject("indicators");
        JSONArray quotes = indicators != null ? indicators.optJSONArray("quote") : null;
        JSONObject first = quotes != null ? quotes.optJSONObject(0) : null;
        JSONArray closes = first != null ? first.optJSONArray("close") : null;
        if (ts == null || closes == null) return Double.NaN;
        int n = Math.min(ts.length(), closes.length());
        long[] t = new long[n];
        double[] c = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = ts.optLong(i, Long.MAX_VALUE);
            c[i] = closes.isNull(i) ? Double.NaN : closes.optDouble(i, Double.NaN);
        }
        double reference = closeBefore(t, c, cutoffMillis / 1000L);
        return Double.isNaN(reference) ? Double.NaN : (price - reference) / reference * 100.0;
    }
}
