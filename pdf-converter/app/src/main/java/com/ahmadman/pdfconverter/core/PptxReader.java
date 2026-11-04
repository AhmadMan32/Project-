package com.ahmadman.pdfconverter.core;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads PowerPoint (.pptx) slides: positioned text boxes, pictures, tables and fills. */
public final class PptxReader {

    private static final float EMU_PER_PT = 12700f;

    private final Map<String, byte[]> zip;

    private PptxReader(Map<String, byte[]> zip) {
        this.zip = zip;
    }

    public static Doc read(InputStream in) throws IOException {
        Map<String, byte[]> zip = Zip.readAll(in);
        byte[] presData = zip.get("ppt/presentation.xml");
        if (presData == null) {
            throw new IOException("not a PowerPoint presentation");
        }
        PptxReader r = new PptxReader(zip);
        Doc doc = new Doc();
        doc.slides = new java.util.ArrayList<>();
        try {
            Document pres = Xml.parse(presData);
            Element sz = Xml.descendants(pres, "sldSz").isEmpty() ? null : Xml.descendants(pres, "sldSz").get(0);
            float w = Xml.attrLong(sz, "cx", 9144000) / EMU_PER_PT;
            float h = Xml.attrLong(sz, "cy", 6858000) / EMU_PER_PT;
            Map<String, String> rels = Zip.rels(zip, "ppt/presentation.xml");
            for (Element id : Xml.descendants(pres, "sldId")) {
                String path = rels.get(Xml.rel(id, "id"));
                if (path == null || !zip.containsKey(path)) {
                    continue;
                }
                doc.slides.add(r.slide(path, w, h));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("broken PowerPoint presentation", e);
        }
        return doc;
    }

    /** Placeholder geometry inherited from the layout and master. */
    private static final class Placeholders {
        final Map<String, float[]> byKey = new HashMap<>();

        float[] find(String type, String idx) {
            if (idx != null && byKey.containsKey("idx:" + idx)) {
                return byKey.get("idx:" + idx);
            }
            String t = type == null ? "body" : type;
            if (t.equals("ctrTitle")) {
                t = "title";
            }
            if (t.equals("subTitle")) {
                t = "body";
            }
            return byKey.get("type:" + t);
        }
    }

    private Doc.Slide slide(String path, float w, float h) throws Exception {
        Doc.Slide slide = new Doc.Slide();
        slide.width = w;
        slide.height = h;
        Document d = Xml.parse(zip.get(path));
        Map<String, String> rels = Zip.rels(zip, path);

        Placeholders ph = new Placeholders();
        String layout = relOfType(path, "slideLayout");
        String master = layout == null ? null : relOfType(layout, "slideMaster");
        Integer bg = null;
        for (String part : new String[]{master, layout}) {
            if (part == null || !zip.containsKey(part)) {
                continue;
            }
            Document pd = Xml.parse(zip.get(part));
            collectPlaceholders(pd, ph);
            Integer b = background(pd);
            if (b != null) {
                bg = b;
            }
        }
        Integer own = background(d);
        if (own != null) {
            bg = own;
        }
        if (bg != null) {
            slide.background = bg;
        }

        Element tree = Xml.descendants(d, "spTree").isEmpty() ? null : Xml.descendants(d, "spTree").get(0);
        float[] identity = {0, 0, 1, 1}; // offsetX, offsetY, scaleX, scaleY
        shapes(tree, identity, rels, ph, slide);
        return slide;
    }

    private String relOfType(String part, String typeSuffix) {
        byte[] data = zip.get(Zip.relsPath(part));
        if (data == null) {
            return null;
        }
        try {
            Document d = Xml.parse(data);
            for (Element r : Xml.descendants(d, "Relationship")) {
                if (r.getAttribute("Type").endsWith("/" + typeSuffix)) {
                    return Zip.resolve(part, r.getAttribute("Target"));
                }
            }
        } catch (Exception ignored) {
            // no relation
        }
        return null;
    }

    private static Integer background(Document d) {
        List<Element> bgs = Xml.descendants(d, "bg");
        if (bgs.isEmpty()) {
            return null;
        }
        List<Element> rgb = Xml.descendants(bgs.get(0), "srgbClr");
        if (rgb.isEmpty()) {
            return null;
        }
        int c = parseHex(Xml.attr(rgb.get(0), "val"));
        return c < 0 ? null : c;
    }

    private static void collectPlaceholders(Document d, Placeholders ph) {
        for (Element sp : Xml.descendants(d, "sp")) {
            Element p = Xml.path(sp, "nvSpPr", "nvPr", "ph");
            float[] r = xfrm(Xml.path(sp, "spPr", "xfrm"));
            if (p == null || r == null) {
                continue;
            }
            String type = Xml.attr(p, "type");
            String idx = Xml.attr(p, "idx");
            if (idx != null) {
                ph.byKey.put("idx:" + idx, r);
            }
            String t = type == null ? "body" : type.equals("ctrTitle") ? "title" : type;
            if (!ph.byKey.containsKey("type:" + t)) {
                ph.byKey.put("type:" + t, r);
            }
        }
    }

    /** {x, y, w, h} in points, or null. */
    private static float[] xfrm(Element x) {
        if (x == null) {
            return null;
        }
        Element off = Xml.child(x, "off");
        Element ext = Xml.child(x, "ext");
        if (off == null || ext == null) {
            return null;
        }
        return new float[]{Xml.attrLong(off, "x", 0) / EMU_PER_PT, Xml.attrLong(off, "y", 0) / EMU_PER_PT,
                Xml.attrLong(ext, "cx", 0) / EMU_PER_PT, Xml.attrLong(ext, "cy", 0) / EMU_PER_PT};
    }

    private void shapes(Element tree, float[] tf, Map<String, String> rels, Placeholders ph, Doc.Slide slide) {
        if (tree == null) {
            return;
        }
        for (Element e : Xml.children(tree, null)) {
            switch (Xml.local(e)) {
                case "sp":
                    shape(e, tf, ph, slide);
                    break;
                case "pic":
                    picture(e, tf, rels, slide);
                    break;
                case "graphicFrame":
                    tableFrame(e, tf, slide);
                    break;
                case "grpSp":
                    group(e, tf, rels, ph, slide);
                    break;
                case "AlternateContent":
                    Element fallback = Xml.child(e, "Fallback");
                    shapes(fallback, tf, rels, ph, slide);
                    break;
                default:
                    break;
            }
        }
    }

    private void group(Element g, float[] tf, Map<String, String> rels, Placeholders ph, Doc.Slide slide) {
        Element x = Xml.path(g, "grpSpPr", "xfrm");
        float[] child = tf;
        if (x != null) {
            float[] r = xfrm(x);
            Element chOff = Xml.child(x, "chOff");
            Element chExt = Xml.child(x, "chExt");
            if (r != null && chOff != null && chExt != null) {
                float cx = Xml.attrLong(chOff, "x", 0) / EMU_PER_PT;
                float cy = Xml.attrLong(chOff, "y", 0) / EMU_PER_PT;
                float cw = Xml.attrLong(chExt, "cx", 0) / EMU_PER_PT;
                float chh = Xml.attrLong(chExt, "cy", 0) / EMU_PER_PT;
                float sx = cw > 0 ? r[2] / cw : 1;
                float sy = chh > 0 ? r[3] / chh : 1;
                // child point p -> tf(r.x + (p - chOff) * s)
                child = new float[]{tf[0] + tf[2] * (r[0] - cx * sx), tf[1] + tf[3] * (r[1] - cy * sy),
                        tf[2] * sx, tf[3] * sy};
            }
        }
        shapes(g, child, rels, ph, slide);
    }

    private static float[] apply(float[] tf, float[] r) {
        return new float[]{tf[0] + r[0] * tf[2], tf[1] + r[1] * tf[3], r[2] * tf[2], r[3] * tf[3]};
    }

    private void shape(Element sp, float[] tf, Placeholders ph, Doc.Slide slide) {
        Doc.Shape s = new Doc.Shape();
        Element p = Xml.path(sp, "nvSpPr", "nvPr", "ph");
        String phType = p == null ? null : Xml.attr(p, "type");
        s.title = phType != null && (phType.equals("title") || phType.equals("ctrTitle"));
        float[] r = xfrm(Xml.path(sp, "spPr", "xfrm"));
        if (r == null && p != null) {
            r = ph.find(phType, Xml.attr(p, "idx"));
        }
        if (r == null) {
            return;
        }
        if (phType != null && (phType.equals("sldNum") || phType.equals("dt") || phType.equals("ftr"))) {
            // footers usually hold field placeholders without real text; keep only literal text
            if (Xml.descendants(sp, "fld").size() > 0) {
                return;
            }
        }
        r = apply(tf, r);
        s.x = r[0];
        s.y = r[1];
        s.w = r[2];
        s.h = r[3];
        Element spPr = Xml.child(sp, "spPr");
        Element fill = Xml.child(spPr, "solidFill");
        if (fill != null) {
            s.fill = colorOf(fill);
        }
        Element ln = Xml.child(spPr, "ln");
        if (ln != null && Xml.child(ln, "solidFill") != null) {
            s.line = colorOf(Xml.child(ln, "solidFill"));
        }
        s.fontSize = s.title ? 32 : (p == null ? 18 : 20);
        Element body = Xml.child(sp, "txBody");
        if (body != null) {
            Element bodyPr = Xml.child(body, "bodyPr");
            String anchor = Xml.attr(bodyPr, "anchor");
            s.vAnchor = "ctr".equals(anchor) ? 1 : "b".equals(anchor) ? 2 : (s.title ? 1 : 0);
            Element autofit = Xml.child(bodyPr, "normAutofit");
            float scale = autofit == null ? 1f : Xml.attrLong(autofit, "fontScale", 100000) / 100000f;
            // A placeholder without a type is a content ("obj") placeholder: bulleted by default.
            boolean bodyPlaceholder = p != null && (phType == null || phType.equals("body") || phType.equals("obj"));
            paragraphs(body, s, scale, bodyPlaceholder);
        }
        if (s.fill >= 0 || s.line >= 0 || !s.paras.isEmpty()) {
            slide.shapes.add(s);
        }
    }

    private static void paragraphs(Element txBody, Doc.Shape s, float scale, boolean bulletDefault) {
        Element lstStyle = Xml.child(txBody, "lstStyle");
        float defSize = Xml.attrLong(Xml.path(lstStyle, "lvl1pPr", "defRPr"), "sz", 0) / 100f;
        if (defSize > 0) {
            s.fontSize = defSize;
        }
        for (Element ap : Xml.children(txBody, "p")) {
            Doc.Para para = new Doc.Para();
            Element pPr = Xml.child(ap, "pPr");
            String algn = Xml.attr(pPr, "algn");
            para.align = "ctr".equals(algn) ? Doc.ALIGN_CENTER : "r".equals(algn) ? Doc.ALIGN_RIGHT
                    : "just".equals(algn) ? Doc.ALIGN_JUSTIFY : Doc.ALIGN_LEFT;
            para.indentLevel = (int) Xml.attrLong(pPr, "lvl", 0);
            boolean noBullet = pPr != null && Xml.child(pPr, "buNone") != null;
            boolean bullet = pPr != null && (Xml.child(pPr, "buChar") != null || Xml.child(pPr, "buAutoNum") != null);
            para.bullet = bullet || (bulletDefault && !noBullet);
            for (Element e : Xml.children(ap, null)) {
                String n = Xml.local(e);
                if (n.equals("r") || n.equals("fld")) {
                    Element t = Xml.child(e, "t");
                    if (t == null) {
                        continue;
                    }
                    Doc.Run run = new Doc.Run(t.getTextContent());
                    Element rPr = Xml.child(e, "rPr");
                    if (rPr != null) {
                        run.bold = "1".equals(Xml.attr(rPr, "b")) || "true".equals(Xml.attr(rPr, "b"));
                        run.italic = "1".equals(Xml.attr(rPr, "i")) || "true".equals(Xml.attr(rPr, "i"));
                        String u = Xml.attr(rPr, "u");
                        run.underline = u != null && !u.equals("none");
                        long sz = Xml.attrLong(rPr, "sz", 0);
                        if (sz > 0) {
                            run.size = sz / 100f * scale;
                        }
                        Element fill = Xml.child(rPr, "solidFill");
                        if (fill != null) {
                            run.color = colorOf(fill);
                        }
                    }
                    para.runs.add(run);
                } else if (n.equals("br")) {
                    para.runs.add(new Doc.Run("\n"));
                }
            }
            if (para.isEmpty()) {
                para.bullet = false;
            }
            s.paras.add(para);
        }
        if (scale < 1f) {
            s.fontSize *= scale;
        }
    }

    private void picture(Element pic, float[] tf, Map<String, String> rels, Doc.Slide slide) {
        float[] r = xfrm(Xml.path(pic, "spPr", "xfrm"));
        List<Element> blips = Xml.descendants(pic, "blip");
        if (r == null || blips.isEmpty()) {
            return;
        }
        String target = rels.get(Xml.rel(blips.get(0), "embed"));
        byte[] data = target == null ? null : zip.get(target);
        if (data == null) {
            return;
        }
        r = apply(tf, r);
        Doc.Shape s = new Doc.Shape();
        s.x = r[0];
        s.y = r[1];
        s.w = r[2];
        s.h = r[3];
        s.image = data;
        slide.shapes.add(s);
    }

    private void tableFrame(Element frame, float[] tf, Doc.Slide slide) {
        List<Element> tbls = Xml.descendants(frame, "tbl");
        float[] r = xfrm(Xml.child(frame, "xfrm"));
        if (tbls.isEmpty() || r == null) {
            return;
        }
        r = apply(tf, r);
        Doc.Table t = new Doc.Table();
        for (Element tr : Xml.children(tbls.get(0), "tr")) {
            java.util.List<String> row = new java.util.ArrayList<>();
            for (Element tc : Xml.children(tr, "tc")) {
                StringBuilder sb = new StringBuilder();
                for (Element ap : Xml.descendants(tc, "p")) {
                    StringBuilder line = new StringBuilder();
                    for (Element t2 : Xml.descendants(ap, "t")) {
                        line.append(t2.getTextContent());
                    }
                    if (sb.length() > 0) {
                        sb.append('\n');
                    }
                    sb.append(line);
                }
                row.add(sb.toString());
            }
            t.rows.add(row);
        }
        t.header = true;
        Doc.Shape s = new Doc.Shape();
        s.x = r[0];
        s.y = r[1];
        s.w = r[2];
        s.h = r[3];
        s.table = t;
        s.fontSize = 14;
        slide.shapes.add(s);
    }

    private static int colorOf(Element fill) {
        Element rgb = Xml.child(fill, "srgbClr");
        if (rgb != null) {
            return parseHex(Xml.attr(rgb, "val"));
        }
        Element scheme = Xml.child(fill, "schemeClr");
        if (scheme != null) {
            String v = String.valueOf(Xml.attr(scheme, "val"));
            switch (v) {
                case "bg1":
                case "lt1":
                    return 0xFFFFFF;
                case "tx1":
                case "dk1":
                    return 0x000000;
                case "accent1":
                    return 0x4472C4;
                case "accent2":
                    return 0xED7D31;
                default:
                    return 0x44546A;
            }
        }
        return -1;
    }

    private static int parseHex(String hex) {
        if (hex == null || hex.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
