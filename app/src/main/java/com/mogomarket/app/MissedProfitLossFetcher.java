package com.mogomarket.app;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Estimates the profit a closed trade "left on the table": the highest price reached during the
 * {@link #WINDOW_DAYS} days after the sale, compared with the sell price.
 */
public class MissedProfitLossFetcher {
    private static final long WINDOW_DAYS = 7;
    private static final OkHttpClient CLIENT = new OkHttpClient();

    public interface Callback {
        /**
         * @param maxHigh        highest price in the window, or null when there is no data
         * @param windowComplete false when the window is still open or the request failed
         *                       (the caller retries later)
         */
        void onFetched(Double maxHigh, boolean windowComplete);
    }

    public static double calculate(StockData trade, double maxHigh) {
        if (trade == null || maxHigh <= trade.sellPrice || trade.buyPrice <= 0) {
            return 0.0;
        }
        double shares = trade.shares > 0 ? trade.shares : (trade.tradeAmount / trade.buyPrice);
        return (maxHigh - trade.sellPrice) * shares;
    }

    public static void fetch(StockData trade, Callback callback) {
        if (callback == null) return;
        if (trade == null || trade.symbol == null || trade.closedAt <= 0) {
            callback.onFetched(null, true);
            return;
        }
        long windowEnd = trade.closedAt + WINDOW_DAYS * 24L * 3600L * 1000L;
        if (System.currentTimeMillis() < windowEnd) {
            callback.onFetched(null, false);
            return;
        }
        String symbol = trade.symbol.trim();
        if (symbol.contains(":")) { // crypto symbols are not covered by Yahoo here
            callback.onFetched(null, true);
            return;
        }
        String url = ApiConfig.YAHOO_CHART
                + URLEncoder.encode(symbol, StandardCharsets.UTF_8).replace("+", "%20")
                + "?interval=1d&range=1y&includePrePost=false";
        CLIENT.newCall(new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build())
                .enqueue(new okhttp3.Callback() {
                    @Override
                    public void onFailure(@NonNull Call call, @NonNull IOException e) {
                        callback.onFetched(null, false);
                    }

                    @Override
                    public void onResponse(@NonNull Call call, @NonNull Response response) {
                        try (Response r = response) {
                            if (!r.isSuccessful() || r.body() == null) {
                                callback.onFetched(null, false);
                                return;
                            }
                            JSONArray results = new JSONObject(r.body().string())
                                    .getJSONObject("chart").optJSONArray("result");
                            if (results == null || results.length() == 0) {
                                callback.onFetched(null, true);
                                return;
                            }
                            JSONObject item = results.getJSONObject(0);
                            JSONArray stamps = item.optJSONArray("timestamp");
                            JSONArray highs = item.getJSONObject("indicators")
                                    .getJSONArray("quote").getJSONObject(0).getJSONArray("high");
                            long fromSec = trade.closedAt / 1000L;
                            long toSec = windowEnd / 1000L;
                            Double maxHigh = null;
                            if (stamps != null) {
                                for (int i = 0; i < Math.min(stamps.length(), highs.length()); i++) {
                                    long ts = stamps.getLong(i);
                                    if (ts < fromSec || ts > toSec || highs.isNull(i)) continue;
                                    double high = highs.getDouble(i);
                                    if (maxHigh == null || high > maxHigh) maxHigh = high;
                                }
                            }
                            callback.onFetched(maxHigh, true);
                        } catch (Exception e) {
                            callback.onFetched(null, false);
                        }
                    }
                });
    }
}
