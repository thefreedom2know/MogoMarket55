package com.mogomarket.app;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds savings funds in a spreadsheet exported from הר הכסף (or from a pension clearing house,
 * an insurance company, or a table the user typed). The exact column layout is not published,
 * so the header row is located by its words and every column is matched by a list of names.
 * Rows without a balance and a name/fund number, and total rows, are skipped.
 */
public final class FundImportParser {

    private FundImportParser() {}

    enum Field {
        FUND_ID, ACCOUNT, TYPE, DATE, FEE_BALANCE, FEE_DEPOSIT, DEPOSIT, STATUS,
        COMPANY, NAME, TRACK, BALANCE
    }

    /** Header names per field, most specific first. Matched after {@link #norm}. */
    private static final String[][] SYNONYMS = {
            /* FUND_ID */ {"מספר קופה", "קוד קופה", "מס קופה", "מספר אישור מס הכנסה", "מספר אישור",
                    "מס אישור", "קוד אוצר", "מספר אוצר", "מספר מסלול", "קוד מסלול", "מספר קרן",
                    "קוד קרן", "מספר הקופה", "fund id", "fund_id", "fundid"},
            /* ACCOUNT */ {"מספר חשבון", "מס חשבון", "מספר פוליסה", "מס פוליסה", "מספר פוליסה/חשבון",
                    "מספר חשבון/פוליסה", "מספר עמית", "מספר תוכנית", "מספר תכנית", "account"},
            /* TYPE */ {"סוג מוצר", "סוג המוצר", "סוג קופה", "סוג הקופה", "סוג תוכנית", "סוג תכנית",
                    "סוג חיסכון", "סוג קרן", "סוג", "מוצר", "product type", "type"},
            /* DATE */ {"תאריך נכונות", "נכון לתאריך", "תאריך נכונות הנתונים", "תאריך עדכון",
                    "תאריך יתרה", "מועד עדכון", "נכון ליום", "תאריך", "date"},
            /* FEE_BALANCE */ {"דמי ניהול מצבירה", "דמי ניהול מהצבירה", "דמי ניהול מיתרה",
                    "דמי ניהול מהיתרה", "דמי ניהול מחיסכון", "דנ מצבירה", "דמי ניהול צבירה",
                    "שיעור דמי ניהול מצבירה", "דמי ניהול מנכסים", "fee balance", "דמי ניהול"},
            /* FEE_DEPOSIT */ {"דמי ניהול מהפקדה", "דמי ניהול מהפקדות", "דמי ניהול הפקדה",
                    "דנ מהפקדה", "שיעור דמי ניהול מהפקדה", "fee deposit"},
            /* DEPOSIT */ {"הפקדה חודשית", "סך הפקדה חודשית", "הפקדה חודשית אחרונה", "הפקדות חודשיות",
                    "הפקדה אחרונה", "סכום הפקדה", "monthly deposit"},
            /* STATUS */ {"סטטוס", "סטטוס חשבון", "סטטוס פוליסה", "מצב חשבון", "מצב", "status"},
            /* COMPANY */ {"שם החברה המנהלת", "חברה מנהלת", "החברה המנהלת", "שם הגוף המנהל",
                    "הגוף המנהל", "גוף מנהל", "שם הגוף", "גוף", "שם יצרן", "יצרן", "חברה",
                    "company", "managing company"},
            /* NAME */ {"שם המוצר", "שם מוצר", "שם הקופה", "שם קופה", "שם הקרן", "שם קרן",
                    "שם התוכנית", "שם תוכנית", "שם תכנית", "שם הפוליסה", "שם", "fund name", "name"},
            /* TRACK */ {"מסלול השקעה", "מסלול ההשקעה", "שם מסלול", "שם מסלול השקעה", "מסלול", "track"},
            /* BALANCE */ {"יתרה צבורה", "סך יתרה", "יתרה כוללת", "יתרת חיסכון", "יתרה", "סכום צבור",
                    "סך חיסכון", "סך החיסכון", "חיסכון מצטבר", "סכום חיסכון", "צבירה", "סך צבירה",
                    "ערך פדיון", "סך הכל", "balance", "amount"},
    };

    /** Words that only appear in the header row; used to find it. */
    private static final String[] HEADER_HINTS = {
            "יתרה", "צבור", "חברה", "מנהלת", "קופה", "מוצר", "פוליסה", "חשבון", "מסלול",
            "דמי ניהול", "סוג", "חיסכון", "גוף", "balance", "fund"
    };

    /** Result of an import: the funds and a short explanation when nothing was found. */
    public static final class Result {
        public final List<SavingsFund> funds = new ArrayList<>();
        public int sheetsWithHeader;
        public final List<String> headersSeen = new ArrayList<>();
    }

    public static Result parse(List<FundFileReader.Sheet> sheets, int defaultPeriod) {
        Result result = new Result();
        for (FundFileReader.Sheet sheet : sheets) {
            parseSheet(sheet, defaultPeriod, result);
        }
        return result;
    }

    private static void parseSheet(FundFileReader.Sheet sheet, int defaultPeriod, Result result) {
        List<List<String>> rows = sheet.rows;
        int headerRow = -1;
        int bestScore = 1;
        for (int r = 0; r < Math.min(rows.size(), 30); r++) {
            int score = headerScore(rows.get(r));
            if (score > bestScore) {
                bestScore = score;
                headerRow = r;
            }
        }
        if (headerRow < 0) return;
        int[] cols = mapColumns(rows.get(headerRow));
        if (cols[Field.BALANCE.ordinal()] < 0
                || (cols[Field.NAME.ordinal()] < 0 && cols[Field.FUND_ID.ordinal()] < 0
                && cols[Field.COMPANY.ordinal()] < 0)) {
            return;
        }
        result.sheetsWithHeader++;
        result.headersSeen.add(String.join(" | ", rows.get(headerRow)));

        String sheetKind = SavingsFund.kindFromText(sheet.name);
        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (isBlank(row)) continue;
            // A second header (another product table on the same sheet) re-maps the columns.
            if (headerScore(row) >= bestScore && parseAmount(cell(row, cols, Field.BALANCE)) == null) {
                int[] again = mapColumns(row);
                if (again[Field.BALANCE.ordinal()] >= 0) cols = again;
                continue;
            }
            String joined = String.join(" ", row);
            if (joined.contains("סה\"כ") || joined.contains("סה״כ") || joined.contains("סהכ")
                    || norm(joined).startsWith("total")) {
                continue;
            }
            Double balance = parseAmount(cell(row, cols, Field.BALANCE));
            String name = cell(row, cols, Field.NAME);
            String company = cell(row, cols, Field.COMPANY);
            long fundId = parseFundId(cell(row, cols, Field.FUND_ID));
            if (balance == null) continue;
            if (name.isEmpty() && company.isEmpty() && fundId == 0) continue;

            SavingsFund f = new SavingsFund();
            f.fundId = fundId;
            f.name = name;
            f.company = company;
            f.account = cell(row, cols, Field.ACCOUNT);
            f.track = cell(row, cols, Field.TRACK);
            f.balance = balance;
            String typeText = cell(row, cols, Field.TYPE);
            String kind = SavingsFund.kindFromText(typeText + " " + name);
            f.kind = SavingsFund.KIND_OTHER.equals(kind) ? sheetKind : kind;
            Double feeB = parsePercent(cell(row, cols, Field.FEE_BALANCE));
            Double feeD = parsePercent(cell(row, cols, Field.FEE_DEPOSIT));
            if (feeB != null) f.feeBalance = feeB;
            if (feeD != null) f.feeDeposit = feeD;
            Double deposit = parseAmount(cell(row, cols, Field.DEPOSIT));
            if (deposit != null && deposit > 0) f.monthlyDeposit = deposit;
            int period = parsePeriod(cell(row, cols, Field.DATE));
            // A date far in the past is not the balance date (likely a joining date).
            boolean plausible = period > 0 && (defaultPeriod <= 0 || period > defaultPeriod - 200);
            f.balancePeriod = plausible ? period : defaultPeriod;
            String status = cell(row, cols, Field.STATUS);
            f.active = !(status.contains("לא פעיל") || status.contains("סגור")
                    || status.toLowerCase(Locale.ROOT).contains("inactive"));
            f.importSource = "harhakesef";
            result.funds.add(f);
        }
    }

    static int headerScore(List<String> row) {
        int score = 0;
        for (String c : row) {
            String n = norm(c);
            if (n.isEmpty() || n.length() > 40) continue;
            if (parseAmount(c) != null) return 0; // a data row, not a header
            for (String hint : HEADER_HINTS) {
                if (n.contains(hint)) {
                    score++;
                    break;
                }
            }
        }
        return score;
    }

    /** Column index of each field, or -1. Exact names win over partial matches. */
    static int[] mapColumns(List<String> header) {
        int[] cols = new int[Field.values().length];
        java.util.Arrays.fill(cols, -1);
        boolean[] used = new boolean[header.size()];
        String[] names = new String[header.size()];
        for (int i = 0; i < header.size(); i++) names[i] = norm(header.get(i));

        for (int pass = 0; pass < 2; pass++) {
            for (Field field : Field.values()) {
                if (cols[field.ordinal()] >= 0) continue;
                outer:
                for (String synonym : SYNONYMS[field.ordinal()]) {
                    String s = norm(synonym);
                    for (int i = 0; i < names.length; i++) {
                        if (used[i] || names[i].isEmpty()) continue;
                        boolean match = pass == 0 ? names[i].equals(s) : partialMatch(names[i], s, field);
                        if (match) {
                            cols[field.ordinal()] = i;
                            used[i] = true;
                            break outer;
                        }
                    }
                }
            }
        }
        return cols;
    }

    private static boolean partialMatch(String header, String synonym, Field field) {
        // A generic "date" column may be the joining date; only exact names count for it.
        if (field == Field.DATE && (synonym.equals("תאריך") || synonym.equals("date"))) return false;
        if (field == Field.FEE_BALANCE && header.contains("הפקד")) return false;
        if (field == Field.BALANCE && (header.contains("דמי ניהול") || header.contains("תאריך")
                || header.contains("הפקד"))) return false;
        // One-word synonyms ("שם", "סוג", "גוף") only match as a whole word, so "שם" does not
        // grab "שם מסלול" before the track does, and "סוג" stays away from unrelated columns.
        if (!synonym.contains(" ") && synonym.length() <= 4) {
            for (String word : header.split(" ")) if (word.equals(synonym)) return field != Field.NAME
                    || !header.contains("מסלול");
            return false;
        }
        return header.contains(synonym);
    }

    /** Lower case, no quotes/geresh/brackets/asterisks, single spaces. "ד"נ" becomes "דנ". */
    static String norm(String s) {
        if (s == null) return "";
        String t = s.toLowerCase(Locale.ROOT)
                .replaceAll("[\"'׳״`()\\[\\]*:.₪%]", "")
                .replace(' ', ' ')
                .replaceAll("[\\u200e\\u200f\\u202a-\\u202e]", "")
                .replaceAll("\\s+", " ")
                .trim();
        return t.replace("מס'", "מס");
    }

    private static String cell(List<String> row, int[] cols, Field field) {
        int i = cols[field.ordinal()];
        if (i < 0 || i >= row.size()) return "";
        String v = row.get(i);
        return v == null ? "" : v.trim();
    }

    private static boolean isBlank(List<String> row) {
        for (String c : row) if (c != null && !c.trim().isEmpty()) return false;
        return true;
    }

    private static final Pattern NUMBER = Pattern.compile("-?\\d[\\d,]*(\\.\\d+)?|-?\\.\\d+");

    /** "₪ 123,456.78", "(1,234)", "1234" -> number; text without digits -> null. */
    static Double parseAmount(String s) {
        if (s == null) return null;
        String t = s.trim().replace('−', '-');
        if (t.isEmpty()) return null;
        if (t.matches("-?\\d+(\\.\\d+)?[eE][-+]?\\d+")) return Double.parseDouble(t);
        // Plain words with a digit inside (e.g. "מסלול 50") are not amounts.
        String letters = t.replaceAll("[\\d.,\\s₪$%()\\-+]|ש\"ח|שח|ils|nis", "");
        if (!letters.isEmpty()) return null;
        boolean negative = t.startsWith("(") && t.endsWith(")") || t.startsWith("-") || t.endsWith("-");
        Matcher m = NUMBER.matcher(t.replace("-", ""));
        if (!m.find()) return null;
        try {
            double v = Double.parseDouble(m.group().replace(",", ""));
            return negative ? -v : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "0.25%", "0.25", or a spreadsheet fraction 0.0025 -> 0.25 (percent). */
    static Double parsePercent(String s) {
        Double v = parseAmount(s);
        if (v == null || v < 0) return null;
        if (!s.contains("%") && v > 0 && v < 0.03) v = v * 100.0;
        if (v > 10) return null; // not a fee
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** Fund numbers are digits only (e.g. "1234", "קופה 1234" or "1234.0" from Excel). */
    static long parseFundId(String s) {
        if (s == null) return 0;
        Matcher m = Pattern.compile("\\d{2,7}").matcher(s.replaceAll("\\.0+$", ""));
        if (!m.find()) return 0;
        try {
            return Long.parseLong(m.group());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static final Pattern DMY = Pattern.compile("(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})");
    private static final Pattern YMD = Pattern.compile("(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})");
    private static final Pattern MY = Pattern.compile("^(\\d{1,2})[./-](\\d{4})$");

    /** A date cell -> YYYYMM, or 0. Accepts 31/10/2026, 2026-10-31, 10/2026 and Excel day numbers. */
    static int parsePeriod(String s) {
        if (s == null) return 0;
        String t = s.trim();
        if (t.isEmpty()) return 0;
        Matcher m = YMD.matcher(t);
        if (m.find()) return period(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        m = DMY.matcher(t);
        if (m.find()) {
            int y = Integer.parseInt(m.group(3));
            if (y < 100) y += 2000;
            return period(y, Integer.parseInt(m.group(2)));
        }
        m = MY.matcher(t);
        if (m.find()) return period(Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        try {
            double serial = Double.parseDouble(t);
            if (serial > 20000 && serial < 80000) {
                Calendar c = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
                c.clear();
                c.set(1899, Calendar.DECEMBER, 30);
                c.add(Calendar.DAY_OF_MONTH, (int) serial);
                return period(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
            }
        } catch (NumberFormatException ignored) { }
        return 0;
    }

    private static int period(int year, int month) {
        if (year < 1990 || year > 2100 || month < 1 || month > 12) return 0;
        return year * 100 + month;
    }

    /** Last month (YYYYMM) - a fair default for balances without a date. */
    public static int previousMonth(Calendar now) {
        int y = now.get(Calendar.YEAR);
        int m = now.get(Calendar.MONTH); // 0-based, so this is already "last month"
        if (m == 0) {
            y -= 1;
            m = 12;
        }
        return y * 100 + m;
    }
}
