package com.mogomarket.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class FundImportParserTest {

    private static FundImportParser.Result parse(byte[] file) throws Exception {
        return FundImportParser.parse(FundFileReader.read(file), 202610);
    }

    @Test
    public void csvWithTitleRowsHeaderAndTotal() throws Exception {
        String csv = "דוח חסכונות - הר הכסף\n"
                + "ת.ז.,123456789\n"
                + "\n"
                + "סוג מוצר,שם החברה המנהלת,שם המוצר,מספר קופה,מספר חשבון,מסלול השקעה,"
                + "יתרה (₪),דמי ניהול מצבירה,דמי ניהול מהפקדה,תאריך נכונות,סטטוס\n"
                + "קרן השתלמות,אלטשולר שחם,אלטשולר שחם השתלמות מניות,1234,55-998,מניות,"
                + "\"125,430.50\",0.70%,0%,30/09/2026,פעיל\n"
                + "קרן פנסיה חדשה מקיפה,מגדל,מגדל מקפת אישית,7890,A1,לבני 50 ומטה,"
                + "\"310,000\",0.22%,1.5%,31/08/2026,לא פעיל\n"
                + "סה\"כ,,,,,,\"435,430.50\",,,,\n";
        FundImportParser.Result r = parse(csv.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, r.funds.size());

        SavingsFund a = r.funds.get(0);
        assertEquals(1234, a.fundId);
        assertEquals("אלטשולר שחם", a.company);
        assertEquals("אלטשולר שחם השתלמות מניות", a.name);
        assertEquals("55-998", a.account);
        assertEquals("מניות", a.track);
        assertEquals(125430.50, a.balance, 0.001);
        assertEquals(0.70, a.feeBalance, 1e-9);
        assertEquals(0.0, a.feeDeposit, 1e-9);
        assertEquals(202609, a.balancePeriod);
        assertEquals(SavingsFund.KIND_HISHTALMUT, a.kind);
        assertTrue(a.active);

        SavingsFund b = r.funds.get(1);
        assertEquals(SavingsFund.KIND_PENSION, b.kind);
        assertEquals(1.5, b.feeDeposit, 1e-9);
        assertEquals(202608, b.balancePeriod);
        assertFalse(b.active);
    }

    @Test
    public void windowsHebrewCsvWithoutFundNumberOrDate() throws Exception {
        String csv = "חברה מנהלת,סוג קופה,יתרה צבורה\r\nהראל,קופת גמל,\"12,000\"\r\n";
        FundImportParser.Result r = parse(csv.getBytes(Charset.forName("windows-1255")));
        assertEquals(1, r.funds.size());
        assertEquals("הראל", r.funds.get(0).company);
        assertEquals(0, r.funds.get(0).fundId);
        assertEquals(202610, r.funds.get(0).balancePeriod);
        assertEquals(SavingsFund.KIND_GEMEL, r.funds.get(0).kind);
    }

    @Test
    public void htmlTableSavedAsXls() throws Exception {
        String html = "<html><body><table><tr><th>שם קופה</th><th>מספר אישור</th><th>יתרה</th>"
                + "<th>תאריך הצטרפות</th></tr>"
                + "<tr><td>מור גמל כללי</td><td>1520</td><td>&#8362; 9,876</td><td>01/01/2012</td></tr>"
                + "</table></body></html>";
        FundImportParser.Result r = parse(html.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, r.funds.size());
        assertEquals(1520, r.funds.get(0).fundId);
        assertEquals(9876, r.funds.get(0).balance, 0.001);
        // The joining date is not taken as the balance date.
        assertEquals(202610, r.funds.get(0).balancePeriod);
    }

    @Test
    public void xlsxWithSharedStringsNumbersAndFractionFees() throws Exception {
        String shared = "<sst><si><t>שם המוצר</t></si><si><t>קוד קופה</t></si><si><t>יתרה</t></si>"
                + "<si><t>דמי ניהול מצבירה</t></si><si><r><t>ילין</t></r><r><t> לפידות</t></r></si></sst>";
        String sheet = "<worksheet><sheetData>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>0</v></c><c r=\"B2\" t=\"s\"><v>1</v></c>"
                + "<c r=\"C2\" t=\"s\"><v>2</v></c><c r=\"D2\" t=\"s\"><v>3</v></c></row>"
                + "<row r=\"3\"><c r=\"A3\" t=\"s\"><v>4</v></c><c r=\"B3\"><v>8801</v></c>"
                + "<c r=\"C3\"><v>45210.75</v></c><c r=\"D3\"><v>0.0052</v></c></row>"
                + "</sheetData></worksheet>";
        String workbook = "<workbook><sheets><sheet name=\"קרן השתלמות\" sheetId=\"1\"/></sheets></workbook>";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            put(zip, "xl/workbook.xml", workbook);
            put(zip, "xl/sharedStrings.xml", shared);
            put(zip, "xl/worksheets/sheet1.xml", sheet);
        }
        FundImportParser.Result r = parse(out.toByteArray());
        assertEquals(1, r.funds.size());
        SavingsFund f = r.funds.get(0);
        assertEquals("ילין לפידות", f.name);
        assertEquals(8801, f.fundId);
        assertEquals(45210.75, f.balance, 0.001);
        assertEquals(0.52, f.feeBalance, 1e-9);
        assertEquals(SavingsFund.KIND_HISHTALMUT, f.kind); // from the sheet name
    }

    @Test(expected = FundFileReader.UnsupportedFormatException.class)
    public void oldBinaryXlsIsRefused() throws Exception {
        FundFileReader.read(new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0});
    }

    @Test
    public void columnMatchingPrefersExactNames() {
        int[] cols = FundImportParser.mapColumns(List.of(
                "שם מסלול", "שם החברה המנהלת", "שם", "דמי ניהול מהפקדה", "דמי ניהול מצבירה", "יתרה"));
        assertEquals(0, cols[FundImportParser.Field.TRACK.ordinal()]);
        assertEquals(1, cols[FundImportParser.Field.COMPANY.ordinal()]);
        assertEquals(2, cols[FundImportParser.Field.NAME.ordinal()]);
        assertEquals(3, cols[FundImportParser.Field.FEE_DEPOSIT.ordinal()]);
        assertEquals(4, cols[FundImportParser.Field.FEE_BALANCE.ordinal()]);
        assertEquals(5, cols[FundImportParser.Field.BALANCE.ordinal()]);
    }

    @Test
    public void numbersDatesAndIds() {
        assertEquals(-1234.0, FundImportParser.parseAmount("(1,234)"), 1e-9);
        assertEquals(1500.0, FundImportParser.parseAmount("1,500 ש\"ח"), 1e-9);
        assertNull(FundImportParser.parseAmount("מסלול 50"));
        assertEquals(123456.0, FundImportParser.parseAmount("1.23456E5"), 1e-9);
        assertEquals(202609, FundImportParser.parsePeriod("2026-09-30"));
        assertEquals(202609, FundImportParser.parsePeriod("09/2026"));
        assertEquals(202609, FundImportParser.parsePeriod("46295")); // Excel day number, 30/09/2026
        assertEquals(1234, FundImportParser.parseFundId("1234.0"));
        assertEquals(0.25, FundImportParser.parsePercent("0.25%"), 1e-9);
    }

    @Test
    public void feeAlertComparesWithTrackAverage() {
        SavingsFund f = new SavingsFund();
        f.market = new SavingsFund.Market();
        f.market.peerAvgFee = 0.5;
        f.market.fee = 0.4;
        assertFalse(f.feeAboveAverage());   // fund average used when the own fee is unknown
        f.feeBalance = 0.8;
        assertTrue(f.feeAboveAverage());
    }

    private static void put(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
