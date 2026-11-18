package com.mogomarket.app;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * AI service. Uses Groq (free, OpenAI-compatible) first and Gemini as a fallback.
 * Supports single prompts and multi-turn chat.
 */
public class LLMService {

    private static final String GEMINI_API_URL = ApiConfig.GEMINI_MODELS +
            "gemini-flash-latest:generateContent";
    private static final String API_KEY = ApiConfig.geminiKey();

    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private final DecimalFormat df = new DecimalFormat("#.##");
    private long lastGeminiRequestTime = 0;

    // The Groq model that worked last time (so unavailable models are not retried).
    private static volatile int groqModelStart = 0;

    public interface AnalysisCallback {
        void onAnalysisReceived(String analysis);
        void onError(String error);
    }

    public interface LLMCallback {
        void onSuccess(String result);
        void onFailure(Throwable t);
    }

    public interface StreamCallback {
        void onToken(String token);
        void onComplete(String fullText);
        void onError(String error);
    }

    /** One chat message. role is "user" or "assistant". */
    public static final class Turn {
        public final String role;
        public final String text;

        public Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }
    }

    public LLMService() { }

    private static boolean hasGemini() {
        return API_KEY != null && !API_KEY.trim().isEmpty();
    }

    public static boolean isConfigured() {
        return ApiConfig.hasGroqKey() || hasGemini();
    }

    // ------------------------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------------------------

    /** Multi-turn chat. The system prompt carries the context (e.g. chart data). */
    public void chat(String systemPrompt, List<Turn> turns, LLMCallback callback) {
        sendChat(systemPrompt, turns, new AnalysisCallback() {
            @Override
            public void onAnalysisReceived(String analysis) {
                callback.onSuccess(analysis);
            }

            @Override
            public void onError(String error) {
                callback.onFailure(new Exception(error));
            }
        });
    }

    public void generateContent(String prompt, LLMCallback callback) {
        List<Turn> turns = new ArrayList<>();
        turns.add(new Turn("user", prompt));
        chat(null, turns, callback);
    }

    public void streamQuery(String prompt, StreamCallback callback) {
        List<Turn> turns = new ArrayList<>();
        turns.add(new Turn("user", prompt));
        sendChat(null, turns, new AnalysisCallback() {
            @Override
            public void onAnalysisReceived(String result) {
                String[] words = result.split(" ");
                Handler handler = new Handler(Looper.getMainLooper());
                for (int i = 0; i < words.length; i++) {
                    final String token = (i == 0 ? "" : " ") + words[i];
                    final int idx = i;
                    final int total = words.length;
                    handler.postDelayed(() -> {
                        callback.onToken(token);
                        if (idx == total - 1) callback.onComplete(result);
                    }, i * 40L);
                }
            }

            @Override
            public void onError(String error) {
                callback.onError(error);
            }
        });
    }

    public void askQuestion(String symbol, String question, String context,
                            List<Float> closes, AnalysisCallback callback) {
        String pricesStr = "לא זמין";
        if (closes != null && closes.size() >= 2) {
            String[] formatted = closes.subList(0, Math.min(5, closes.size()))
                    .stream()
                    .map(c -> df.format(c))
                    .toArray(String[]::new);
            pricesStr = String.join(", ", formatted);
        }
        String prompt = String.format(
                "אתה יועץ פיננסי מקצועי. ענה תמיד בעברית, תמציתי וברור.\n\n" +
                        "שאלה על מניה %s:\n\n" +
                        "שאלה: %s\n\n" +
                        "הקשר: %s\n" +
                        "מחירי סגירה אחרונים: %s\n\n" +
                        "ענה בעברית בלבד, תמציתי ומקצועי.",
                symbol, question, context, pricesStr);
        List<Turn> turns = new ArrayList<>();
        turns.add(new Turn("user", prompt));
        sendChat(null, turns, callback);
    }

    // ------------------------------------------------------------------------------------
    // Routing: Groq first, Gemini as a fallback
    // ------------------------------------------------------------------------------------

    private void sendChat(String systemPrompt, List<Turn> turns, AnalysisCallback callback) {
        if (ApiConfig.hasGroqKey()) {
            sendToGroq(systemPrompt, turns, new AnalysisCallback() {
                @Override
                public void onAnalysisReceived(String analysis) {
                    callback.onAnalysisReceived(analysis);
                }

                @Override
                public void onError(String error) {
                    if (hasGemini()) {
                        sendToGemini(systemPrompt, turns, callback);
                    } else {
                        callback.onError(error);
                    }
                }
            }, groqModelStart);
            return;
        }
        sendToGemini(systemPrompt, turns, callback);
    }

    private void sendToGroq(String systemPrompt, List<Turn> turns, AnalysisCallback callback,
                            int modelIndex) {
        if (modelIndex >= ApiConfig.GROQ_MODELS.length) {
            notifyError(callback, "לחשבון ה-Groq שלך אין גישה לאף אחד מהמודלים. "
                    + "בדוק את ההגדרות ב-console.groq.com (Settings > Limits).");
            return;
        }
        final String model = ApiConfig.GROQ_MODELS[modelIndex];

        JSONObject requestBody = new JSONObject();
        try {
            JSONArray messages = new JSONArray();
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
            }
            for (Turn t : turns) {
                messages.put(new JSONObject().put("role", t.role).put("content", t.text));
            }
            requestBody.put("model", model);
            requestBody.put("messages", messages);
            requestBody.put("temperature", 0.4);
        } catch (Exception e) {
            notifyError(callback, "שגיאה בבניית הבקשה: " + e.getMessage());
            return;
        }

        Request request = new Request.Builder()
                .url(ApiConfig.GROQ_CHAT)
                .addHeader("Authorization", "Bearer " + ApiConfig.groqKey())
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(),
                        MediaType.get("application/json; charset=utf-8")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                notifyError(callback, "שגיאת רשת: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429) {
                    notifyError(callback, "המכסה של Groq נגמרה זמנית. נסה שוב בעוד דקה.");
                    return;
                }
                if (response.code() == 401) {
                    notifyError(callback, "Groq דחה את המפתח. בדוק את GROQ_API_KEY.");
                    return;
                }
                boolean noModelAccess = response.code() == 404 || response.code() == 403
                        || (response.code() == 400 && responseBody.contains("model"));
                if (noModelAccess) {
                    android.util.Log.w("LLM", "Groq model unavailable: " + model);
                    sendToGroq(systemPrompt, turns, callback, modelIndex + 1);
                    return;
                }
                if (!response.isSuccessful()) {
                    notifyError(callback, "שגיאת Groq " + response.code() + ": " + responseBody);
                    return;
                }
                try {
                    String text = new JSONObject(responseBody).getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message")
                            .optString("content", "").trim();
                    if (text.isEmpty()) {
                        notifyError(callback, "לא התקבלה תשובה מה-AI");
                        return;
                    }
                    groqModelStart = modelIndex;
                    new Handler(Looper.getMainLooper()).post(() ->
                            callback.onAnalysisReceived(text));
                } catch (Exception e) {
                    notifyError(callback, "שגיאה בפענוח תגובה: " + e.getMessage());
                }
            }
        });
    }

    private void sendToGemini(String systemPrompt, List<Turn> turns, AnalysisCallback callback) {
        if (!hasGemini()) {
            notifyError(callback,
                    "לא הוגדר מפתח AI. הוסף GROQ_API_KEY (או GEMINI_API_KEY) ל-local.properties.");
            return;
        }

        long now = System.currentTimeMillis();
        long timeSinceLast = now - lastGeminiRequestTime;
        if (timeSinceLast < 5000) {
            long delay = 5000 - timeSinceLast;
            new Handler(Looper.getMainLooper()).postDelayed(() ->
                    sendToGemini(systemPrompt, turns, callback), delay);
            return;
        }
        lastGeminiRequestTime = System.currentTimeMillis();

        JSONObject requestBody = new JSONObject();
        try {
            JSONArray contents = new JSONArray();
            for (Turn t : turns) {
                contents.put(new JSONObject()
                        .put("role", "assistant".equals(t.role) ? "model" : "user")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", t.text))));
            }
            requestBody.put("contents", contents);
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                requestBody.put("systemInstruction", new JSONObject().put("parts",
                        new JSONArray().put(new JSONObject().put("text", systemPrompt))));
            }
        } catch (Exception e) {
            notifyError(callback, "שגיאה בבניית הבקשה: " + e.getMessage());
            return;
        }

        Request request = new Request.Builder()
                .url(GEMINI_API_URL)
                .addHeader("Accept", "application/json")
                .addHeader("x-goog-api-key", API_KEY)
                .post(RequestBody.create(requestBody.toString(),
                        MediaType.get("application/json; charset=utf-8")))
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                notifyError(callback, "שגיאת רשת: " + e.getMessage());
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (response.code() == 429) {
                    notifyError(callback, "המכסה היומית של Gemini נגמרה.\nנסה שוב מאוחר יותר.");
                    return;
                }
                if (response.code() == 403) {
                    notifyError(callback, "Gemini דחה את הבקשה. בדוק שהמפתח תקין ופעיל.");
                    return;
                }
                if (!response.isSuccessful()) {
                    notifyError(callback, "שגיאת API " + response.code() + ": " + responseBody);
                    return;
                }
                try {
                    JSONArray candidates = new JSONObject(responseBody).optJSONArray("candidates");
                    if (candidates == null || candidates.length() == 0) {
                        notifyError(callback, "לא התקבלה תשובה מה-AI");
                        return;
                    }
                    JSONArray parts = candidates.getJSONObject(0)
                            .getJSONObject("content").getJSONArray("parts");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < parts.length(); i++) {
                        sb.append(parts.getJSONObject(i).optString("text", ""));
                    }
                    String result = sb.toString().trim();
                    new Handler(Looper.getMainLooper()).post(() ->
                            callback.onAnalysisReceived(result));
                } catch (Exception e) {
                    notifyError(callback, "שגיאה בפענוח תגובה: " + e.getMessage());
                }
            }
        });
    }

    private void notifyError(AnalysisCallback callback, String error) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(error));
    }
}
