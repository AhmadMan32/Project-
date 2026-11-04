package com.ahmadman.pdfconverter.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Minimal but valid Word (.docx) writer: paragraphs, headings, images, tables, page breaks. */
public final class DocxWriter {

    private static final long EMU_PER_PT = 12700;

    private final StringBuilder body = new StringBuilder();
    private final List<byte[]> images = new ArrayList<>();
    private final List<String> imageExt = new ArrayList<>();
    private float pageW = 595.28f;
    private float pageH = 841.89f;
    private float margin = 56.7f;
    private boolean breakBefore;

    public DocxWriter pageSize(float widthPt, float heightPt, float marginPt) {
        pageW = widthPt;
        pageH = heightPt;
        margin = marginPt;
        return this;
    }

    public float contentWidth() {
        return pageW - 2 * margin;
    }

    public float contentHeight() {
        return pageH - 2 * margin;
    }

    /** The next paragraph, image or table starts on a new page. */
    public void pageBreak() {
        breakBefore = true;
    }

    private void pPr(StringBuilder sb, String style) {
        if (style == null && !breakBefore) {
            return;
        }
        sb.append("<w:pPr>");
        if (style != null) {
            sb.append("<w:pStyle w:val=\"").append(style).append("\"/>");
        }
        if (breakBefore) {
            sb.append("<w:pageBreakBefore/>");
            breakBefore = false;
        }
        sb.append("</w:pPr>");
    }

    /**
     * @param sizePt  0 for the default size
     * @param heading 0 body, 1 = Heading 1, 2 = Heading 2
     */
    public void paragraph(String text, float sizePt, boolean bold, boolean italic, int heading) {
        StringBuilder sb = body;
        sb.append("<w:p>");
        pPr(sb, heading == 1 ? "Heading1" : heading == 2 ? "Heading2" : null);
        if (text != null && !text.isEmpty()) {
            sb.append("<w:r>");
            if (bold || italic || sizePt > 0) {
                sb.append("<w:rPr>");
                if (bold) {
                    sb.append("<w:b/>");
                }
                if (italic) {
                    sb.append("<w:i/>");
                }
                if (sizePt > 0 && heading == 0) {
                    int half = Math.max(2, Math.round(sizePt * 2));
                    sb.append("<w:sz w:val=\"").append(half).append("\"/><w:szCs w:val=\"").append(half).append("\"/>");
                }
                sb.append("</w:rPr>");
            }
            String[] parts = text.split("\n", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    sb.append("<w:br/>");
                }
                appendText(sb, parts[i]);
            }
            sb.append("</w:r>");
        }
        sb.append("</w:p>");
    }

    private static void appendText(StringBuilder sb, String s) {
        String[] tabs = s.split("\t", -1);
        for (int t = 0; t < tabs.length; t++) {
            if (t > 0) {
                sb.append("<w:tab/>");
            }
            if (!tabs[t].isEmpty()) {
                sb.append("<w:t xml:space=\"preserve\">").append(Xml.esc(tabs[t])).append("</w:t>");
            }
        }
    }

    /** Inline picture scaled to the given size in points. */
    public void image(byte[] data, String ext, float widthPt, float heightPt) {
        images.add(data);
        imageExt.add(ext.equals("jpg") ? "jpeg" : ext);
        int n = images.size();
        long cx = Math.max(1, Math.round(widthPt * EMU_PER_PT));
        long cy = Math.max(1, Math.round(heightPt * EMU_PER_PT));
        StringBuilder sb = body;
        sb.append("<w:p>");
        pPr(sb, null);
        sb.append("<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
                .append("<wp:extent cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/>")
                .append("<wp:docPr id=\"").append(n).append("\" name=\"Resim ").append(n).append("\"/>")
                .append("<wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
                .append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
                .append("<pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(n).append("\" name=\"image")
                .append(n).append('.').append(imageExt.get(n - 1)).append("\"/><pic:cNvPicPr/></pic:nvPicPr>")
                .append("<pic:blipFill><a:blip r:embed=\"rIdImg").append(n).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
                .append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy)
                .append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
                .append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
    }

    /** Simple bordered table; the first row is bold when {@code header} is true. */
    public void table(List<List<String>> rows, boolean header) {
        if (rows.isEmpty()) {
            return;
        }
        if (breakBefore) {
            paragraph("", 0, false, false, 0); // carries the page break
        }
        int cols = 0;
        for (List<String> r : rows) {
            cols = Math.max(cols, r.size());
        }
        int twips = Math.round(contentWidth() * 20);
        int colW = Math.max(200, twips / Math.max(1, cols));
        StringBuilder sb = body;
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"0\" w:type=\"auto\"/>")
                .append("<w:tblBorders>");
        for (String side : new String[]{"top", "left", "bottom", "right", "insideH", "insideV"}) {
            sb.append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"999999\"/>");
        }
        sb.append("</w:tblBorders></w:tblPr><w:tblGrid>");
        for (int c = 0; c < cols; c++) {
            sb.append("<w:gridCol w:w=\"").append(colW).append("\"/>");
        }
        sb.append("</w:tblGrid>");
        for (int r = 0; r < rows.size(); r++) {
            sb.append("<w:tr>");
            List<String> row = rows.get(r);
            for (int c = 0; c < cols; c++) {
                String v = c < row.size() ? row.get(c) : "";
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(colW).append("\" w:type=\"dxa\"/></w:tcPr><w:p>");
                if (v != null && !v.isEmpty()) {
                    sb.append("<w:r>");
                    if (header && r == 0) {
                        sb.append("<w:rPr><w:b/></w:rPr>");
                    }
                    appendText(sb, v.replace('\n', ' '));
                    sb.append("</w:r>");
                }
                sb.append("</w:p></w:tc>");
            }
            sb.append("</w:tr>");
        }
        sb.append("</w:tbl><w:p/>");
    }

    public void write(OutputStream out) throws IOException {
        try (Zip.Writer z = new Zip.Writer(out)) {
            z.put("[Content_Types].xml", contentTypes());
            z.put("_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "</Relationships>");
            z.put("docProps/core.xml", OfficeCommon.coreProps());
            z.put("word/document.xml", document());
            z.put("word/styles.xml", styles());
            StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
            for (int i = 0; i < images.size(); i++) {
                String name = "media/image" + (i + 1) + "." + imageExt.get(i);
                rels.append("<Relationship Id=\"rIdImg").append(i + 1)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"")
                        .append(name).append("\"/>");
                z.put("word/" + name, images.get(i));
            }
            rels.append("</Relationships>");
            z.put("word/_rels/document.xml.rels", rels.toString());
        }
    }

    private String contentTypes() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                + "</Types>";
    }

    private String document() {
        int w = Math.round(pageW * 20);
        int h = Math.round(pageH * 20);
        int m = Math.round(margin * 20);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""
                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
                + " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                + " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                + " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">"
                + "<w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"" + w + "\" w:h=\"" + h + "\""
                + (pageW > pageH ? " w:orient=\"landscape\"" : "") + "/>"
                + String.format(Locale.ROOT,
                "<w:pgMar w:top=\"%d\" w:right=\"%d\" w:bottom=\"%d\" w:left=\"%d\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>",
                m, m, m, m)
                + "</w:sectPr></w:body></w:document>";
    }

    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>"
                + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"tr-TR\"/></w:rPr></w:rPrDefault>"
                + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
                + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"240\" w:after=\"120\"/><w:outlineLvl w:val=\"0\"/></w:pPr>"
                + "<w:rPr><w:b/><w:sz w:val=\"36\"/><w:szCs w:val=\"36\"/></w:rPr></w:style>"
                + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/>"
                + "<w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"80\"/><w:outlineLvl w:val=\"1\"/></w:pPr>"
                + "<w:rPr><w:b/><w:sz w:val=\"28\"/><w:szCs w:val=\"28\"/></w:rPr></w:style>"
                + "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:tblPr><w:tblCellMar>"
                + "<w:left w:w=\"108\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>"
                + "</w:styles>";
    }
}
