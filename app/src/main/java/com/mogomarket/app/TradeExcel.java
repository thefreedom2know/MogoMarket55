package com.mogomarket.app;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class TradeExcel {

    public static void exportTrades(List<StockData> trades, OutputStream out) throws IOException {
        String csv = TradeCsv.exportTrades(trades);
        out.write(csv.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public static List<StockData> importTrades(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int nRead;
        while ((nRead = in.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        String content = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        return TradeCsv.importTrades(content);
    }
}
