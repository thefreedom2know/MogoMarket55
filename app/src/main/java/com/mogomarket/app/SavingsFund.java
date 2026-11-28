package com.mogomarket.app;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One long-term savings product of the user: קופת גמל, קרן השתלמות, קרן פנסיה, גמל להשקעה,
 * חיסכון לכל ילד or a savings insurance policy.
 *
 * The user's own fields live in users/&lt;uid&gt;/funds/&lt;key&gt;. The server writes the
 * {@code market} child (returns, fees, peers, value estimate) from the Capital Market
 * Authority's monthly open data; see functions/funds.js.
 */
public class SavingsFund {

    public static final String KIND_PENSION = "pension";
    public static final String KIND_GEMEL = "gemel";
    public static final String KIND_HISHTALMUT = "hishtalmut";
    public static final String KIND_INVEST = "invest";
    public static final String KIND_CHILD = "child";
    public static final String KIND_POLICY = "policy";
    public static final String KIND_OTHER = "other";

    public static final String[] KINDS = {
            KIND_PENSION, KIND_GEMEL, KIND_HISHTALMUT, KIND_INVEST, KIND_CHILD, KIND_POLICY, KIND_OTHER
    };

    public String key;

    public long fundId;          // מספר קופה / מסלול (0 when unknown)
    public String name = "";
    public String company = "";
    public String kind = KIND_OTHER;
    public String track = "";
    public String account = "";
    public double balance;
    public int balancePeriod;    // YYYYMM the balance is correct for (0 when unknown)
    public double feeBalance = Double.NaN;  // % per year from balance
    public double feeDeposit = Double.NaN;  // % of each deposit
    public double monthlyDeposit;
    public boolean active = true;
    public String importSource = "";        // "harhakesef" or "manual"

    public Market market;

    /** What data.gov.il says about the fund (all percent values are in %). */
    public static class Market {
        public boolean notFound;
        public String error = "";
        public String source = "";
        public String name = "";
        public String company = "";
        public String classification = "";
        public String specialization = "";
        public String subSpecialization = "";
        public int period;
        public double monthlyYield = Double.NaN;
        public double ytdYield = Double.NaN;
        public double return12m = Double.NaN;
        public double avgAnnual3y = Double.NaN;
        public double avgAnnual5y = Double.NaN;
        public double fee = Double.NaN;
        public double depositFee = Double.NaN;
        public double stockExposure = Double.NaN;
        public double foreignExposure = Double.NaN;
        public double fxExposure = Double.NaN;
        public double sharpe = Double.NaN;
        public double estimatedValue = Double.NaN;
        public int estimatedPeriod;
        public long updatedAt;
        public long checkedAt;

        public double peerAvgFee = Double.NaN;
        public double peerAvgYtd = Double.NaN;
        public double peerAvgAnnual3y = Double.NaN;
        public double peerAvgAnnual5y = Double.NaN;
        public int peerCount;
        public int rank3y;
        public int ranked3y;
        public String peerTopText = "";

        public double ta125Ytd = Double.NaN;
        public double ta125R12m = Double.NaN;
        public double sp500Ytd = Double.NaN;
        public double sp500R12m = Double.NaN;

        /** Monthly gross return by YYYYMM. */
        public final TreeMap<Integer, Double> history = new TreeMap<>();
    }

    // ---- Firebase <-> object (the plain map keeps unknown/server fields untouched) ----------

    /** Fields the app writes. Never includes {@code market}, which belongs to the server. */
    public Map<String, Object> toUserMap() {
        Map<String, Object> m = new TreeMap<>();
        m.put("fundId", fundId > 0 ? fundId : null);
        m.put("name", name);
        m.put("company", company);
        m.put("kind", kind);
        m.put("track", track);
        m.put("account", account);
        m.put("balance", balance);
        m.put("balancePeriod", balancePeriod > 0 ? balancePeriod : null);
        m.put("feeBalance", Double.isNaN(feeBalance) ? null : feeBalance);
        m.put("feeDeposit", Double.isNaN(feeDeposit) ? null : feeDeposit);
        m.put("monthlyDeposit", monthlyDeposit > 0 ? monthlyDeposit : null);
        m.put("active", active);
        m.put("importSource", importSource);
        m.put("source", sourceForKind(kind));
        return m;
    }

    @SuppressWarnings("unchecked")
    public static SavingsFund fromMap(String key, Map<String, Object> m) {
        SavingsFund f = new SavingsFund();
        f.key = key;
        if (m == null) return f;
        f.fundId = (long) num(m.get("fundId"), 0);
        f.name = str(m.get("name"));
        f.company = str(m.get("company"));
        f.kind = str(m.get("kind")).isEmpty() ? KIND_OTHER : str(m.get("kind"));
        f.track = str(m.get("track"));
        f.account = str(m.get("account"));
        f.balance = num(m.get("balance"), 0);
        f.balancePeriod = (int) num(m.get("balancePeriod"), 0);
        f.feeBalance = num(m.get("feeBalance"), Double.NaN);
        f.feeDeposit = num(m.get("feeDeposit"), Double.NaN);
        f.monthlyDeposit = num(m.get("monthlyDeposit"), 0);
        Object active = m.get("active");
        f.active = !(active instanceof Boolean) || (Boolean) active;
        f.importSource = str(m.get("importSource"));
        Object market = m.get("market");
        if (market instanceof Map) f.market = marketFromMap((Map<String, Object>) market);
        return f;
    }

    @SuppressWarnings("unchecked")
    static Market marketFromMap(Map<String, Object> m) {
        Market k = new Market();
        k.notFound = Boolean.TRUE.equals(m.get("notFound"));
        k.error = str(m.get("error"));
        k.source = str(m.get("source"));
        k.name = str(m.get("name"));
        k.company = str(m.get("company"));
        k.classification = str(m.get("classification"));
        k.specialization = str(m.get("specialization"));
        k.subSpecialization = str(m.get("subSpecialization"));
        k.period = (int) num(m.get("period"), 0);
        k.monthlyYield = num(m.get("monthlyYield"), Double.NaN);
        k.ytdYield = num(m.get("ytdYield"), Double.NaN);
        k.return12m = num(m.get("return12m"), Double.NaN);
        k.avgAnnual3y = num(m.get("avgAnnual3y"), Double.NaN);
        k.avgAnnual5y = num(m.get("avgAnnual5y"), Double.NaN);
        k.fee = num(m.get("fee"), Double.NaN);
        k.depositFee = num(m.get("depositFee"), Double.NaN);
        k.stockExposure = num(m.get("stockExposure"), Double.NaN);
        k.foreignExposure = num(m.get("foreignExposure"), Double.NaN);
        k.fxExposure = num(m.get("fxExposure"), Double.NaN);
        k.sharpe = num(m.get("sharpe"), Double.NaN);
        k.estimatedValue = num(m.get("estimatedValue"), Double.NaN);
        k.estimatedPeriod = (int) num(m.get("estimatedPeriod"), 0);
        k.updatedAt = (long) num(m.get("updatedAt"), 0);
        k.checkedAt = (long) num(m.get("checkedAt"), 0);

        Object peers = m.get("peers");
        if (peers instanceof Map) {
            Map<String, Object> p = (Map<String, Object>) peers;
            k.peerAvgFee = num(p.get("avgFee"), Double.NaN);
            k.peerAvgYtd = num(p.get("avgYtd"), Double.NaN);
            k.peerAvgAnnual3y = num(p.get("avgAnnual3y"), Double.NaN);
            k.peerAvgAnnual5y = num(p.get("avgAnnual5y"), Double.NaN);
            k.peerCount = (int) num(p.get("count"), 0);
            k.rank3y = (int) num(p.get("rank3y"), 0);
            k.ranked3y = (int) num(p.get("ranked3y"), 0);
            Object top = p.get("top");
            StringBuilder sb = new StringBuilder();
            if (top instanceof java.util.List) {
                int i = 1;
                for (Object o : (java.util.List<Object>) top) {
                    if (!(o instanceof Map)) continue;
                    Map<String, Object> t = (Map<String, Object>) o;
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(i++).append(". ").append(str(t.get("name")));
                    double y = num(t.get("avgAnnual3y"), Double.NaN);
                    double fee = num(t.get("fee"), Double.NaN);
                    if (!Double.isNaN(y)) sb.append(String.format(Locale.US, " · %.2f%% לשנה", y));
                    if (!Double.isNaN(fee)) sb.append(String.format(Locale.US, " · ד\"נ %.2f%%", fee));
                    long id = (long) num(t.get("fundId"), 0);
                    if (id > 0) sb.append(" · #").append(id);
                }
            }
            k.peerTopText = sb.toString();
        }
        Object index = m.get("index");
        if (index instanceof Map) {
            Map<String, Object> ix = (Map<String, Object>) index;
            if (ix.get("ta125") instanceof Map) {
                Map<String, Object> t = (Map<String, Object>) ix.get("ta125");
                k.ta125Ytd = num(t.get("ytd"), Double.NaN);
                k.ta125R12m = num(t.get("r12m"), Double.NaN);
            }
            if (ix.get("sp500") instanceof Map) {
                Map<String, Object> t = (Map<String, Object>) ix.get("sp500");
                k.sp500Ytd = num(t.get("ytd"), Double.NaN);
                k.sp500R12m = num(t.get("r12m"), Double.NaN);
            }
        }
        Object history = m.get("history");
        if (history instanceof Map) {
            for (Map.Entry<String, Object> e : ((Map<String, Object>) history).entrySet()) {
                try {
                    k.history.put(Integer.parseInt(e.getKey()), num(e.getValue(), Double.NaN));
                } catch (NumberFormatException ignored) { }
            }
        }
        return k;
    }

    // ---- derived values ---------------------------------------------------------------------

    /** Best known value: the server's estimate when it has one, else the entered balance. */
    public double currentValue() {
        if (market != null && !Double.isNaN(market.estimatedValue) && market.estimatedValue > 0) {
            return market.estimatedValue;
        }
        return balance;
    }

    /** The fee this user pays from balance: their own when known, else the fund's average. */
    public double effectiveFee() {
        if (!Double.isNaN(feeBalance)) return feeBalance;
        return market != null ? market.fee : Double.NaN;
    }

    /** True when the user's fee is clearly above the average of funds in the same track. */
    public boolean feeAboveAverage() {
        double fee = effectiveFee();
        if (market == null || Double.isNaN(fee) || Double.isNaN(market.peerAvgFee)) return false;
        return fee > market.peerAvgFee + 0.05;
    }

    /** The track name used to group the allocation chart. */
    public String trackLabel() {
        if (market != null && !market.specialization.isEmpty()) {
            return market.subSpecialization.isEmpty() ? market.specialization
                    : market.specialization + " · " + market.subSpecialization;
        }
        if (track != null && !track.trim().isEmpty()) return track.trim();
        return kindLabel(kind);
    }

    public String displayName() {
        if (name != null && !name.trim().isEmpty()) return name.trim();
        if (market != null && !market.name.isEmpty()) return market.name;
        return fundId > 0 ? "קופה " + fundId : kindLabel(kind);
    }

    public static String kindLabel(String kind) {
        if (kind == null) return "אחר";
        switch (kind) {
            case KIND_PENSION: return "קרן פנסיה";
            case KIND_GEMEL: return "קופת גמל";
            case KIND_HISHTALMUT: return "קרן השתלמות";
            case KIND_INVEST: return "גמל להשקעה";
            case KIND_CHILD: return "חיסכון לכל ילד";
            case KIND_POLICY: return "ביטוח מנהלים / פוליסה";
            default: return "אחר";
        }
    }

    /** Which data.gov.il dataset to look in first. */
    public static String sourceForKind(String kind) {
        if (KIND_PENSION.equals(kind)) return "pension";
        if (KIND_POLICY.equals(kind)) return "bituach";
        return "gemel";
    }

    /** Guesses the kind from a product type text such as "קרן השתלמות" or "פוליסת ביטוח מנהלים". */
    public static String kindFromText(String text) {
        String t = text == null ? "" : text;
        if (t.contains("פנסי")) return KIND_PENSION;
        if (t.contains("השתלמות")) return KIND_HISHTALMUT;
        if (t.contains("להשקעה")) return KIND_INVEST;
        if (t.contains("ילד")) return KIND_CHILD;
        if (t.contains("ביטוח") || t.contains("פוליס") || t.contains("מנהלים")) return KIND_POLICY;
        if (t.contains("גמל") || t.contains("תגמולים") || t.contains("פיצויים")) return KIND_GEMEL;
        return KIND_OTHER;
    }

    /** "202610" -> "10/2026". */
    public static String periodLabel(int period) {
        if (period <= 0) return "";
        return String.format(Locale.US, "%02d/%d", period % 100, period / 100);
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    static double num(Object o, double fallback) {
        if (o instanceof Number) return ((Number) o).doubleValue();
        if (o instanceof String) {
            try {
                return Double.parseDouble(((String) o).replace(",", "").trim());
            } catch (NumberFormatException ignored) { }
        }
        return fallback;
    }
}
