package com.mogomarket.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Turns a downloaded spreadsheet into plain text cells, without any Excel library.
 * Understands .xlsx (all sheets), CSV / TSV (UTF-8 or Windows Hebrew), and HTML tables saved
 * with an .xls name (what many Israeli government sites export). Old binary .xls is refused
 * with {@link UnsupportedFormatException} so the app can ask for xlsx or CSV instead.
 */
public final class FundFileReader {

    /** One sheet: rows of cell text ("" for empty cells). */
    public static final class Sheet {
        public final String name;
        public final List<List<String>> rows;

        Sheet(String name, List<List<String>> rows) {
            this.name = name;
            this.rows = rows;
        }
    }

    public static final class UnsupportedFormatException extends IOException {
        UnsupportedFormatException(String message) {
            super(message);
        }
    }

    private static final int MAX_BYTES = 20 * 1024 * 1024;

    private FundFileReader() {}

    public static List<Sheet> read(InputStream in) throws IOException {
        return read(readAll(in));
    }

    public static List<Sheet> read(byte[] data) throws IOException {
        if (data.length >= 4 && data[0] == 'P' && data[1] == 'K') {
            return readXlsx(data);
        }
        if (data.length >= 4 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF
                && (data[2] & 0xFF) == 0x11 && (data[3] & 0xFF) == 0xE0) {
            throw new UnsupportedFormatException("old-xls");
        }
        String text = decode(data);
        String head = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (head.startsWith("<") && (head.contains("<table") || head.contains("<tr"))) {
            return readHtml(text);
        }
        List<Sheet> sheets = new ArrayList<>();
        sheets.add(new Sheet("csv", readDelimited(text)));
        return sheets;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            if (out.size() > MAX_BYTES) throw new IOException("File is too large");
        }
        return out.toByteArray();
    }

    /** UTF-8 when the bytes are valid UTF-8, otherwise Windows-1255 (Hebrew Excel CSV). */
    static String decode(byte[] data) {
        int start = data.length >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF ? 3 : 0;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(data, start, data.length - start))
                    .toString();
        } catch (CharacterCodingException e) {
            Charset hebrew;
            try {
                hebrew = Charset.forName("windows-1255");
            } catch (Exception unsupported) {
                hebrew = StandardCharsets.ISO_8859_1;
            }
            return new String(data, start, data.length - start, hebrew);
        }
    }

    // ---- CSV / TSV --------------------------------------------------------------------------

    static List<List<String>> readDelimited(String text) {
        String firstLine = text.split("\r?\n", 2)[0];
        char sep = ',';
        int commas = count(firstLine, ','), tabs = count(firstLine, '\t'), semis = count(firstLine, ';');
        if (tabs > commas && tabs >= semis) sep = '\t';
        else if (semis > commas) sep = ';';

        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"' && cell.length() == 0) {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString().trim());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString().trim());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString().trim());
            rows.add(row);
        }
        return rows;
    }

    private static int count(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        return n;
    }

    // ---- HTML table ("xls" files that are really web pages) -------------------------------

    private static final Pattern TABLE = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t([dh])([^>]*)>(.*?)</t[dh]>");
    private static final Pattern COLSPAN = Pattern.compile("(?i)colspan\\s*=\\s*[\"']?(\\d+)");

    static List<Sheet> readHtml(String html) {
        List<Sheet> sheets = new ArrayList<>();
        Matcher tables = TABLE.matcher(html);
        int index = 0;
        while (tables.find()) {
            sheets.add(new Sheet("table" + (++index), htmlRows(tables.group(1))));
        }
        if (sheets.isEmpty()) sheets.add(new Sheet("table", htmlRows(html)));
        return sheets;
    }

    private static List<List<String>> htmlRows(String html) {
        List<List<String>> rows = new ArrayList<>();
        Matcher r = ROW.matcher(html);
        while (r.find()) {
            List<String> row = new ArrayList<>();
            Matcher c = CELL.matcher(r.group(1));
            while (c.find()) {
                String text = htmlText(c.group(3));
                row.add(text);
                Matcher span = COLSPAN.matcher(c.group(2));
                if (span.find()) {
                    int extra = Math.min(50, Integer.parseInt(span.group(1))) - 1;
                    for (int i = 0; i < extra; i++) row.add("");
                }
            }
            rows.add(row);
        }
        return rows;
    }

    private static String htmlText(String s) {
        String t = s.replaceAll("(?is)<br\\s*/?>", " ").replaceAll("(?s)<[^>]*>", "");
        t = t.replace("&nbsp;", " ").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        Matcher m = Pattern.compile("&#(\\d+);").matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int code = Integer.parseInt(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(new String(Character.toChars(code))));
        }
        m.appendTail(out);
        return out.toString().replace('\u00A0', ' ').trim().replaceAll("\\s+", " ");
    }

    // ---- XLSX -------------------------------------------------------------------------------

    static List<Sheet> readXlsx(byte[] data) throws IOException {
        Map<String, byte[]> parts = new TreeMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("xl/sharedStrings.xml") || name.equals("xl/workbook.xml")
                        || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"))) {
                    parts.put(name, readAll(zip));
                }
            }
        }
        List<String> shared = new ArrayList<>();
        if (parts.containsKey("xl/sharedStrings.xml")) {
            Document doc = parse(parts.get("xl/sharedStrings.xml"));
            NodeList items = doc.getElementsByTagName("si");
            for (int i = 0; i < items.getLength(); i++) {
                shared.add(textOfTags((Element) items.item(i), "t"));
            }
        }
        List<String> sheetNames = new ArrayList<>();
        if (parts.containsKey("xl/workbook.xml")) {
            NodeList list = parse(parts.get("xl/workbook.xml")).getElementsByTagName("sheet");
            for (int i = 0; i < list.getLength(); i++) {
                sheetNames.add(((Element) list.item(i)).getAttribute("name"));
            }
        }
        List<Sheet> sheets = new ArrayList<>();
        int index = 0;
        // TreeMap keeps sheet1, sheet2... in name order; sheet10+ is rare in these exports.
        for (Map.Entry<String, byte[]> part : parts.entrySet()) {
            if (!part.getKey().startsWith("xl/worksheets/")) continue;
            String name = index < sheetNames.size() ? sheetNames.get(index) : part.getKey();
            sheets.add(new Sheet(name, xlsxRows(parse(part.getValue()), shared)));
            index++;
        }
        return sheets;
    }

    private static List<List<String>> xlsxRows(Document doc, List<String> shared) {
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();
        NodeList rowNodes = doc.getElementsByTagName("row");
        int nextRow = 1;
        for (int r = 0; r < rowNodes.getLength(); r++) {
            Element row = (Element) rowNodes.item(r);
            int rowIndex = parseIntOr(row.getAttribute("r"), nextRow);
            nextRow = rowIndex + 1;
            TreeMap<Integer, String> cells = new TreeMap<>();
            NodeList cellNodes = row.getElementsByTagName("c");
            int nextCol = 0;
            for (int c = 0; c < cellNodes.getLength(); c++) {
                Element cell = (Element) cellNodes.item(c);
                int col = columnIndex(cell.getAttribute("r"), nextCol);
                nextCol = col + 1;
                String type = cell.getAttribute("t");
                String value;
                if ("inlineStr".equals(type)) {
                    value = textOfTags(cell, "t");
                } else {
                    String v = textOfTags(cell, "v");
                    if ("s".equals(type)) {
                        int i = parseIntOr(v.trim(), -1);
                        value = i >= 0 && i < shared.size() ? shared.get(i) : "";
                    } else {
                        value = v;
                    }
                }
                cells.put(col, value.trim());
            }
            grid.put(rowIndex, cells);
        }
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<Integer, TreeMap<Integer, String>> e : grid.entrySet()) {
            while (rows.size() < e.getKey() - 1) rows.add(new ArrayList<>());
            List<String> row = new ArrayList<>();
            for (Map.Entry<Integer, String> cell : e.getValue().entrySet()) {
                while (row.size() < cell.getKey()) row.add("");
                row.add(cell.getValue());
            }
            rows.add(row);
        }
        return rows;
    }

    /** "B12" -> 1 (zero-based column). */
    static int columnIndex(String ref, int fallback) {
        if (ref == null || ref.isEmpty()) return fallback;
        int col = 0;
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) {
            col = col * 26 + (Character.toUpperCase(ref.charAt(i)) - 'A' + 1);
            i++;
        }
        return i == 0 ? fallback : col - 1;
    }

    private static int parseIntOr(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String textOfTags(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            // Skip phonetic runs (<rPh>), which repeat the text in another script.
            if (n.getParentNode() != null && "rPh".equals(n.getParentNode().getNodeName())) continue;
            sb.append(n.getTextContent());
        }
        return sb.toString();
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            f.setExpandEntityReferences(false);
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception ignored) {
                // Not supported on every platform parser.
            }
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml));
        } catch (Exception e) {
            throw new IOException("Could not read the spreadsheet", e);
        }
    }
}
