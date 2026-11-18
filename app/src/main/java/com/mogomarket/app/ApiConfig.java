package com.mogomarket.app;

/**
 * One place for every external API the app talks to.
 *
 * <p>Endpoints are constants below. Secrets (keys, passwords) are never written in the code:
 * they live in {@code local.properties} (see {@code local.properties.example}) and reach the app
 * through BuildConfig, and this class is the only code that reads them.
 */
public final class ApiConfig {
    private ApiConfig() {}

    // ---- Yahoo Finance (free, no key): charts, quotes, pre/post market, symbol search ----
    public static final String YAHOO_CHART = "https://query1.finance.yahoo.com/v8/finance/chart/";
    public static final String YAHOO_SEARCH = "https://query1.finance.yahoo.com/v1/finance/search?q=";

    // ---- Finnhub (needs FINNHUB_KEY): stock quotes, search, company profile, logos ----
    public static final String FINNHUB_QUOTE = "https://finnhub.io/api/v1/quote?symbol=";
    public static final String FINNHUB_SEARCH = "https://finnhub.io/api/v1/search?q=";
    public static final String FINNHUB_PROFILE = "https://finnhub.io/api/v1/stock/profile2?symbol=";
    public static final String FINNHUB_LOGO =
            "https://static2.finnhub.io/file/publicdatany/finnhubimage/stock_logo/";

    // ---- Binance (free, no key): crypto prices and candles ----
    public static final String BINANCE_PRICE = "https://api.binance.com/api/v3/ticker/price?symbol=";
    public static final String BINANCE_24H = "https://api.binance.com/api/v3/ticker/24hr?symbol=";
    public static final String BINANCE_KLINES = "https://api.binance.com/api/v3/klines?symbol=";

    // ---- Google Gemini (needs GEMINI_API_KEY): AI chat ----
    public static final String GEMINI_MODELS = "https://generativelanguage.googleapis.com/v1beta/models/";

    // ---- Groq (free tier, needs GROQ_API_KEY): AI chat, OpenAI-compatible ----
    public static final String GROQ_CHAT = "https://api.groq.com/openai/v1/chat/completions";
    public static final String GROQ_MODEL = "llama-3.3-70b-versatile";

    // ---- EmailJS: sending emails ----
    public static final String EMAILJS_SEND = "https://api.emailjs.com/api/v1.0/email/send";

    // ---- Secrets from local.properties ----
    public static String finnhubKey() {
        return BuildConfig.FINNHUB_KEY;
    }

    public static boolean hasFinnhubKey() {
        String key = BuildConfig.FINNHUB_KEY;
        return key != null && !key.trim().isEmpty();
    }

    public static String groqKey() {
        return BuildConfig.GROQ_API_KEY;
    }

    public static boolean hasGroqKey() {
        String key = BuildConfig.GROQ_API_KEY;
        return key != null && !key.trim().isEmpty();
    }

    public static String geminiKey() {
        return BuildConfig.GEMINI_API_KEY;
    }

    public static String smtpEmail() {
        return BuildConfig.SMTP_EMAIL;
    }

    public static String smtpPassword() {
        return BuildConfig.SMTP_PASSWORD;
    }
}
