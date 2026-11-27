package com.mogomarket.app;

/**
 * One place for every external API the app talks to.
 *
 * <p>Endpoints are constants below. The app holds no secrets at all: the Finnhub, Groq and Gemini
 * keys and the email password live in Firebase Functions secrets (see {@code functions/README.md}).
 * Finnhub requests go through {@link FinnhubProxy}, AI through {@link LLMService}, and the summary
 * email through {@link DailySummaryEmailService}.
 */
public final class ApiConfig {
    private ApiConfig() {}

    // ---- Yahoo Finance (free, no key): charts, quotes, pre/post market, symbol search ----
    public static final String YAHOO_CHART = "https://query1.finance.yahoo.com/v8/finance/chart/";
    public static final String YAHOO_SEARCH = "https://query1.finance.yahoo.com/v1/finance/search?q=";

    // ---- Finnhub (key on the server, see FinnhubProxy): quotes, search, company profile, logos ----
    public static final String FINNHUB_QUOTE = "https://finnhub.io/api/v1/quote?symbol=";
    public static final String FINNHUB_SEARCH = "https://finnhub.io/api/v1/search?q=";
    public static final String FINNHUB_PROFILE = "https://finnhub.io/api/v1/stock/profile2?symbol=";
    public static final String FINNHUB_LOGO =
            "https://static2.finnhub.io/file/publicdatany/finnhubimage/stock_logo/";

    // ---- Binance (free, no key): crypto prices and candles ----
    public static final String BINANCE_PRICE = "https://api.binance.com/api/v3/ticker/price?symbol=";
    public static final String BINANCE_24H = "https://api.binance.com/api/v3/ticker/24hr?symbol=";
    public static final String BINANCE_KLINES = "https://api.binance.com/api/v3/klines?symbol=";

}
