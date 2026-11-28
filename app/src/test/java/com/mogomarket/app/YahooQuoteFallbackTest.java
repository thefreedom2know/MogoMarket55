package com.mogomarket.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class YahooQuoteFallbackTest {

    @Test
    public void plainTickersStayTheSame() {
        assertEquals("AAPL", YahooQuoteFallback.toYahooSymbol("AAPL"));
        assertEquals("NVDA", YahooQuoteFallback.toYahooSymbol(" nvda "));
        assertEquals("TEVA.TA", YahooQuoteFallback.toYahooSymbol("TEVA.TA"));
    }

    @Test
    public void shareClassDotBecomesDash() {
        assertEquals("BRK-B", YahooQuoteFallback.toYahooSymbol("BRK.B"));
        assertEquals("BF-B", YahooQuoteFallback.toYahooSymbol("bf.b"));
    }

    @Test
    public void oandaForexBecomesYahooPair() {
        assertEquals("EURUSD=X", YahooQuoteFallback.toYahooSymbol("OANDA:EUR_USD"));
    }

    @Test
    public void unsupportedSymbolsReturnNull() {
        assertNull(YahooQuoteFallback.toYahooSymbol(null));
        assertNull(YahooQuoteFallback.toYahooSymbol("  "));
        assertNull(YahooQuoteFallback.toYahooSymbol("BINANCE:BTCUSDT"));
        assertNull(YahooQuoteFallback.toYahooSymbol("OANDA:XAU"));
    }
}
