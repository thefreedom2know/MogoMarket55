package com.mogomarket.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Sp500SectorsTest {

    @Test
    public void knownTickersMapToAppSectorNames() {
        assertEquals("Technology", Sp500Sectors.sectorFor("AAPL"));
        assertEquals("Healthcare", Sp500Sectors.sectorFor("JNJ"));
        assertEquals("Consumer Discretionary", Sp500Sectors.sectorFor("AMZN"));
        assertEquals("Communication Services", Sp500Sectors.sectorFor("GOOGL"));
        assertEquals("Energy", Sp500Sectors.sectorFor("XOM"));
        assertEquals("Financials", Sp500Sectors.sectorFor("JPM"));
    }

    @Test
    public void lookupIgnoresCaseSpacesAndClassSeparator() {
        assertEquals("Technology", Sp500Sectors.sectorFor("  nvda "));
        assertEquals("Financials", Sp500Sectors.sectorFor("BRK.B"));
        assertEquals("Financials", Sp500Sectors.sectorFor("brk-b"));
        assertEquals("Financials", Sp500Sectors.sectorFor("BRK/B"));
    }

    @Test
    public void unknownOrEmptyTickersReturnNull() {
        assertNull(Sp500Sectors.sectorFor(null));
        assertNull(Sp500Sectors.sectorFor(""));
        assertNull(Sp500Sectors.sectorFor("NOTATICKER"));
        assertNull(Sp500Sectors.sectorFor("BINANCE:BTCUSDT"));
    }

    @Test
    public void tableCoversTheWholeIndex() {
        assertTrue(Sp500Sectors.size() >= 495);
    }
}
