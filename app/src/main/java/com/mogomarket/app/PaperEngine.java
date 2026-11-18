package com.mogomarket.app;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.MutableData;
import com.google.firebase.database.Transaction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Engine of the paper-trading simulator: live quotes (Yahoo, no key needed) and every
 * state change of the virtual account, always done inside a Firebase transaction.
 *
 * Data layout under users/{uid}/paper-trading (everything is stored as plain maps, so no
 * model classes / R8 rules are needed):
 *   cash, startingCash, fee
 *   positions/{key}: symbol, shares, averagePrice, lastPrice, tp, sl, openedAt
 *   orders/{pushKey}: symbol, side, type (LIMIT|STOP), shares, price, tp, sl, createdAt
 *   trades/{pushKey}: symbol, side, shares, price, notional, realizedPnl, executedAt,
 *                     fee, type, note
 */
final class PaperEngine {

    static final double DEFAULT_CASH = 100_000.0;
    static final int MAX_TRADES = 500;

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final ExecutorService COORDINATOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private PaperEngine() { }

    // ------------------------------------------------------------------------------------
    // Quotes
    // ------------------------------------------------------------------------------------

    static final class Quote {
        final String symbol;
        final String name;
        final double price;
        final double prevClose;

        Quote(String symbol, String name, double price, double prevClose) {
            this.symbol = symbol;
            this.name = name;
            this.price = price;
            this.prevClose = prevClose;
        }

        double change() {
            return prevClose > 0 ? price - prevClose : 0;
        }

        double changePct() {
            return prevClose > 0 ? (price - prevClose) / prevClose * 100.0 : 0;
        }
    }

    interface QuoteCallback {
        void onResult(@Nullable Quote quote, @Nullable String error);
    }

    interface QuotesCallback {
        void onQuotes(Map<String, Quote> quotes);
    }

    interface Result {
        void onDone(boolean ok, String message);
    }

    /** Symbol as the user types it -> symbol Yahoo understands. */
    static String yahooSymbol(String raw) {
        String s = raw.trim().toUpperCase(Locale.US);
        if (s.equals("SPX") || s.equals("^SPX") || s.equals("GSPC")) return "^GSPC";
        String crypto = ChartFragment.CRYPTO_MAP.get(s);
        if (crypto != null) {
            String base = crypto.replace("BINANCE:", "");
            if (base.endsWith("USDT")) base = base.substring(0, base.length() - 4);
            return base + "-USD";
        }
        String forex = ChartFragment.FOREX_MAP.get(s);
        if (forex != null) return forex;
        return s;
    }

    static Quote fetchBlocking(String symbol) throws IOException {
        String url = ApiConfig.YAHOO_CHART
                + URLEncoder.encode(yahooSymbol(symbol), "UTF-8")
                + "?range=1d&interval=1m";
        Request request = new Request.Builder().url(url).header("User-Agent", UA).build();
        try (Response response = HTTP.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("No quote for " + symbol + " (HTTP " + response.code() + ")");
            }
            String body = response.body().string();
            JSONObject chart = new JSONObject(body).getJSONObject("chart");
            JSONArray results = chart.optJSONArray("result");
            if (results == null || results.length() == 0) {
                throw new IOException("No quote for " + symbol);
            }
            JSONObject meta = results.getJSONObject(0).optJSONObject("meta");
            if (meta == null) throw new IOException("No quote for " + symbol);
            double price = meta.optDouble("regularMarketPrice", 0);
            double prev = meta.optDouble("chartPreviousClose", 0);
            if (!(prev > 0)) prev = meta.optDouble("previousClose", 0);
            if (!Double.isFinite(price) || price <= 0) {
                throw new IOException("Invalid price for " + symbol);
            }
            String name = meta.optString("shortName", "");
            if (name.isEmpty()) name = meta.optString("longName", "");
            if (name.isEmpty()) name = symbol;
            return new Quote(symbol, name, price, Double.isFinite(prev) ? prev : 0);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not read the quote for " + symbol, e);
        }
    }

    static void fetchOne(String symbol, QuoteCallback callback) {
        POOL.submit(() -> {
            Quote quote = null;
            String error = null;
            try {
                quote = fetchBlocking(symbol);
            } catch (Exception e) {
                error = e.getMessage() == null ? "Quote failed" : e.getMessage();
            }
            final Quote q = quote;
            final String err = error;
            MAIN.post(() -> callback.onResult(q, err));
        });
    }

    static void fetchMany(Collection<String> symbols, QuotesCallback callback) {
        final List<String> list = new ArrayList<>(symbols);
        COORDINATOR.submit(() -> {
            Map<String, Quote> out = new HashMap<>();
            List<Callable<Quote>> jobs = new ArrayList<>();
            for (String symbol : list) {
                jobs.add(() -> {
                    try {
                        return fetchBlocking(symbol);
                    } catch (Exception e) {
                        return null;
                    }
                });
            }
            try {
                List<Future<Quote>> futures = POOL.invokeAll(jobs, 20, TimeUnit.SECONDS);
                for (Future<Quote> f : futures) {
                    try {
                        Quote q = f.get();
                        if (q != null) out.put(q.symbol, q);
                    } catch (Exception ignored) {
                        // that symbol simply has no fresh quote
                    }
                }
            } catch (InterruptedException ignored) {
                // fall through with whatever we have
            }
            MAIN.post(() -> callback.onQuotes(out));
        });
    }

    // ------------------------------------------------------------------------------------
    // Account transactions
    // ------------------------------------------------------------------------------------

    static String positionKey(String symbol) {
        return symbol.toUpperCase(Locale.US).replaceAll("[^A-Z0-9_-]", "_");
    }

    private static double num(MutableData data, String key, double def) {
        Double v = data.child(key).getValue(Double.class);
        return v == null ? def : v;
    }

    /** Applies one fill to the account. Returns null on success, or an error text. */
    private static String applyFill(MutableData account, String tradeKey, String symbol,
                                    String side, double shares, double price, long ts,
                                    String type, String note, double tp, double sl) {
        if (!Double.isFinite(shares) || shares <= 0 || !Double.isFinite(price) || price <= 0) {
            return "Invalid order size or price";
        }
        double notional = shares * price;
        if (!Double.isFinite(notional)) return "Order is outside the supported range";

        double cash = num(account, "cash", DEFAULT_CASH);
        double fee = Math.max(0, num(account, "fee", 0));
        MutableData pos = account.child("positions").child(positionKey(symbol));
        double held = num(pos, "shares", 0);
        double avg = num(pos, "averagePrice", 0);
        double realized = 0;

        if ("BUY".equals(side)) {
            if (cash + 0.000001 < notional + fee) {
                return "Not enough virtual cash (need " + String.format(Locale.US, "$%,.2f",
                        notional + fee) + ")";
            }
            double total = held + shares;
            double newAvg = (held * avg + notional) / total;
            pos.child("symbol").setValue(symbol);
            pos.child("shares").setValue(total);
            pos.child("averagePrice").setValue(newAvg);
            pos.child("lastPrice").setValue(price);
            if (held <= 0) pos.child("openedAt").setValue(ts);
            if (tp > 0) pos.child("tp").setValue(tp);
            if (sl > 0) pos.child("sl").setValue(sl);
            account.child("cash").setValue(cash - notional - fee);
        } else {
            if (held <= 0 || held + 0.000000001 < shares) {
                return "You do not own enough " + symbol + " to sell";
            }
            realized = (price - avg) * shares - fee;
            double left = held - shares;
            if (left <= 0.000000001) {
                pos.setValue(null);
            } else {
                pos.child("shares").setValue(left);
                pos.child("lastPrice").setValue(price);
            }
            account.child("cash").setValue(cash + notional - fee);
        }

        Map<String, Object> trade = new HashMap<>();
        trade.put("symbol", symbol);
        trade.put("action", side);
        trade.put("side", side);
        trade.put("shares", shares);
        trade.put("price", price);
        trade.put("notional", notional);
        trade.put("realizedPnl", realized);
        trade.put("executedAt", ts);
        trade.put("fee", fee);
        trade.put("type", type == null ? "MARKET" : type);
        if (note != null) trade.put("note", note);
        MutableData trades = account.child("trades");
        trades.child(tradeKey).setValue(trade);
        while (trades.getChildrenCount() > MAX_TRADES) {
            MutableData oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (MutableData item : trades.getChildren()) {
                Long t = item.child("executedAt").getValue(Long.class);
                if (t != null && t < oldestTime) {
                    oldest = item;
                    oldestTime = t;
                }
            }
            if (oldest == null) break;
            oldest.setValue(null);
        }
        return null;
    }

    /**
     * Executes a fill. orderKey (optional) is a pending order consumed by this fill;
     * exitCheck (optional, "TP" or "SL") re-validates the position's exit level.
     */
    static void trade(DatabaseReference acct, String symbol, String side, double shares,
                      double price, String type, @Nullable String note, double tp, double sl,
                      @Nullable String orderKey, @Nullable String exitCheck, Result callback) {
        final String tradeKey = acct.child("trades").push().getKey();
        if (tradeKey == null) {
            callback.onDone(false, "Could not create the trade record");
            return;
        }
        final long ts = System.currentTimeMillis();
        acct.runTransaction(new Transaction.Handler() {
            private String failure = "Trade could not be completed";

            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData account) {
                if (orderKey != null
                        && account.child("orders").child(orderKey).getValue() == null) {
                    failure = "Order is no longer pending";
                    return Transaction.abort();
                }
                if (exitCheck != null) {
                    MutableData p = account.child("positions").child(positionKey(symbol));
                    double t = num(p, "tp", 0);
                    double s = num(p, "sl", 0);
                    boolean hit = ("TP".equals(exitCheck) && t > 0 && price >= t)
                            || ("SL".equals(exitCheck) && s > 0 && price <= s);
                    if (!hit) {
                        failure = "Exit level is no longer active";
                        return Transaction.abort();
                    }
                }
                String error = applyFill(account, tradeKey, symbol, side, shares, price, ts,
                        type, note, tp, sl);
                if (error != null) {
                    failure = error;
                    return Transaction.abort();
                }
                if (orderKey != null) account.child("orders").child(orderKey).setValue(null);
                return Transaction.success(account);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed,
                                   @Nullable DataSnapshot snapshot) {
                if (error != null) {
                    callback.onDone(false, "Trade failed: " + error.getMessage());
                } else if (committed) {
                    callback.onDone(true, String.format(Locale.US, "%s %s %s @ $%,.2f",
                            "BUY".equals(side) ? "Bought" : "Sold", fmtShares(shares), symbol,
                            price));
                } else {
                    if (orderKey != null && !failure.startsWith("Order is no longer")) {
                        acct.child("orders").child(orderKey).removeValue();
                        callback.onDone(false, "Order cancelled: " + failure);
                    } else {
                        callback.onDone(false, failure);
                    }
                }
            }
        });
    }

    static String fmtShares(double shares) {
        if (Math.abs(shares - Math.rint(shares)) < 0.0000005) {
            return String.format(Locale.US, "%,.0f", shares);
        }
        String s = String.format(Locale.US, "%,.6f", shares);
        s = s.replaceAll("0+$", "");
        if (s.endsWith(".")) s = s.substring(0, s.length() - 1);
        return s;
    }

    static void placeOrder(DatabaseReference acct, String symbol, String side, String type,
                           double shares, double price, double tp, double sl, Result callback) {
        DatabaseReference ref = acct.child("orders").push();
        Map<String, Object> order = new HashMap<>();
        order.put("symbol", symbol);
        order.put("side", side);
        order.put("type", type);
        order.put("shares", shares);
        order.put("price", price);
        if (tp > 0) order.put("tp", tp);
        if (sl > 0) order.put("sl", sl);
        order.put("createdAt", System.currentTimeMillis());
        ref.setValue(order).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                callback.onDone(true, String.format(Locale.US, "%s %s order placed: %s %s @ $%,.2f",
                        type, side, fmtShares(shares), symbol, price));
            } else {
                callback.onDone(false, "Could not place the order");
            }
        });
    }

    static void setBracket(DatabaseReference acct, String symbol, double tp, double sl,
                           Result callback) {
        final String key = positionKey(symbol);
        acct.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData account) {
                MutableData pos = account.child("positions").child(key);
                if (pos.child("shares").getValue() == null) return Transaction.abort();
                pos.child("tp").setValue(tp > 0 ? tp : null);
                pos.child("sl").setValue(sl > 0 ? sl : null);
                return Transaction.success(account);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed,
                                   @Nullable DataSnapshot snapshot) {
                callback.onDone(error == null && committed,
                        error == null && committed ? "Take profit / stop loss saved"
                                : "Could not save the levels");
            }
        });
    }

    static void cancelOrder(DatabaseReference acct, String orderKey) {
        acct.child("orders").child(orderKey).removeValue();
    }

    /** Wipes the account and starts again with the given capital. */
    static void reset(DatabaseReference acct, DatabaseReference equity, double capital,
                      double fee, Result callback) {
        Map<String, Object> fresh = new HashMap<>();
        fresh.put("cash", capital);
        fresh.put("startingCash", capital);
        fresh.put("fee", fee);
        fresh.put("createdAt", System.currentTimeMillis());
        acct.setValue(fresh).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                equity.removeValue();
                callback.onDone(true, "Account reset");
            } else {
                callback.onDone(false, "Could not reset the account");
            }
        });
    }

    static void setFee(DatabaseReference acct, double fee) {
        acct.child("fee").setValue(fee);
    }

    /** First run / legacy accounts: make sure cash and startingCash exist. */
    static void ensureAccount(DatabaseReference acct) {
        acct.runTransaction(new Transaction.Handler() {
            @NonNull
            @Override
            public Transaction.Result doTransaction(@NonNull MutableData account) {
                if (account.child("cash").getValue() == null) {
                    account.child("cash").setValue(DEFAULT_CASH);
                }
                if (account.child("startingCash").getValue() == null) {
                    account.child("startingCash").setValue(DEFAULT_CASH);
                }
                return Transaction.success(account);
            }

            @Override
            public void onComplete(@Nullable DatabaseError error, boolean committed,
                                   @Nullable DataSnapshot snapshot) { }
        });
    }
}
