package com.ahmadman.pdfconverter.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Minimal but valid Excel (.xlsx) writer with inline strings and real numbers. */
public final class XlsxWriter {

    private static final int MAX_ROWS = 1_048_576;
    private static final int MAX_COLS = 16_384;

    private final List<String> names = new ArrayList<>();
    private final List<String> sheets = new ArrayList<>();
    private final Set<String> used = new HashSet<>();

    /** Adds a sheet; numbers are detected with {@link TextAnalyzer#cellValue}. */
    public void addSheet(String name, List<List<String>> rows, boolean boldFirstRow) {
        names.add(uniqueName(name));
        int cols = 0;
        for (List<String> r : rows) {
            cols = Math.min(MAX_COLS, Math.max(cols, r.size()));
        }
        int[] widths = new int[cols];
        for (List<String> r : rows) {
            for (int c = 0; c < Math.min(cols, r.size()); c++) {
                String v = r.get(c);
                widths[c] = Math.max(widths[c], v == null ? 0 : Math.min(60, v.length()));
            }
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        if (cols > 0) {
            sb.append("<cols>");
            for (int c = 0; c < cols; c++) {
                double w = Math.max(8, widths[c] * 1.1 + 2);
                sb.append(String.format(Locale.ROOT, "<col min=\"%d\" max=\"%d\" width=\"%.1f\" customWidth=\"1\"/>",
                        c + 1, c + 1, w));
            }
            sb.append("</cols>");
        }
        sb.append("<sheetData>");
        int rowCount = Math.min(MAX_ROWS, rows.size());
        for (int r = 0; r < rowCount; r++) {
            List<String> row = rows.get(r);
            sb.append("<row r=\"").append(r + 1).append("\">");
            for (int c = 0; c < Math.min(cols, row.size()); c++) {
                String v = row.get(c);
                if (v == null || v.isEmpty()) {
                    continue;
                }
                String ref = OfficeCommon.column(c) + (r + 1);
                String style = boldFirstRow && r == 0 ? " s=\"1\"" : "";
                Object value = TextAnalyzer.cellValue(v);
                if (value instanceof Double) {
                    double d = (Double) value;
                    String num = d == Math.rint(d) && Math.abs(d) < 1e15
                            ? Long.toString((long) d) : Double.toString(d);
                    sb.append("<c r=\"").append(ref).append('"').append(style).append("><v>")
                            .append(num).append("</v></c>");
                } else {
                    sb.append("<c r=\"").append(ref).append('"').append(style)
                            .append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                            .append(Xml.esc((String) value)).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        sheets.add(sb.toString());
    }

    private String uniqueName(String name) {
        String n = name == null ? "" : name.replaceAll("[\\[\\]:*?/\\\\]", " ").trim();
        if (n.isEmpty()) {
            n = "Sayfa";
        }
        if (n.length() > 31) {
            n = n.substring(0, 31);
        }
        String base = n;
        int i = 2;
        while (used.contains(n.toLowerCase(Locale.ROOT))) {
            String suffix = " (" + i++ + ")";
            n = (base.length() + suffix.length() > 31 ? base.substring(0, 31 - suffix.length()) : base) + suffix;
        }
        used.add(n.toLowerCase(Locale.ROOT));
        return n;
    }

    public void write(OutputStream out) throws IOException {
        if (sheets.isEmpty()) {
            addSheet("Sayfa 1", new ArrayList<>(), false);
        }
        try (Zip.Writer z = new Zip.Writer(out)) {
            StringBuilder ct = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                    + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
            for (int i = 0; i < sheets.size(); i++) {
                ct.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
            }
            ct.append("</Types>");
            z.put("[Content_Types].xml", ct.toString());
            z.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "</Relationships>");
            z.put("docProps/core.xml", OfficeCommon.coreProps());

            StringBuilder wb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                    + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
            for (int i = 0; i < sheets.size(); i++) {
                wb.append("<sheet name=\"").append(Xml.esc(names.get(i))).append("\" sheetId=\"").append(i + 1)
                        .append("\" r:id=\"rId").append(i + 1).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(i + 1)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                        .append(i + 1).append(".xml\"/>");
                z.put("xl/worksheets/sheet" + (i + 1) + ".xml", sheets.get(i));
            }
            wb.append("</sheets></workbook>");
            rels.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
                    .append("</Relationships>");
            z.put("xl/workbook.xml", wb.toString());
            z.put("xl/_rels/workbook.xml.rels", rels.toString());
            z.put("xl/styles.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font>"
                    + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/><family val=\"2\"/></font></fonts>"
                    + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                    + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                    + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                    + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                    + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                    + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                    + "</styleSheet>");
        }
    }
}
