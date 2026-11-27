package com.mogomarket.app;

import android.os.Handler;
import android.os.Looper;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * AI service. Calls the "aiChat" Firebase Function (Groq first, Gemini as a fallback).
 * Supports single prompts and multi-turn chat.
 */
public class LLMService {

    private final DecimalFormat df = new DecimalFormat("#.##");

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

    /** True when the AI can be used: the server function needs a logged-in user. */
    public static boolean isConfigured() {
        return com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null;
    }

    /**
     * Asks the "aiChat" Firebase Function (Groq first, Gemini as a fallback), which holds the keys
     * on the server (see functions/README.md). Nothing secret ships in the APK.
     */
    private void sendToServer(String systemPrompt, List<Turn> turns, AnalysisCallback callback) {
        if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() == null) {
            notifyError(callback, "Log in to use the AI assistant.");
            return;
        }
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        if (systemPrompt != null) data.put("system", systemPrompt);
        List<java.util.Map<String, Object>> list = new ArrayList<>();
        for (Turn t : turns) {
            java.util.Map<String, Object> m = new java.util.HashMap<>();
            m.put("role", t.role);
            m.put("text", t.text);
            list.add(m);
        }
        data.put("turns", list);
        com.google.firebase.functions.FirebaseFunctions.getInstance()
                .getHttpsCallable("aiChat")
                .call(data)
                .addOnSuccessListener(result -> {
                    Object payload = result.getData();
                    String text = payload instanceof java.util.Map
                            ? String.valueOf(((java.util.Map<?, ?>) payload).get("text")) : "";
                    if (text.isEmpty() || "null".equals(text)) {
                        callback.onError("No answer from the AI server");
                    } else {
                        callback.onAnalysisReceived(text);
                    }
                })
                .addOnFailureListener(e -> {
                    String message = e.getMessage() == null ? "" : e.getMessage();
                    if (e instanceof com.google.firebase.functions.FirebaseFunctionsException
                            && ((com.google.firebase.functions.FirebaseFunctionsException) e)
                            .getCode() == com.google.firebase.functions.FirebaseFunctionsException
                            .Code.NOT_FOUND) {
                        message = "The AI server function is not deployed yet "
                                + "(see functions/README.md).";
                    }
                    callback.onError("AI server error: " + message);
                });
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
    // Routing: always the server (the AI keys never ship inside the app)
    // ------------------------------------------------------------------------------------

    private void sendChat(String systemPrompt, List<Turn> turns, AnalysisCallback callback) {
        sendToServer(systemPrompt, turns, callback);
    }

    private void notifyError(AnalysisCallback callback, String error) {
        new Handler(Looper.getMainLooper()).post(() -> callback.onError(error));
    }
}
