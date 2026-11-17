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

    private static final String URL =
            "https://production.dataviz.cnn.io/index/fearandgreed/graphdata";
    private static final OkHttpClient CLIENT = new OkHttpClient();

    public static void fetch(Listener listener) {
        Request request = new Request.Builder().url(URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .header("Origin", "https://edition.cnn.com")
                .header("Referer", "https://edition.cnn.com/")
                .header("Accept", "application/json")
                .build();
        CLIENT.newCall(request).enqueue(new Callback() {
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
                    JSONObject fng = new JSONObject(r.body().string())
                            .getJSONObject("fear_and_greed");
                    int score = (int) Math.round(fng.getDouble("score"));
                    String rating = fng.optString("rating", "");
                    listener.onResult(score, rating, null);
                } catch (Exception e) {
                    listener.onResult(-1, "", e);
                }
            }
        });
    }
}
