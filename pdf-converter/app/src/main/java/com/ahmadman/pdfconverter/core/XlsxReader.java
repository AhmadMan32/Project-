package com.ahmadman.pdfconverter.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/** Reads every sheet of an Excel (.xlsx) workbook into tables (streaming, for big sheets). */
public final class XlsxReader {

    /** Rendering limits: huge sheets are cut so the PDF stays usable. */
    public static final int MAX_ROWS = 5000;
    public static final int MAX_COLS = 60;

    private final Map<String, byte[]> zip;
    private final List<String> shared = new ArrayList<>();
    private final List<Boolean> dateStyles = new ArrayList<>();

    private XlsxReader(Map<String, byte[]> zip) {
        this.zip = zip;
    }

    public static Doc read(InputStream in) throws IOException {
        Map<String, byte[]> zip = Zip.readAll(in);
        if (!zip.containsKey("xl/workbook.xml")) {
            throw new IOException("not an Excel workbook");
        }
        XlsxReader r = new XlsxReader(zip);
        Doc doc = new Doc();
        try {
            r.loadSharedStrings();
            r.loadStyles();
            Map<String, String> rels = Zip.rels(zip, "xl/workbook.xml");
            Document wb = Xml.parse(zip.get("xl/workbook.xml"));
            boolean first = true;
            for (Element sheet : Xml.descendants(wb, "sheet")) {
                if ("hidden".equals(sheet.getAttribute("state")) || "veryHidden".equals(sheet.getAttribute("state"))) {
                    continue;
                }
                String path = rels.get(Xml.rel(sheet, "id"));
                byte[] data = path == null ? null : zip.get(path);
                if (data == null) {
                    continue;
                }
                Doc.Table t = new Doc.Table();
                t.caption = sheet.getAttribute("name");
                t.header = true;
                t.rows.addAll(r.sheet(data));
                if (t.rows.isEmpty()) {
                    continue;
                }
                if (!first) {
                    doc.blocks.add(new Doc.PageBreak());
                }
                first = false;
                doc.blocks.add(t);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("broken Excel workbook", e);
        }
        return doc;
    }

    private void loadSharedStrings() throws Exception {
        byte[] data = zip.get("xl/sharedStrings.xml");
        if (data == null) {
            return;
        }
        sax(data, new DefaultHandler() {
            private final StringBuilder sb = new StringBuilder();
            private boolean inT;
            private boolean inPhonetic;

            @Override
            public void startElement(String uri, String local, String qName, Attributes a) {
                String n = name(local, qName);
                if (n.equals("si")) {
                    sb.setLength(0);
                } else if (n.equals("t")) {
                    inT = true;
                } else if (n.equals("rPh")) {
                    inPhonetic = true;
                }
            }

            @Override
            public void endElement(String uri, String local, String qName) {
                String n = name(local, qName);
                if (n.equals("si")) {
                    shared.add(sb.toString());
                } else if (n.equals("t")) {
                    inT = false;
                } else if (n.equals("rPh")) {
                    inPhonetic = false;
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inT && !inPhonetic) {
                    sb.append(ch, start, length);
                }
            }
        });
    }

    /** Marks cell styles whose number format is a date so serial numbers can be shown as dates. */
    private void loadStyles() {
        byte[] data = zip.get("xl/styles.xml");
        if (data == null) {
            return;
        }
        try {
            Document d = Xml.parse(data);
            Map<Long, String> custom = new HashMap<>();
            for (Element f : Xml.descendants(d, "numFmt")) {
                custom.put(Xml.attrLong(f, "numFmtId", -1), f.getAttribute("formatCode"));
            }
            Element cellXfs = Xml.descendants(d, "cellXfs").isEmpty() ? null : Xml.descendants(d, "cellXfs").get(0);
            for (Element xf : Xml.children(cellXfs, "xf")) {
                long id = Xml.attrLong(xf, "numFmtId", 0);
                boolean date = (id >= 14 && id <= 22) || (id >= 45 && id <= 47);
                String code = custom.get(id);
                if (code != null) {
                    String c = code.replaceAll("\"[^\"]*\"|\\[[^]]*]", "").toLowerCase(Locale.ROOT);
                    date = c.contains("y") || c.contains("d") || (c.contains("m") && c.contains("h"));
                }
                dateStyles.add(date);
            }
        } catch (Exception ignored) {
            // without styles dates stay numbers
        }
    }

    private List<List<String>> sheet(byte[] data) throws Exception {
        final TreeMap<Integer, TreeMap<Integer, String>> cells = new TreeMap<>();
        sax(data, new DefaultHandler() {
            private String ref;
            private String type;
            private int style;
            private final StringBuilder value = new StringBuilder();
            private boolean inValue;
            private int nextRow = 1;
            private int nextCol;
            private int row;

            @Override
            public void startElement(String uri, String local, String qName, Attributes a) {
                String n = name(local, qName);
                switch (n) {
                    case "row":
                        String r = a.getValue("r");
                        row = r != null ? parseInt(r, nextRow) : nextRow;
                        nextRow = row + 1;
                        nextCol = 0;
                        break;
                    case "c":
                        ref = a.getValue("r");
                        type = a.getValue("t");
                        style = parseInt(a.getValue("s"), 0);
                        value.setLength(0);
                        break;
                    case "v":
                    case "t":
                        inValue = true;
                        break;
                    default:
                        break;
                }
            }

            @Override
            public void endElement(String uri, String local, String qName) {
                String n = name(local, qName);
                if (n.equals("v") || n.equals("t")) {
                    inValue = false;
                } else if (n.equals("c")) {
                    int col = ref != null ? OfficeCommon.columnIndex(ref) : nextCol;
                    int r = ref != null && OfficeCommon.rowNumber(ref) > 0 ? OfficeCommon.rowNumber(ref) : row;
                    nextCol = col + 1;
                    if (r > MAX_ROWS || col >= MAX_COLS || col < 0) {
                        return;
                    }
                    String text = format(value.toString(), type, style);
                    if (!text.isEmpty()) {
                        TreeMap<Integer, String> rowCells = cells.get(r);
                        if (rowCells == null) {
                            rowCells = new TreeMap<>();
                            cells.put(r, rowCells);
                        }
                        rowCells.put(col, text);
                    }
                }
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inValue) {
                    value.append(ch, start, length);
                }
            }
        });
        List<List<String>> rows = new ArrayList<>();
        if (cells.isEmpty()) {
            return rows;
        }
        int maxCol = 0;
        for (TreeMap<Integer, String> r : cells.values()) {
            maxCol = Math.max(maxCol, r.lastKey());
        }
        int firstRow = cells.firstKey();
        int lastRow = cells.lastKey();
        for (int r = firstRow; r <= lastRow; r++) {
            TreeMap<Integer, String> src = cells.get(r);
            List<String> row = new ArrayList<>();
            for (int c = 0; c <= maxCol; c++) {
                String v = src == null ? null : src.get(c);
                row.add(v == null ? "" : v);
            }
            rows.add(row);
        }
        return rows;
    }

    private String format(String raw, String type, int style) {
        if (type == null || type.equals("n")) {
            if (raw.isEmpty()) {
                return "";
            }
            try {
                double d = Double.parseDouble(raw);
                if (style >= 0 && style < dateStyles.size() && dateStyles.get(style) && d > 0 && d < 2958466) {
                    return excelDate(d);
                }
                return number(d);
            } catch (NumberFormatException e) {
                return raw;
            }
        }
        switch (type) {
            case "s":
                int i = parseInt(raw.trim(), -1);
                return i >= 0 && i < shared.size() ? shared.get(i) : "";
            case "b":
                return raw.trim().equals("1") ? "DOĞRU" : "YANLIŞ";
            default:
                return raw; // inlineStr, str (formula result), e (error)
        }
    }

    static String number(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return Long.toString((long) d);
        }
        String s = String.format(Locale.ROOT, "%.10f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',');
    }

    static String excelDate(double serial) {
        long days = (long) Math.floor(serial);
        double frac = serial - days;
        // Excel's day 0 is 1899-12-30 (because of the 1900 leap year bug).
        long millis = (days - 25569) * 86400000L + Math.round(frac * 86400000.0);
        SimpleDateFormat f = new SimpleDateFormat(frac > 1e-9 ? "dd.MM.yyyy HH:mm" : "dd.MM.yyyy", Locale.ROOT);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date(millis));
    }

    private static int parseInt(String s, int def) {
        if (s == null) {
            return def;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String name(String local, String qName) {
        if (local != null && !local.isEmpty()) {
            return local;
        }
        int colon = qName.indexOf(':');
        return colon >= 0 ? qName.substring(colon + 1) : qName;
    }

    private static void sax(byte[] data, DefaultHandler h) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(true);
        SAXParser p = f.newSAXParser();
        p.parse(new ByteArrayInputStream(data), h);
    }
}
