package com.mogomarket.app;

import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class WatchlistAdapter extends RecyclerView.Adapter<WatchlistAdapter.ViewHolder> {

    private static final String FINNHUB_KEY = ApiConfig.finnhubKey();


    public interface OnWatchStockClickListener {
        void onStockClick(String symbol);
        void onStockDelete(String symbol);
        void onSetPriceAlert(StockWatchData stock);
        void onAlertStateChanged(String symbol, boolean triggered);
    }

    public interface OnManualOrderChangedListener {
        void onManualOrderChanged(List<String> symbols);
    }

    private final OnWatchStockClickListener listener;
    private final List<StockWatchData> fullList = new ArrayList<>();
    private final List<StockWatchData> displayList = new ArrayList<>();
    private final List<String> manualOrderSymbols = new ArrayList<>();
    private final OkHttpClient client = new OkHttpClient();

    private final Map<String, float[]> quoteCache = new HashMap<>();
    private final Set<String> loading = new HashSet<>();

    // Pre-market / after-hours quotes (free Yahoo chart API with includePrePost=true).
    private static final long EXTENDED_CACHE_TTL_MS = 60_000L;
    private static class ExtendedQuote {
        String label;      // "Pre" or "After"
        float price;
        float changePercent; // versus the last regular-session price
        long fetchedAt;
        java.util.List<Float> spark = new java.util.ArrayList<>();
    }
    private final Map<String, ExtendedQuote> extendedCache = new HashMap<>();
    private final Set<String> extendedLoading = new HashSet<>();
    private static final long QUOTE_CACHE_TTL_MS = 60_000L;

    private String currentSearch = "";
    private String currentFilter = "default";
    private static final int PERCENT_SORT_DEFAULT = 0;
    private static final int PERCENT_SORT_GAIN_FIRST = 1;
    private static final int PERCENT_SORT_LOSS_FIRST = 2;

    private boolean ascending = true;
    private int percentSortMode = PERCENT_SORT_DEFAULT;
    private OnManualOrderChangedListener manualOrderChangedListener;

    public WatchlistAdapter(OnWatchStockClickListener listener) {
        this.listener = listener;
    }

    public void setManualOrderChangedListener(OnManualOrderChangedListener listener) {
        manualOrderChangedListener = listener;
    }

    public void setManualOrder(List<String> symbols) {
        manualOrderSymbols.clear();
        if (symbols != null) {
            for (String symbol : symbols) {
                if (symbol != null && !symbol.trim().isEmpty()) {
                    manualOrderSymbols.add(symbol.trim());
                }
            }
        }
    }

    public List<String> getManualOrder() {
        return new ArrayList<>(manualOrderSymbols);
    }

    public void setPercentSortMode(int mode) {
        if (mode < PERCENT_SORT_DEFAULT || mode > PERCENT_SORT_LOSS_FIRST) return;
        percentSortMode = mode;
        applyFilters();
    }

    public void setItems(List<StockWatchData> items) {
        fullList.clear();
        if (items != null) {
            for (StockWatchData stock : items) {
                if (stock != null && stock.symbol != null && !stock.symbol.trim().isEmpty()) {
                    fullList.add(stock);
                }
            }
        }
        Map<String, Integer> savedPositions = new HashMap<>();
        for (int i = 0; i < manualOrderSymbols.size(); i++) {
            savedPositions.put(manualOrderSymbols.get(i).toUpperCase(Locale.US), i);
        }
        fullList.sort((left, right) -> {
            Integer savedLeftPosition = savedPositions.get(left.symbol.toUpperCase(Locale.US));
            Integer savedRightPosition = savedPositions.get(right.symbol.toUpperCase(Locale.US));
            int leftPosition = savedLeftPosition != null ? savedLeftPosition : Integer.MAX_VALUE;
            int rightPosition = savedRightPosition != null ? savedRightPosition : Integer.MAX_VALUE;
            return Integer.compare(leftPosition, rightPosition);
        });
        manualOrderSymbols.clear();
        for (StockWatchData stock : fullList) {
            manualOrderSymbols.add(stock.symbol);
        }
        if (manualOrderChangedListener != null) {
            manualOrderChangedListener.onManualOrderChanged(getManualOrder());
        }
        applyFilters();
    }

    public void setSearch(String query) {
        currentSearch = query == null ? "" : query.trim().toLowerCase(Locale.US);
        applyFilters();
    }

    public void setFilter(String filter) {
        currentFilter = filter == null ? "default" : filter;
        applyFilters();
    }

    public void cyclePercentSortOrder() {
        percentSortMode++;

        if (percentSortMode > PERCENT_SORT_LOSS_FIRST) {
            percentSortMode = PERCENT_SORT_DEFAULT;
        }

        applyFilters();
    }

    public int getPercentSortMode() {
        return percentSortMode;
    }

    public boolean isAscending() {
        return ascending;
    }

    public void refresh() {
        quoteCache.clear();
        loading.clear();
        extendedCache.clear();
        extendedLoading.clear();
        notifyDataSetChanged();
    }

    public void moveItem(int from, int to) {
        if (from < 0 || to < 0 || from >= displayList.size() || to >= displayList.size()) return;
        if (from == to) return;
        StockWatchData moved = displayList.remove(from);
        displayList.add(to, moved);

        Set<StockWatchData> visibleItems = new HashSet<>(displayList);
        int displayPosition = 0;
        for (int i = 0; i < fullList.size(); i++) {
            if (visibleItems.contains(fullList.get(i))) {
                fullList.set(i, displayList.get(displayPosition++));
            }
        }
        manualOrderSymbols.clear();
        for (StockWatchData stock : fullList) {
            manualOrderSymbols.add(stock.symbol);
        }
        notifyItemMoved(from, to);
        if (manualOrderChangedListener != null) {
            manualOrderChangedListener.onManualOrderChanged(getManualOrder());
        }
    }

    private void applyFilters() {
        displayList.clear();

        for (StockWatchData s : fullList) {
            if (s == null || s.symbol == null) continue;

            String symbolLower = s.symbol.toLowerCase(Locale.US);
            if (!currentSearch.isEmpty() && !symbolLower.contains(currentSearch)) continue;

            if ("gain".equals(currentFilter) && s.dayChange < 0f) continue;
            if ("loss".equals(currentFilter) && s.dayChange >= 0f) continue;

            displayList.add(s);
        }

        switch (currentFilter) {
            case "gain":
            case "loss":
                if (percentSortMode == PERCENT_SORT_GAIN_FIRST) {
                    Collections.sort(displayList, (a, b) ->
                            Float.compare(b.dayChange, a.dayChange));
                } else if (percentSortMode == PERCENT_SORT_LOSS_FIRST) {
                    Collections.sort(displayList, (a, b) ->
                            Float.compare(a.dayChange, b.dayChange));
                }
                break;

            case "alpha":
                Collections.sort(displayList, (a, b) ->
                        ascending
                                ? a.symbol.compareToIgnoreCase(b.symbol)
                                : b.symbol.compareToIgnoreCase(a.symbol));
                break;

            default:
                if (percentSortMode == PERCENT_SORT_GAIN_FIRST) {
                    Collections.sort(displayList, (a, b) ->
                            Float.compare(b.dayChange, a.dayChange));
                } else if (percentSortMode == PERCENT_SORT_LOSS_FIRST) {
                    Collections.sort(displayList, (a, b) ->
                            Float.compare(a.dayChange, b.dayChange));
                }
                break;
        }

        notifyDataSetChanged();
    }

    public String getCurrentFilter() {
        return currentFilter;
    }

    private boolean isCrypto(String symbol) {
        return symbol != null && symbol.contains(":");
    }

    private boolean isForex(String symbol) {
        if (symbol == null) return false;

        String upper = symbol.trim().toUpperCase(Locale.US);

        return upper.endsWith("=X")
                || upper.equals("GC=F")
                || upper.equals("SI=F")
                || upper.equals("BZ=F")
                || upper.equals("CL=F")
                || ChartFragment.FOREX_MAP.containsKey(upper)
                || ChartFragment.FOREX_MAP.containsValue(upper);
    }

    private String normalizeUserSymbol(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .toUpperCase(Locale.US)
                .replace(" ", "")
                .replace("/", "")
                .replace("-", "")
                .replace("_", "");
    }

    private String mapSymbolForQuote(String raw) {
        String normalized = normalizeUserSymbol(raw);
        if (normalized.isEmpty()) return "";

        String crypto = ChartFragment.CRYPTO_MAP.get(normalized);
        if (crypto != null) return crypto;

        String forex = ChartFragment.FOREX_MAP.get(normalized);
        if (forex != null) return forex;
        if ("SPX".equals(normalized) || "^SPX".equals(normalized)
                || "GSPC".equals(normalized)) return "^GSPC";

        return normalized;
    }

    private String formatPrice(String symbol, float price) {
        return String.format(Locale.US, isForex(symbol) ? "$%.4f" : "$%.2f", price);
    }

    private String displaySymbol(String symbol) {
        if (symbol == null) return "";
        if (isCrypto(symbol)) return symbol.substring(symbol.indexOf(':') + 1);
        if ("^VIX".equals(symbol)) return "VIX";
        if ("CL=F".equals(symbol)) return "OIL";
        if ("BZ=F".equals(symbol)) return "BRENT";
        return symbol.replace("=X", "").replace("=F", "");
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_watchlist_stock, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        StockWatchData stock = displayList.get(position);
        Context ctx = holder.itemView.getContext();

        holder.itemView.setTag(stock.symbol);

        int textPrimary = ctx.getColor(R.color.text_primary);
        int textSecondary = ctx.getColor(R.color.text_secondary);
        int colorGain = ctx.getColor(R.color.gain);
        int colorLoss = ctx.getColor(R.color.loss);
        int colorPrimary = ctx.getColor(R.color.primary);

        holder.symbolText.setText(displaySymbol(stock.symbol));
        holder.symbolText.setTextColor(textPrimary);
        holder.badgeText.setText(displaySymbol(stock.symbol));

        float[] cached = quoteCache.get(stock.symbol);
        if (cached != null && cached.length >= 3
                && System.currentTimeMillis() - cached[2] < QUOTE_CACHE_TTL_MS) {
            stock.currentPrice = cached[0];
            stock.dayChange = cached[1];
            holder.priceText.setText(formatPrice(stock.symbol, cached[0]));
            holder.priceText.setTextColor(colorPrimary);
            bindChange(holder.dayChangeText, cached[1], colorGain, colorLoss);
        } else {
            quoteCache.remove(stock.symbol);
            holder.priceText.setText("...");
            holder.priceText.setTextColor(textSecondary);
            holder.dayChangeText.setText("");

            if (!loading.contains(stock.symbol)) {
                loading.add(stock.symbol);
                final String targetSymbol = stock.symbol;
                fetchQuote(stock, holder, targetSymbol, colorPrimary, textSecondary, colorGain, colorLoss, ctx);
            }
        }

        bindExtendedHours(stock, holder, colorGain, colorLoss);

        if (stock.alertEnabled && stock.alertTargetPrice > 0) {
            holder.alertText.setText(String.format(
                    Locale.US,
                    isForex(stock.symbol) ? "Alert %s $%.4f" : "Alert %s $%.2f",
                    "BELOW".equals(stock.alertDirection) ? "\u2193" : "\u2191",
                    stock.alertTargetPrice
            ));
            holder.alertText.setTextColor(colorPrimary);
        } else {
            holder.alertText.setText("No alert");
            holder.alertText.setTextColor(textSecondary);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onStockClick(stock.symbol);
        });

        if (holder.btnDelete != null) {
            holder.btnDelete.setOnClickListener(v -> {
                if (listener != null) listener.onStockDelete(stock.symbol);
            });
        }

        holder.alertText.setOnClickListener(v -> {
            if (listener != null) listener.onSetPriceAlert(stock);
        });
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    private boolean supportsExtendedHours(String symbol) {
        if (symbol == null || symbol.trim().isEmpty()) return false;
        String s = symbol.trim().toUpperCase(Locale.US);
        return !isCrypto(s) && !isForex(s) && !s.startsWith("^") && !s.endsWith("=F")
                && !s.endsWith("=X");
    }

    private void showSparkline(ViewHolder holder, StockWatchData stock, ExtendedQuote q,
                               int colorGain, int colorLoss) {
        if (holder.sparkline == null || q == null || q.spark.size() < 2) return;
        // Green when the stock is up today, red when it is down today.
        holder.sparkline.setData(q.spark, stock.dayChange >= 0f ? colorGain : colorLoss);
    }

    private void showExtended(ViewHolder holder, StockWatchData stock, ExtendedQuote q,
                              int colorGain, int colorLoss) {
        showSparkline(holder, stock, q, colorGain, colorLoss);
        if (q == null) {
            holder.extendedText.setVisibility(View.GONE);
            return;
        }
        String sign = q.changePercent >= 0 ? "+" : "-";
        holder.extendedText.setText(String.format(Locale.US, "%s $%.2f  %s%.2f%%",
                q.label, q.price, sign, Math.abs(q.changePercent)));
        holder.extendedText.setTextColor(q.changePercent >= 0 ? colorGain : colorLoss);
        holder.extendedText.setVisibility(View.VISIBLE);
    }

    private void bindExtendedHours(StockWatchData stock, ViewHolder holder,
                                   int colorGain, int colorLoss) {
        holder.extendedText.setVisibility(View.GONE);
        if (holder.sparkline != null) holder.sparkline.setData(null, colorGain); // clear recycled row
        if (stock.symbol == null || stock.symbol.trim().isEmpty()) return;
        final boolean extendedSupported = supportsExtendedHours(stock.symbol);
        ExtendedQuote cached = extendedCache.get(stock.symbol);
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt < EXTENDED_CACHE_TTL_MS) {
            showExtended(holder, stock, cached.label == null ? null : cached, colorGain, colorLoss);
            if (cached.label == null) showSparkline(holder, stock, cached, colorGain, colorLoss);
            return;
        }
        if (extendedLoading.contains(stock.symbol)) return;
        extendedLoading.add(stock.symbol);
        final String symbol = stock.symbol;
        String encoded = URLEncoder.encode(mapSymbolForQuote(symbol).trim(), StandardCharsets.UTF_8)
                .replace("+", "%20");
        final boolean crypto = isCrypto(symbol);
        String url = crypto
                ? ApiConfig.BINANCE_KLINES
                        + mapSymbolForQuote(symbol).substring(mapSymbolForQuote(symbol).indexOf(':') + 1)
                        + "&interval=15m&limit=96"
                : ApiConfig.YAHOO_CHART + encoded
                        + "?interval=" + (extendedSupported ? "1m" : "5m")
                        + "&range=1d&includePrePost=" + extendedSupported;
        Request request = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                extendedLoading.remove(symbol);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                extendedLoading.remove(symbol);
                if (response.body() == null) return;
                String body = response.body().string();
                if (!response.isSuccessful()) return;
                ExtendedQuote quote = new ExtendedQuote();
                quote.fetchedAt = System.currentTimeMillis();
                try {
                  if (crypto) {
                    JSONArray klines = new JSONArray(body);
                    for (int i = 0; i < klines.length(); i++) {
                        quote.spark.add((float) Double.parseDouble(klines.getJSONArray(i).getString(4)));
                    }
                  } else {
                    JSONArray result = new JSONObject(body).getJSONObject("chart").optJSONArray("result");
                    if (result != null && result.length() > 0) {
                        JSONObject item = result.getJSONObject(0);
                        JSONObject meta = item.getJSONObject("meta");
                        double regularPrice = meta.optDouble("regularMarketPrice", 0);
                        JSONObject period = meta.getJSONObject("currentTradingPeriod");
                        long regularStart = period.getJSONObject("regular").getLong("start");
                        long regularEnd = period.getJSONObject("regular").getLong("end");
                        JSONArray stamps = item.optJSONArray("timestamp");
                        JSONArray closes = item.getJSONObject("indicators")
                                .getJSONArray("quote").getJSONObject(0).getJSONArray("close");
                        java.util.List<Float> all = new java.util.ArrayList<>();
                        for (int i = 0; i < closes.length(); i++) {
                            if (!closes.isNull(i)) all.add((float) closes.getDouble(i));
                        }
                        int step = Math.max(1, all.size() / 40);
                        for (int i = 0; i < all.size(); i += step) quote.spark.add(all.get(i));
                        if (!all.isEmpty() && (all.size() - 1) % step != 0) quote.spark.add(all.get(all.size() - 1));
                        if (extendedSupported && stamps != null && regularPrice > 0) {
                            for (int i = Math.min(stamps.length(), closes.length()) - 1; i >= 0; i--) {
                                if (closes.isNull(i)) continue;
                                long ts = stamps.getLong(i);
                                double price = closes.getDouble(i);
                                boolean fresh = quote.fetchedAt / 1000L - ts < 20 * 3600L;
                                if (fresh && price > 0 && ts < regularStart) quote.label = "Pre";
                                else if (fresh && price > 0 && ts >= regularEnd) quote.label = "After";
                                if (quote.label != null) {
                                    quote.price = (float) price;
                                    quote.changePercent =
                                            (float) ((price - regularPrice) / regularPrice * 100.0);
                                }
                                break; // only the most recent bar matters
                            }
                        }
                    }
                  }
                } catch (Exception ignored) {
                    // No extended-hours data: the row simply shows nothing extra.
                }
                extendedCache.put(symbol, quote);
                holder.extendedText.post(() -> {
                    if (!symbol.equals(holder.itemView.getTag())) return;
                    showExtended(holder, stock, quote.label == null ? null : quote, colorGain, colorLoss);
                    if (quote.label == null) showSparkline(holder, stock, quote, colorGain, colorLoss);
                });
            }
        });
    }

    private void fetchQuote(StockWatchData stock,
                            ViewHolder holder,
                            String targetSymbol,
                            int colorPrimary,
                            int textSecondary,
                            int colorGain,
                            int colorLoss,
                            Context ctx) {

        final String requestSymbol = mapSymbolForQuote(stock.symbol);

        if (isCrypto(requestSymbol)) {
            final String pair = requestSymbol.contains(":")
                    ? requestSymbol.substring(requestSymbol.indexOf(':') + 1)
                    : requestSymbol;

            String url = ApiConfig.BINANCE_KLINES + pair + "&interval=1d&limit=2";

            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    loading.remove(stock.symbol);
                    if (!targetSymbol.equals(holder.itemView.getTag())) return;
                    showDash(holder, textSecondary);
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    loading.remove(stock.symbol);

                    if (response.body() == null) {
                        if (!targetSymbol.equals(holder.itemView.getTag())) return;
                        showDash(holder, textSecondary);
                        return;
                    }

                    String body = response.body().string();

                    if (!response.isSuccessful()) {
                        if (!targetSymbol.equals(holder.itemView.getTag())) return;
                        showDash(holder, textSecondary);
                        return;
                    }

                    try {
                        JSONArray arr = new JSONArray(body);

                        if (arr.length() < 2) {
                            if (targetSymbol.equals(holder.itemView.getTag())) {
                                showDash(holder, textSecondary);
                            }
                            return;
                        }

                        JSONArray prevBar = arr.getJSONArray(arr.length() - 2);
                        JSONArray lastBar = arr.getJSONArray(arr.length() - 1);

                        float prevClose = Float.parseFloat(prevBar.getString(4));
                        float lastClose = Float.parseFloat(lastBar.getString(4));

                        if (lastClose <= 0f) {
                            if (targetSymbol.equals(holder.itemView.getTag())) {
                                showDash(holder, textSecondary);
                            }
                            return;
                        }

                        float computedDayChange = prevClose > 0f
                                ? ((lastClose - prevClose) / prevClose) * 100f
                                : 0f;

                        final float finalPrice = lastClose;
                        final float finalDayChange = computedDayChange;

                        quoteCache.put(stock.symbol, new float[]{finalPrice, finalDayChange,
                                System.currentTimeMillis()});
                        stock.currentPrice = finalPrice;
                        stock.dayChange = finalDayChange;

                        holder.priceText.post(() -> {
                            if (!targetSymbol.equals(holder.itemView.getTag())) return;
                            holder.priceText.setText(formatPrice(stock.symbol, finalPrice));
                            holder.priceText.setTextColor(colorPrimary);
                        });

                        holder.dayChangeText.post(() -> {
                            if (!targetSymbol.equals(holder.itemView.getTag())) return;
                            bindChange(holder.dayChangeText, finalDayChange, colorGain, colorLoss);
                        });

                        processAlert(stock, finalPrice, ctx);

                    } catch (Exception e) {
                        if (targetSymbol.equals(holder.itemView.getTag())) {
                            showDash(holder, textSecondary);
                        }
                    }
                }
            });

            return;
        }

        // Finnhub needs an API key; without one fall back to the free Yahoo chart API below.
        if (FINNHUB_KEY != null && !FINNHUB_KEY.trim().isEmpty()
                && !isForex(requestSymbol) && !requestSymbol.endsWith("=F")
                && !requestSymbol.startsWith("^")) {
            fetchFinnhubQuote(stock, holder, targetSymbol, requestSymbol, colorPrimary,
                    textSecondary, colorGain, colorLoss, ctx);
            return;
        }

        String encodedSymbol = URLEncoder.encode(requestSymbol, StandardCharsets.UTF_8)
                .replace("+", "%20");
        String url = ApiConfig.YAHOO_CHART + encodedSymbol
                + "?interval=1d&range=5d&includePrePost=false";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0")
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                loading.remove(stock.symbol);
                if (!targetSymbol.equals(holder.itemView.getTag())) return;
                showDash(holder, textSecondary);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                loading.remove(stock.symbol);

                if (response.body() == null) {
                    if (!targetSymbol.equals(holder.itemView.getTag())) return;
                    showDash(holder, textSecondary);
                    return;
                }

                String body = response.body().string();

                if (!response.isSuccessful()) {
                    if (!targetSymbol.equals(holder.itemView.getTag())) return;
                    showDash(holder, textSecondary);
                    return;
                }

                try {
                    JSONObject root = new JSONObject(body);
                    JSONArray result = root.getJSONObject("chart").optJSONArray("result");

                    if (result == null || result.length() == 0) {
                        if (targetSymbol.equals(holder.itemView.getTag())) {
                            showDash(holder, textSecondary);
                        }
                        return;
                    }

                    JSONObject item = result.getJSONObject(0);
                    JSONObject indicators = item.getJSONObject("indicators");
                    JSONArray quoteArray = indicators.getJSONArray("quote");

                    if (quoteArray.length() == 0) {
                        if (targetSymbol.equals(holder.itemView.getTag())) {
                            showDash(holder, textSecondary);
                        }
                        return;
                    }

                    JSONObject quote = quoteArray.getJSONObject(0);
                    JSONArray closes = quote.getJSONArray("close");

                    float tempLastClose = 0f;
                    float tempPrevClose = 0f;

                    for (int i = closes.length() - 1; i >= 0; i--) {
                        if (!closes.isNull(i)) {
                            tempLastClose = (float) closes.getDouble(i);

                            for (int j = i - 1; j >= 0; j--) {
                                if (!closes.isNull(j)) {
                                    tempPrevClose = (float) closes.getDouble(j);
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    if (tempLastClose <= 0f) {
                        if (targetSymbol.equals(holder.itemView.getTag())) {
                            showDash(holder, textSecondary);
                        }
                        return;
                    }

                    YahooQuoteMath.Quote marketQuote = YahooQuoteMath.fromChartItem(item);
                    tempLastClose = (float) marketQuote.currentPrice;
                    float computedDayChange = (float) marketQuote.percentChange();
                    if (!Float.isFinite(computedDayChange)) {
                        if (targetSymbol.equals(holder.itemView.getTag())) {
                            showDash(holder, textSecondary);
                        }
                        return;
                    }

                    final float finalLastClose = tempLastClose;
                    final float finalDayChange = computedDayChange;

                    quoteCache.put(stock.symbol, new float[]{finalLastClose, finalDayChange,
                            System.currentTimeMillis()});
                    stock.currentPrice = finalLastClose;
                    stock.dayChange = finalDayChange;

                    holder.priceText.post(() -> {
                        if (!targetSymbol.equals(holder.itemView.getTag())) return;
                        holder.priceText.setText(formatPrice(stock.symbol, finalLastClose));
                        holder.priceText.setTextColor(colorPrimary);
                    });

                    holder.dayChangeText.post(() -> {
                        if (!targetSymbol.equals(holder.itemView.getTag())) return;
                        bindChange(holder.dayChangeText, finalDayChange, colorGain, colorLoss);
                    });

                    processAlert(stock, finalLastClose, ctx);

                } catch (Exception e) {
                    if (targetSymbol.equals(holder.itemView.getTag())) {
                        showDash(holder, textSecondary);
                    }
                }
            }
        });
    }

    private void fetchFinnhubQuote(StockWatchData stock, ViewHolder holder,
                                   String targetSymbol, String symbol, int colorPrimary,
                                   int textSecondary, int colorGain, int colorLoss,
                                   Context context) {
        if (FINNHUB_KEY == null || FINNHUB_KEY.trim().isEmpty()) {
            showDash(holder, textSecondary);
            Log.w("WatchlistAdapter", "Finnhub quote key is missing for " + symbol);
            return;
        }
        String encodedSymbol = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        String encodedToken = URLEncoder.encode(FINNHUB_KEY, StandardCharsets.UTF_8);
        String url = ApiConfig.FINNHUB_QUOTE + encodedSymbol
                + "&token=" + encodedToken;
        client.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException error) {
                loading.remove(stock.symbol);
                Log.w("WatchlistAdapter", "Finnhub quote failed for " + symbol, error);
                if (targetSymbol.equals(holder.itemView.getTag())) showDash(holder, textSecondary);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response)
                    throws IOException {
                loading.remove(stock.symbol);
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        Log.w("WatchlistAdapter", "Finnhub quote returned HTTP "
                                + response.code() + " for " + symbol);
                        if (targetSymbol.equals(holder.itemView.getTag())) {
                            showDash(holder, textSecondary);
                        }
                        return;
                    }
                    JSONObject quote = new JSONObject(response.body().string());
                    float price = (float) quote.getDouble("c");
                    float previousClose = (float) quote.getDouble("pc");
                    float change = (float) DailyQuoteMath.percentChange(price, previousClose);
                    if (!Float.isFinite(price) || price <= 0 || !Float.isFinite(change)) {
                        if (targetSymbol.equals(holder.itemView.getTag())) {
                            showDash(holder, textSecondary);
                        }
                        return;
                    }
                    stock.currentPrice = price;
                    stock.dayChange = change;
                    quoteCache.put(stock.symbol, new float[]{price, change,
                            System.currentTimeMillis()});
                    processAlert(stock, price, context);
                    holder.itemView.post(() -> {
                        if (!targetSymbol.equals(holder.itemView.getTag())) return;
                        holder.priceText.setText(formatPrice(stock.symbol, price));
                        holder.priceText.setTextColor(colorPrimary);
                        bindChange(holder.dayChangeText, change, colorGain, colorLoss);
                    });
                } catch (Exception error) {
                    Log.w("WatchlistAdapter", "Could not parse quote for " + symbol, error);
                    if (targetSymbol.equals(holder.itemView.getTag())) {
                        showDash(holder, textSecondary);
                    }
                } finally {
                    response.close();
                }
            }
        });
    }

    private void showDash(ViewHolder holder, int textSecondary) {
        holder.priceText.post(() -> {
            holder.priceText.setText("$—");
            holder.priceText.setTextColor(textSecondary);
        });

        holder.dayChangeText.post(() -> {
            holder.dayChangeText.setText("—");
            holder.dayChangeText.setTextColor(textSecondary);
        });
    }

    private void bindChange(TextView tv, float change, int colorGain, int colorLoss) {
        if (tv.getParent() instanceof View) {
            WatchlistSparklineView spark =
                    ((View) tv.getParent()).findViewById(R.id.watchlistSparkline);
            if (spark != null) spark.setColor(change >= 0 ? colorGain : colorLoss);
        }
        if (change >= 0) {
            tv.setText(String.format(Locale.US, "▲ +%.2f%%", change));
            tv.setTextColor(colorGain);
        } else {
            tv.setText(String.format(Locale.US, "▼ %.2f%%", change));
            tv.setTextColor(colorLoss);
        }
    }

    private void processAlert(StockWatchData stock, float price, Context ctx) {
        if (!ctx.getSharedPreferences(PriceAlertScheduler.PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PriceAlertScheduler.KEY_ENABLED, false)) return;
        if (!stock.alertEnabled || stock.alertTargetPrice <= 0 || stock.alertTriggered) return;
        if (!PriceAlertReceiver.reached(stock.alertDirection, stock.alertTargetPrice, price)) return;

        String symbol = stock.symbol;
        String safeKey = symbol.replace(":", "_");
        FirebaseAuth auth = FirebaseAuth.getInstance();
        if (auth.getCurrentUser() == null) return;
        DatabaseReference stockRef = FirebaseDatabase.getInstance()
                .getReference("users/" + auth.getCurrentUser().getUid()
                        + "/watchlist-stocks/" + safeKey);
        stockRef.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData currentData) {
                StockWatchData latest = currentData.getValue(StockWatchData.class);
                if (latest == null || !latest.alertEnabled || latest.alertTriggered
                        || latest.alertTargetPrice <= 0
                        || !PriceAlertReceiver.reached(latest.alertDirection,
                                latest.alertTargetPrice, price)) {
                    return Transaction.abort();
                }
                latest.alertTriggered = true;
                latest.alertEnabled = false;
                currentData.setValue(latest);
                return Transaction.success(currentData);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed,
                                   @Nullable com.google.firebase.database.DataSnapshot currentData) {
                if (error != null) {
                    Log.e("WatchlistAdapter", "Failed to update triggered price alert", error.toException());
                    return;
                }
                if (!committed) return;

                stock.alertTriggered = true;
                stock.alertEnabled = false;
                int position = displayList.indexOf(stock);
                if (position >= 0) notifyItemChanged(position);
                if (listener != null) listener.onAlertStateChanged(symbol, true);
                StockWatchData triggered = currentData != null
                        ? currentData.getValue(StockWatchData.class) : null;
                float target = triggered != null ? triggered.alertTargetPrice : stock.alertTargetPrice;
                postPriceAlertNotification(ctx, symbol, target, price,
                        triggered != null ? triggered.alertDirection : stock.alertDirection);
            }
        });
    }

    private void postPriceAlertNotification(Context ctx, String symbol, float target, float price,
                                            @Nullable String direction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            android.app.NotificationChannel ch = new android.app.NotificationChannel(
                    PriceAlertReceiver.CHANNEL_ID,
                    "Price Alerts",
                    android.app.NotificationManager.IMPORTANCE_HIGH
            );
            android.app.NotificationManager nm =
                    (android.app.NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        String displaySym = displaySymbol(symbol);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, PriceAlertReceiver.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("📈 Alert: " + displaySym)
                .setContentText(String.format(
                        Locale.US,
                        isForex(symbol)
                                ? ("BELOW".equals(direction) ? "%s fell to $%.4f, current: $%.4f"
                                        : "%s reached $%.4f, current: $%.4f")
                                : ("BELOW".equals(direction) ? "%s fell to $%.2f, current: $%.2f"
                                        : "%s reached $%.2f, current: $%.2f"),
                        displaySym,
                        target,
                        price
                ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        try {
            NotificationManagerCompat.from(ctx).notify(symbol.hashCode(), builder.build());
        } catch (SecurityException e) {
            Log.w("WatchlistAdapter", "Notification permission is unavailable", e);
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView symbolText, badgeText, priceText, dayChangeText, alertText;
        TextView extendedText;
        WatchlistSparklineView sparkline;
        ImageButton btnDelete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            symbolText = itemView.findViewById(R.id.stockSymbolText);
            badgeText = itemView.findViewById(R.id.stockBadgeText);
            priceText = itemView.findViewById(R.id.stockPriceText);
            dayChangeText = itemView.findViewById(R.id.stockDayChangeText);
            alertText = itemView.findViewById(R.id.stockAlertText);
            extendedText = itemView.findViewById(R.id.stockExtendedText);
            sparkline = itemView.findViewById(R.id.watchlistSparkline);
            btnDelete = itemView.findViewById(R.id.btnDeleteStock);
        }
    }
}