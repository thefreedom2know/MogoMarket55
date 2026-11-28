package com.mogomarket.app;

import androidx.annotation.NonNull;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;
import com.google.firebase.functions.HttpsCallableResult;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Sends every request to {@code finnhub.io} through the "finnhub" Firebase Function, which holds
 * the API key on the server. The app builds normal Finnhub URLs without a token; this interceptor
 * turns them into a function call and hands back the JSON as if Finnhub had answered directly,
 * so the calling code does not change. Requests to any other host pass through untouched.
 * If the function is not deployed or fails, quotes come from Yahoo instead
 * ({@link YahooQuoteFallback}), so prices still load.
 */
public final class FinnhubProxy implements Interceptor {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final OkHttpClient CLIENT =
            new OkHttpClient.Builder().addInterceptor(new FinnhubProxy()).build();

    private static final String QUOTE_PATH = "/api/v1/quote";
    private static final long FUNCTION_RETRY_MS = 5 * 60 * 1000L;
    private static volatile long functionDownUntil = 0L;

    private FinnhubProxy() {}

    /** Shared HTTP client for code that talks to Finnhub (and any other host). */
    public static OkHttpClient client() {
        return CLIENT;
    }

    /** Finnhub works for logged-in users only, because the function requires a Firebase login. */
    public static boolean isAvailable() {
        return FirebaseAuth.getInstance().getCurrentUser() != null;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request request = chain.request();
        HttpUrl url = request.url();
        if (!"finnhub.io".equals(url.host())) {
            return chain.proceed(request);
        }
        boolean isQuote = QUOTE_PATH.equals(url.encodedPath());
        // Quotes skip the server function while it is known to be failing, or when logged out.
        if (isQuote && (!isAvailable() || functionRecentlyFailed())) {
            Response backup = yahooQuote(request, url);
            if (backup != null) return backup;
        }
        if (!isAvailable()) {
            return respond(request, 401, "{\"error\":\"Log in to load quotes\"}");
        }
        Response response;
        try {
            response = callFunction(request, url);
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) throw e;
            markFunctionFailed();
            if (isQuote) {
                Response backup = yahooQuote(request, url);
                if (backup != null) return backup;
            }
            throw e;
        }
        if (isQuote && (response.code() != 200 || hasNoPrice(response))) {
            if (response.code() >= 500) markFunctionFailed();
            Response backup = yahooQuote(request, url);
            if (backup != null) {
                response.close();
                return backup;
            }
        }
        return response;
    }

    /** True for a few minutes after the server function failed, so quotes go straight to Yahoo. */
    private static boolean functionRecentlyFailed() {
        return System.currentTimeMillis() < functionDownUntil;
    }

    private static void markFunctionFailed() {
        functionDownUntil = System.currentTimeMillis() + FUNCTION_RETRY_MS;
    }

    /** Finnhub answers {"c":0,...} for symbols it does not cover (for example TASE shares). */
    private static boolean hasNoPrice(Response response) {
        try {
            String body = response.peekBody(64 * 1024).string();
            return !(new org.json.JSONObject(body).optDouble("c", 0) > 0);
        } catch (Exception e) {
            return true;
        }
    }

    /** The same quote from Yahoo, in Finnhub's format, or null if Yahoo has none either. */
    private static Response yahooQuote(Request request, HttpUrl url) {
        String json = YahooQuoteFallback.finnhubQuoteJson(url.queryParameter("symbol"));
        return json == null ? null : respond(request, 200, json);
    }

    private static Response callFunction(Request request, HttpUrl url) throws IOException {
        Map<String, Object> params = new HashMap<>();
        for (String name : url.queryParameterNames()) {
            if (!"token".equals(name)) params.put(name, url.queryParameter(name));
        }
        Map<String, Object> data = new HashMap<>();
        data.put("path", url.encodedPath());
        data.put("params", params);

        try {
            HttpsCallableResult result = Tasks.await(FirebaseFunctions.getInstance()
                    .getHttpsCallable("finnhub").call(data), 20, TimeUnit.SECONDS);
            Object payload = result.getData();
            if (!(payload instanceof Map)) return respond(request, 502, "{}");
            Map<?, ?> map = (Map<?, ?>) payload;
            Object status = map.get("status");
            Object body = map.get("body");
            int code = status instanceof Number ? ((Number) status).intValue() : 200;
            return respond(request, code, body == null ? "{}" : String.valueOf(body));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FirebaseFunctionsException) {
                FirebaseFunctionsException.Code code =
                        ((FirebaseFunctionsException) cause).getCode();
                if (code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED) {
                    return respond(request, 429, "{}");
                }
                if (code == FirebaseFunctionsException.Code.UNAUTHENTICATED) {
                    return respond(request, 401, "{}");
                }
                if (code == FirebaseFunctionsException.Code.INVALID_ARGUMENT) {
                    return respond(request, 400, "{}");
                }
            }
            throw new IOException("Finnhub server function failed", cause);
        } catch (TimeoutException e) {
            throw new InterruptedIOException("Finnhub server function timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted");
        }
    }

    private static Response respond(Request request, int code, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Error")
                .body(ResponseBody.create(body, JSON))
                .build();
    }
}
