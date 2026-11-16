package com.mogomarket.app;

import java.util.ArrayList;
import java.util.List;

public class TradeCsv {

    public static String exportTrades(List<StockData> trades) {
        StringBuilder sb = new StringBuilder();
        sb.append("\"id\",\"symbol\",\"buy_price\",\"sell_price\",\"amount\",\"entry_fees\",\"exit_fees\",\"taxes\",\"exit_reason\",\"strategy\",\"notes\",\"opened_at\",\"closed_at\",\"missed_pnl\",\"sector\"\n");
        if (trades != null) {
            for (StockData t : trades) {
                sb.append("\"").append(escape(t.closedTradeId)).append("\",");
                sb.append("\"").append(escape(t.symbol)).append("\",");
                sb.append("\"").append(t.buyPrice).append("\",");
                sb.append("\"").append(t.sellPrice).append("\",");
                sb.append("\"").append(t.tradeAmount).append("\",");
                sb.append("\"").append(t.entryFees).append("\",");
                sb.append("\"").append(t.exitFees).append("\",");
                sb.append("\"").append(t.taxes).append("\",");
                sb.append("\"").append(escape(t.exitReason)).append("\",");
                sb.append("\"").append(escape(t.strategyTag)).append("\",");
                sb.append("\"").append(escape(t.notes)).append("\",");
                sb.append("\"").append(t.openedAt).append("\",");
                sb.append("\"").append(t.closedAt).append("\",");
                sb.append("\"").append(t.missedProfitLoss).append("\",");
                sb.append("\"").append(escape(t.sector)).append("\"\n");
            }
        }
        return sb.toString();
    }

    public static List<StockData> importTrades(String csv) {
        List<StockData> list = new ArrayList<>();
        if (csv == null || csv.trim().isEmpty()) return list;

        List<List<String>> records = parseCsvRecords(csv);
        if (records.size() <= 1) return list;

        for (int i = 1; i < records.size(); i++) {
            List<String> cols = records.get(i);
            if (cols.size() < 5) continue;
            StockData trade = new StockData(cols.get(1), (float) parseDouble(cols.get(2)), (float) parseDouble(cols.get(3)));
            trade.closedTradeId = cols.get(0);
            trade.sellPrice = parseDouble(cols.get(3));
            trade.tradeAmount = parseDouble(cols.get(4));
            if (cols.size() > 5) trade.entryFees = parseDouble(cols.get(5));
            if (cols.size() > 6) trade.exitFees = parseDouble(cols.get(6));
            if (cols.size() > 7) trade.taxes = parseDouble(cols.get(7));
            if (cols.size() > 8) trade.exitReason = cols.get(8);
            if (cols.size() > 9) trade.strategyTag = cols.get(9);
            if (cols.size() > 10) trade.notes = cols.get(10);
            if (cols.size() > 11) trade.openedAt = parseLong(cols.get(11));
            if (cols.size() > 12) trade.closedAt = parseLong(cols.get(12));
            if (cols.size() > 13) trade.missedProfitLoss = parseDouble(cols.get(13));
            if (cols.size() > 14 && !cols.get(14).isEmpty()) {
                trade.sector = cols.get(14);
            } else {
                trade.sector = "Other";
            }
            list.add(trade);
        }
        return list;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\"\"");
    }

    private static double parseDouble(String s) {
        try {
            return Double.parseDouble(s);
        } catch (Exception e) {
            return 0.0;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return 0L;
        }
    }

    private static List<List<String>> parseCsvRecords(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> currentRecord = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                currentRecord.add(sb.toString());
                sb.setLength(0);
            } else if ((c == '\n' || c == '\r') && !inQuotes) {
                if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                currentRecord.add(sb.toString());
                sb.setLength(0);
                if (!currentRecord.isEmpty() && !(currentRecord.size() == 1 && currentRecord.get(0).isEmpty())) {
                    records.add(currentRecord);
                }
                currentRecord = new ArrayList<>();
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0 || !currentRecord.isEmpty()) {
            currentRecord.add(sb.toString());
            records.add(currentRecord);
        }
        return records;
    }
}
