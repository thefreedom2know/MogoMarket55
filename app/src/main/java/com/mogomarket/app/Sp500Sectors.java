package com.mogomarket.app;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Offline lookup of the GICS sector for every S&P 500 stock, so the Add Trade screen can fill the
 * sector the moment a ticker is typed, with no network call. Sector names match the app's sector
 * list ("Information Technology" is "Technology", "Health Care" is "Healthcare").
 *
 * Source: S&P 500 constituents list (datasets/s-and-p-500-companies), November 2026. The index
 * changes a few times a year; tickers that are not listed here fall back to the Finnhub lookup.
 */
public final class Sp500Sectors {

    private static final String[][] SECTOR_TICKERS = {
            {"Communication Services",
                    "APP CHTR CMCSA DIS ECHO FOX FOXA GOOG GOOGL LYV META NFLX NWS NWSA OMC RDDT "
                    + "SKYD T TKO TMUS TTWO VZ "},
            {"Consumer Discretionary",
                    "ABNB AMZN APTV AZO BBY BKNG CCL CMG CVNA DASH DECK DHI DPZ DRI EBAY EXPE F GM "
                    + "GPC GRMN HAS HD HLT LEN LOW LULU LVS MAR MCD MGM NCLH NKE NVR ORLY PHM RCL RL "
                    + "ROST SBUX TJX TPR TSCO TSLA ULTA WSM WYNN YUM "},
            {"Consumer Staples",
                    "ADM BF.B BG CASY CHD CL CLX COST DG DLTR EL GIS HRL HSY KDP KHC KMB KO KR "
                    + "MDLZ MKC MNST MO PEP PG PM SJM STZ SYY TGT TSN VYLR WMT "},
            {"Energy",
                    "APA BKR COP CVX DVN EOG EQT EXE FANG HAL KMI MPC OKE OXY PSX SLB TPL TRGP VLO "
                    + "WMB XOM "},
            {"Financials",
                    "ACGL AFL AIG AIZ AJG ALL AMP AON APO ARES AXP BAC BEN BLK BNY BRK.B BRO BX C "
                    + "CB CBOE CFG CINF CME COF COIN CPAY EG ERIE FDS FIS FISV FITB GL GPN GS HBAN "
                    + "HIG HOOD IBKR ICE IVZ JKHY JPM KEY KKR L MA MCO MET MRSH MS MSCI MTB NDAQ "
                    + "NTRS PFG PGR PNC PRU PYPL RF RJF SCHW SPGI STT SYF TFC TROW TRV USB V WFC WRB "
                    + "WTW XYZ "},
            {"Healthcare",
                    "A ABBV ABT ALGN AMGN BAX BDX BIIB BMY BSX CAH CI CNC COO COR CRL CVS DGX DHR "
                    + "DVA DXCM ELV EW GEHC GILD HCA HSIC HUM IDXX ILMN INCY IQV ISRG JNJ LH LLY MCK "
                    + "MDT MRK MRNA MTD PFE PODD REGN RMD RVTY SOLV STE SYK TECH TMO UHS UNH VEEV "
                    + "VRTX VTRS WAT WST ZBH ZTS "},
            {"Industrials",
                    "ADP ALLE AME AOS AXON BA BE BR CARR CAT CHRW CMI CPRT CSX CTAS DAL DD DE DOV "
                    + "EFX EME EMR ETN EXPD FAST FDX FDXF FERG FIX FTV GD GE GEV GNRC GWW HII HON "
                    + "HONA HUBB HWM IEX IR ITW J JBHT JCI LDOS LHX LII LMT LUV MAS MMM NDSN NOC NSC "
                    + "ODFL OTIS PAYX PCAR PH PNR PWR ROK ROL RSG RTX SNA SWK TDG TT TXT UAL UBER "
                    + "UNP UPS URI VLTO VRSK VRT WAB WM XYL "},
            {"Materials",
                    "ALB AMCR APD AVY BALL CF CRH DOW ECL FCX IFF IP LIN LYB MLM MOS NEM NUE PKG "
                    + "PPG SHW STLD SW VMC "},
            {"Real Estate",
                    "AMT ARE BXP CBRE CCI CPT CSGP DLR DOC EQIX ESS EXR FRT HST INVH IRM KIM MAA O "
                    + "PLD PSA REG SBAC SPG UDR VICI VMRK VTR WELL WY "},
            {"Technology",
                    "AAPL ACN ADBE ADI ADSK AKAM AMAT AMD ANET APH AVGO CDNS CDW CIEN COHR CRM "
                    + "CRWD CSCO CTSH DDOG DELL FFIV FICO FLEX FSLR FTNT GDDY GEN GLW HPE HPQ IBM "
                    + "INTC INTU IT JBL KEYS KLAC LITE LRCX MCHP MPWR MRVL MSFT MSI MU NOW NTAP NVDA "
                    + "NXPI OKTA ON ORCL P PANW PLTR PTC Q QCOM ROP SMCI SNDK SNPS STX SWKS TDY TEL "
                    + "TER TRMB TWLO TXN TYL VRSN WDAY WDC ZBRA "},
            {"Utilities",
                    "AEE AEP AES ATO AWK CEG CMS CNP D DTE DUK ED EIX ES ETR EVRG EXC FE LNT NEE "
                    + "NI NRG PCG PEG PNW PPL SO SRE VST WEC XEL "},
    };

    private static final Map<String, String> BY_TICKER = new HashMap<>();

    static {
        for (String[] row : SECTOR_TICKERS) {
            for (String ticker : row[1].trim().split("\\s+")) {
                BY_TICKER.put(ticker, row[0]);
            }
        }
    }

    private Sp500Sectors() {}

    /**
     * Returns the app sector name for an S&P 500 ticker, or null when the ticker is not in the
     * index. Accepts "BRK.B", "BRK-B" or "BRK/B", any letter case, and surrounding spaces.
     */
    public static String sectorFor(String ticker) {
        if (ticker == null) return null;
        String key = ticker.trim().toUpperCase(Locale.US).replace('-', '.').replace('/', '.');
        if (key.isEmpty()) return null;
        return BY_TICKER.get(key);
    }

    /** Number of tickers in the table (for tests). */
    static int size() {
        return BY_TICKER.size();
    }
}
