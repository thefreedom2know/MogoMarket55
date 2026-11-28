package com.mogomarket.app;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Backup source for stock quotes. When the "finnhub" server function is not deployed, has no key,
 * or fails, {@link FinnhubProxy} asks Yahoo Finance (free, no key) for the same quote and answers
 * in Finnhub's quote format ({@code c}, {@code pc}, {@code d}, {@code dp}, {@code o}, {@code h},
 * {@code l}), so screens that read Finnhub quotes keep working without changes.
 */
final class YahooQuoteFallback {

    private static final String TAG = "YahooQuoteFallback";

    // Plain client (no FinnhubProxy interceptor), only for Yahoo requests.
    private static final OkHttpClient YAHOO = new OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build();

    private YahooQuoteFallback() {}

    /**
     * Turns a Finnhub symbol into the matching Yahoo symbol, or null when there is none.
     * "OANDA:EUR_USD" becomes "EURUSD=X"; US share classes "BRK.B" become "BRK-B";
     * other symbols (including Yahoo suffixes such as "TEVA.TA") stay as they are.
     */
    @Nullable
    static String toYahooSymbol(@Nullable String finnhubSymbol) {
        if (finnhubSymbol == null) return null;
        String s = finnhubSymbol.trim().toUpperCase(Locale.US);
        if (s.isEmpty()) return null;
        if (s.startsWith("OANDA:")) {
            String pair = s.substring("OANDA:".length()).replace("_", "");
            return pair.length() == 6 ? pair + "=X" : null;
        }
        if (s.contains(":")) return null; // other exchange-prefixed Finnhub symbols
        int dot = s.lastIndexOf('.');
        if (dot > 0 && dot == s.length() - 2) {
            // One letter after the dot is a US share class (BRK.B); Yahoo writes it with a dash.
            return s.substring(0, dot) + "-" + s.substring(dot + 1);
        }
        return s;
    }

    /** Fetches the quote from Yahoo and returns it as Finnhub quote JSON, or null on failure. */
    @Nullable
    static String finnhubQuoteJson(@Nullable String finnhubSymbol) {
        String yahooSymbol = toYahooSymbol(finnhubSymbol);
        if (yahooSymbol == null) return null;
        String url = ApiConfig.YAHOO_CHART
                + URLEncoder.encode(yahooSymbol, StandardCharsets.UTF_8)
                + "?range=5d&interval=1d&includePrePost=false";
        Request request = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
        try (Response response = YAHOO.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) return null;
            JSONArray results = new JSONObject(response.body().string())
                    .getJSONObject("chart").optJSONArray("result");
            if (results == null || results.length() == 0) return null;
            JSONObject item = results.getJSONObject(0);
            YahooQuoteMath.Quote quote = YahooQuoteMath.fromChartItem(item);
            double c = quote.currentPrice;
            double pc = quote.previousClose;
            if (!(c > 0)) return null;

            JSONObject meta = item.optJSONObject("meta");
            double high = meta != null ? meta.optDouble("regularMarketDayHigh", c) : c;
            double low = meta != null ? meta.optDouble("regularMarketDayLow", c) : c;
            double open = lastValue(item, "open", c);

            JSONObject out = new JSONObject();
            out.put("c", c);
            out.put("pc", pc);
            out.put("d", pc > 0 ? c - pc : 0);
            out.put("dp", pc > 0 ? (c - pc) / pc * 100.0 : 0);
            out.put("o", open);
            out.put("h", Double.isNaN(high) ? c : high);
            out.put("l", Double.isNaN(low) ? c : low);
            out.put("t", meta != null ? meta.optLong("regularMarketTime", 0) : 0);
            out.put("source", "yahoo");
            return out.toString();
        } catch (Exception e) {
            Log.w(TAG, "Yahoo quote failed for " + yahooSymbol, e);
            return null;
        }
    }

    private static double lastValue(JSONObject item, String field, double fallback) {
        JSONObject indicators = item.optJSONObject("indicators");
        JSONArray quotes = indicators != null ? indicators.optJSONArray("quote") : null;
        JSONObject first = quotes != null ? quotes.optJSONObject(0) : null;
        JSONArray values = first != null ? first.optJSONArray(field) : null;
        if (values == null) return fallback;
        for (int i = values.length() - 1; i >= 0; i--) {
            if (values.isNull(i)) continue;
            double v = values.optDouble(i, Double.NaN);
            if (!Double.isNaN(v) && v > 0) return v;
        }
        return fallback;
    }
}
