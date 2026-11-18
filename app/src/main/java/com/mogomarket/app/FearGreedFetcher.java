package com.mogomarket.app;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Fetches CNN's Fear & Greed index (0-100) and its rating text. */
public class FearGreedFetcher {

    public interface Listener {
        void onResult(int score, @NonNull String rating, @Nullable Exception error);
    }

    /** Full reading, including how the index looked a day, week and month ago. */
    public static class Snapshot {
        public int score;
        public String rating = "";
        public double previousClose = Double.NaN;
        public double previousWeek = Double.NaN;
        public double previousMonth = Double.NaN;
    }

    private static final String URL =
            "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    private static Request buildRequest() {
        // CNN answers 418 to short/unknown user agents, so look like a normal browser.
        return new Request.Builder().url(URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Origin", "https://edition.cnn.com")
                .header("Referer", "https://edition.cnn.com/")
                .header("Accept", "application/json")
                .build();
    }

    private static Snapshot parse(String json) throws Exception {
        JSONObject fng = new JSONObject(json).getJSONObject("fear_and_greed");
        Snapshot s = new Snapshot();
        s.score = (int) Math.round(fng.getDouble("score"));
        s.rating = fng.optString("rating", "");
        s.previousClose = fng.optDouble("previous_close", Double.NaN);
        s.previousWeek = fng.optDouble("previous_1_week", Double.NaN);
        s.previousMonth = fng.optDouble("previous_1_month", Double.NaN);
        return s;
    }

    /** Blocking version for background workers. Returns null when unavailable. */
    @Nullable
    public static Snapshot fetchBlocking() {
        try (Response r = CLIENT.newCall(buildRequest()).execute()) {
            if (!r.isSuccessful() || r.body() == null) return null;
            return parse(r.body().string());
        } catch (Exception e) {
            return null;
        }
    }

    public static void fetch(Listener listener) {
        CLIENT.newCall(buildRequest()).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                listener.onResult(-1, "", e);
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        listener.onResult(-1, "", new IOException("HTTP " + r.code()));
                        return;
                    }
                    Snapshot s = parse(r.body().string());
                    listener.onResult(s.score, s.rating, null);
                } catch (Exception e) {
                    listener.onResult(-1, "", e);
                }
            }
        });
    }
}
