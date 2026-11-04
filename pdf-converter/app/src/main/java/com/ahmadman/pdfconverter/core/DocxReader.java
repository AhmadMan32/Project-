package com.ahmadman.pdfconverter.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads Word (.docx) text, formatting, lists, tables and pictures into a {@link Doc}. */
public final class DocxReader {

    private final Map<String, byte[]> zip;
    private final Map<String, String> rels;
    private final Map<String, Integer> headingStyles = new HashMap<>();
    /** styleId -> numId for list styles such as "List Bullet". */
    private final Map<String, String> styleNumbering = new HashMap<>();
    private final Map<String, Integer> listCounters = new HashMap<>();
    /** "numId:level" -> numFmt (bullet, decimal, lowerLetter ...). */
    private final Map<String, String> numberFormats = new HashMap<>();
    private final Doc doc = new Doc();
    private boolean pendingBreak;

    private DocxReader(Map<String, byte[]> zip) {
        this.zip = zip;
        this.rels = Zip.rels(zip, "word/document.xml");
    }

    public static Doc read(InputStream in) throws IOException {
        Map<String, byte[]> zip = Zip.readAll(in);
        byte[] main = zip.get("word/document.xml");
        if (main == null) {
            throw new IOException("not a Word document");
        }
        DocxReader r = new DocxReader(zip);
        try {
            r.loadStyles();
            r.loadNumbering();
            Document d = Xml.parse(main);
            Element body = Xml.child(d.getDocumentElement(), "body");
            r.blocks(body);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("broken Word document", e);
        }
        return r.doc;
    }

    private void loadStyles() {
        byte[] data = zip.get("word/styles.xml");
        if (data == null) {
            return;
        }
        try {
            Document d = Xml.parse(data);
            for (Element s : Xml.descendants(d, "style")) {
                String id = Xml.attr(s, "styleId");
                Element nameEl = Xml.child(s, "name");
                String name = nameEl == null ? "" : String.valueOf(Xml.attr(nameEl, "val")).toLowerCase(Locale.ROOT);
                int level = 0;
                if (name.startsWith("heading ")) {
                    try {
                        level = Integer.parseInt(name.substring(8).trim());
                    } catch (NumberFormatException ignored) {
                        level = 0;
                    }
                } else if (name.equals("title")) {
                    level = 1;
                } else if (name.equals("subtitle")) {
                    level = 2;
                }
                Element outline = Xml.path(s, "pPr", "outlineLvl");
                if (level == 0 && outline != null) {
                    level = (int) Xml.attrLong(outline, "val", -1) + 1;
                }
                if (id != null && level > 0) {
                    headingStyles.put(id, Math.min(6, level));
                }
                Element styleNum = Xml.path(s, "pPr", "numPr", "numId");
                if (id != null && styleNum != null) {
                    styleNumbering.put(id, Xml.attr(styleNum, "val"));
                }
            }
        } catch (Exception ignored) {
            // styles are optional
        }
    }

    private void loadNumbering() {
        byte[] data = zip.get("word/numbering.xml");
        if (data == null) {
            return;
        }
        try {
            Document d = Xml.parse(data);
            Map<String, Map<Long, String>> abstracts = new HashMap<>();
            for (Element a : Xml.descendants(d, "abstractNum")) {
                Map<Long, String> levels = new HashMap<>();
                for (Element lvl : Xml.children(a, "lvl")) {
                    levels.put(Xml.attrLong(lvl, "ilvl", 0), Xml.attr(Xml.child(lvl, "numFmt"), "val"));
                }
                abstracts.put(Xml.attr(a, "abstractNumId"), levels);
            }
            for (Element num : Xml.descendants(d, "num")) {
                Map<Long, String> levels = abstracts.get(Xml.attr(Xml.child(num, "abstractNumId"), "val"));
                if (levels == null) {
                    continue;
                }
                for (Map.Entry<Long, String> e : levels.entrySet()) {
                    numberFormats.put(Xml.attr(num, "numId") + ":" + e.getKey(), e.getValue());
                }
            }
        } catch (Exception ignored) {
            // numbering is optional: lists become bullets
        }
    }

    private void blocks(Element container) {
        if (container == null) {
            return;
        }
        for (Element e : Xml.children(container, null)) {
            String n = Xml.local(e);
            switch (n) {
                case "p":
                    paragraph(e);
                    break;
                case "tbl":
                    table(e);
                    break;
                case "sdt":
                    blocks(Xml.child(e, "sdtContent"));
                    break;
                default:
                    break;
            }
        }
    }

    private void paragraph(Element p) {
        Doc.Para para = new Doc.Para();
        Element pPr = Xml.child(p, "pPr");
        if (pPr != null) {
            Element style = Xml.child(pPr, "pStyle");
            if (style != null) {
                String id = Xml.attr(style, "val");
                Integer h = headingStyles.get(id);
                if (h != null) {
                    para.heading = h;
                } else if (id != null) {
                    String l = id.toLowerCase(Locale.ROOT);
                    if (l.matches("heading\\s?[1-6]")) {
                        para.heading = l.charAt(l.length() - 1) - '0';
                    } else if (l.equals("title")) {
                        para.heading = 1;
                    } else if (l.contains("quote")) {
                        para.quote = true;
                    }
                }
            }
            Element outline = Xml.child(pPr, "outlineLvl");
            if (para.heading == 0 && outline != null) {
                long lv = Xml.attrLong(outline, "val", 9);
                if (lv < 6) {
                    para.heading = (int) lv + 1;
                }
            }
            Element jc = Xml.child(pPr, "jc");
            if (jc != null) {
                String v = String.valueOf(Xml.attr(jc, "val"));
                para.align = v.equals("center") ? Doc.ALIGN_CENTER
                        : (v.equals("right") || v.equals("end")) ? Doc.ALIGN_RIGHT
                        : (v.equals("both") || v.equals("distribute")) ? Doc.ALIGN_JUSTIFY : Doc.ALIGN_LEFT;
            }
            Element numPr = Xml.child(pPr, "numPr");
            String styleId = Xml.attr(Xml.child(pPr, "pStyle"), "val");
            String numIdVal = numPr != null ? Xml.attr(Xml.child(numPr, "numId"), "val")
                    : styleId != null ? styleNumbering.get(styleId) : null;
            if (numIdVal != null) {
                Element ilvl = Xml.child(numPr, "ilvl");
                para.indentLevel = (int) Xml.attrLong(ilvl, "val", 0);
                String key = numIdVal + ":" + para.indentLevel;
                if (!"0".equals(numIdVal)) {
                    Integer prev = listCounters.get(key);
                    int count = (prev == null ? 0 : prev) + 1;
                    listCounters.put(key, count);
                    String fmt = numberFormats.get(key);
                    if (fmt == null || fmt.equals("bullet") || fmt.equals("none")) {
                        para.bullet = true;
                    } else {
                        para.number = count;
                    }
                }
            }
            if (Xml.child(pPr, "pageBreakBefore") != null) {
                pendingBreak = true;
            }
        }
        para.pageBreakBefore = pendingBreak;
        pendingBreak = false;
        runs(p, para);
        doc.blocks.add(para);
    }

    /** Collects runs in document order, descending into hyperlinks, insertions, fields ... */
    private void runs(Node parent, Doc.Para para) {
        NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element e = (Element) n;
            String name = Xml.local(e);
            switch (name) {
                case "r":
                    run(e, para);
                    break;
                case "hyperlink":
                case "ins":
                case "smartTag":
                case "fldSimple":
                case "customXml":
                case "moveTo":
                    runs(e, para);
                    break;
                case "sdt":
                    runs(Xml.child(e, "sdtContent") == null ? e : Xml.child(e, "sdtContent"), para);
                    break;
                default:
                    break; // pPr, del, bookmarks, proofErr ...
            }
        }
    }

    private void run(Element r, Doc.Para para) {
        Element rPr = Xml.child(r, "rPr");
        boolean bold = on(Xml.child(rPr, "b"));
        boolean italic = on(Xml.child(rPr, "i"));
        Element u = Xml.child(rPr, "u");
        boolean underline = u != null && !"none".equals(Xml.attr(u, "val"));
        boolean strike = on(Xml.child(rPr, "strike"));
        float size = Xml.attrLong(Xml.child(rPr, "sz"), "val", 0) / 2f;
        int color = -1;
        Element c = Xml.child(rPr, "color");
        if (c != null) {
            color = parseColor(Xml.attr(c, "val"));
        }
        StringBuilder text = new StringBuilder();
        for (Element e : Xml.children(r, null)) {
            String n = Xml.local(e);
            switch (n) {
                case "t":
                    text.append(e.getTextContent());
                    break;
                case "tab":
                    text.append('\t');
                    break;
                case "br":
                case "cr":
                    if ("page".equals(Xml.attr(e, "type"))) {
                        flush(para, text, bold, italic, underline, strike, size, color);
                        pendingBreak = true;
                    } else {
                        text.append('\n');
                    }
                    break;
                case "noBreakHyphen":
                    text.append('-');
                    break;
                case "sym":
                    text.append('•');
                    break;
                case "drawing":
                case "pict":
                case "object":
                    flush(para, text, bold, italic, underline, strike, size, color);
                    image(e);
                    break;
                default:
                    break; // instrText, fldChar, lastRenderedPageBreak ...
            }
        }
        flush(para, text, bold, italic, underline, strike, size, color);
    }

    private static void flush(Doc.Para para, StringBuilder text, boolean bold, boolean italic,
                              boolean underline, boolean strike, float size, int color) {
        if (text.length() == 0) {
            return;
        }
        Doc.Run run = new Doc.Run(text.toString());
        run.bold = bold;
        run.italic = italic;
        run.underline = underline;
        run.strike = strike;
        run.size = size;
        run.color = color;
        para.runs.add(run);
        text.setLength(0);
    }

    private void image(Element drawing) {
        String rid = null;
        List<Element> blips = Xml.descendants(drawing, "blip");
        if (!blips.isEmpty()) {
            rid = Xml.rel(blips.get(0), "embed");
        } else {
            List<Element> legacy = Xml.descendants(drawing, "imagedata");
            if (!legacy.isEmpty()) {
                rid = Xml.rel(legacy.get(0), "id");
            }
        }
        if (rid == null) {
            return;
        }
        String path = rels.get(rid);
        byte[] data = path == null ? null : zip.get(path);
        if (data == null) {
            return;
        }
        Doc.Image img = new Doc.Image(data);
        List<Element> extents = Xml.descendants(drawing, "extent");
        if (!extents.isEmpty()) {
            img.width = Xml.attrLong(extents.get(0), "cx", 0) / 12700f;
            img.height = Xml.attrLong(extents.get(0), "cy", 0) / 12700f;
        }
        doc.blocks.add(img);
    }

    private void table(Element tbl) {
        Doc.Table t = new Doc.Table();
        for (Element tr : Xml.children(tbl, "tr")) {
            java.util.List<String> row = new java.util.ArrayList<>();
            for (Element tc : Xml.children(tr, "tc")) {
                StringBuilder cell = new StringBuilder();
                for (Element p : Xml.descendants(tc, "p")) {
                    Doc.Para tmp = new Doc.Para();
                    runs(p, tmp);
                    if (cell.length() > 0) {
                        cell.append('\n');
                    }
                    cell.append(tmp.text());
                }
                row.add(cell.toString());
                Element span = Xml.path(tc, "tcPr", "gridSpan");
                long extra = Xml.attrLong(span, "val", 1) - 1;
                for (long k = 0; k < extra && k < 50; k++) {
                    row.add("");
                }
            }
            t.rows.add(row);
        }
        t.header = Xml.descendants(tbl, "tblHeader").size() > 0;
        if (pendingBreak) {
            Doc.Para br = new Doc.Para();
            br.pageBreakBefore = true;
            doc.blocks.add(br);
            pendingBreak = false;
        }
        doc.blocks.add(t);
    }

    private static boolean on(Element flag) {
        if (flag == null) {
            return false;
        }
        String v = Xml.attr(flag, "val");
        return v == null || !(v.equals("0") || v.equals("false") || v.equals("off"));
    }

    static int parseColor(String hex) {
        if (hex == null || hex.length() != 6) {
            return -1;
        }
        try {
            int c = Integer.parseInt(hex, 16);
            return c == 0 ? -1 : c; // pure black = default
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
