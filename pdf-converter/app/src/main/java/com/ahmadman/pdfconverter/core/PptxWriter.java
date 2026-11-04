package com.ahmadman.pdfconverter.core;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Minimal but valid PowerPoint (.pptx) writer: one full-slide picture per slide. */
public final class PptxWriter {

    private static final long EMU_PER_PT = 12700;
    private static final long MIN_EMU = 914400;      // 1 inch
    private static final long MAX_EMU = 51206400;    // 56 inches

    private static final String NS = " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
            + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
            + " xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"";
    private static final String HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String EMPTY_TREE = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
            + "<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/>"
            + "<a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>";

    private long slideW = 9144000;
    private long slideH = 6858000;
    private final List<byte[]> images = new ArrayList<>();
    private final List<String> exts = new ArrayList<>();
    private final List<float[]> sizes = new ArrayList<>();

    /** Slide size in points (clamped to PowerPoint's 1-56 inch limits, aspect kept). */
    public void slideSize(float widthPt, float heightPt) {
        double w = widthPt * EMU_PER_PT;
        double h = heightPt * EMU_PER_PT;
        double scale = 1;
        if (Math.max(w, h) > MAX_EMU) {
            scale = MAX_EMU / Math.max(w, h);
        }
        if (Math.min(w, h) * scale < MIN_EMU) {
            scale = MIN_EMU / Math.min(w, h);
        }
        slideW = Math.max(MIN_EMU, Math.min(MAX_EMU, Math.round(w * scale)));
        slideH = Math.max(MIN_EMU, Math.min(MAX_EMU, Math.round(h * scale)));
    }

    /** Adds a slide showing the picture, fitted and centered. */
    public void addImageSlide(byte[] data, String ext, float imageW, float imageH) {
        images.add(data);
        exts.add(ext.equals("jpg") ? "jpeg" : ext);
        sizes.add(new float[]{imageW, imageH});
    }

    public int slideCount() {
        return images.size();
    }

    public void write(OutputStream out) throws IOException {
        int n = images.size();
        try (Zip.Writer z = new Zip.Writer(out)) {
            StringBuilder ct = new StringBuilder(HEAD
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Default Extension=\"png\" ContentType=\"image/png\"/>"
                    + "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>"
                    + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>"
                    + "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>"
                    + "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>"
                    + "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>"
                    + "<Override PartName=\"/ppt/presProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presProps+xml\"/>"
                    + "<Override PartName=\"/ppt/viewProps.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.viewProps+xml\"/>"
                    + "<Override PartName=\"/ppt/tableStyles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.tableStyles+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>");
            for (int i = 1; i <= n; i++) {
                ct.append("<Override PartName=\"/ppt/slides/slide").append(i)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
            }
            ct.append("</Types>");
            z.put("[Content_Types].xml", ct.toString());

            z.put("_rels/.rels", HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "</Relationships>");
            z.put("docProps/core.xml", OfficeCommon.coreProps());

            // presentation.xml + rels
            StringBuilder pres = new StringBuilder(HEAD + "<p:presentation" + NS + " saveSubsetFonts=\"1\">"
                    + "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>");
            StringBuilder presRels = new StringBuilder(HEAD
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>");
            for (int i = 1; i <= n; i++) {
                pres.append("<p:sldId id=\"").append(255 + i).append("\" r:id=\"rId").append(100 + i).append("\"/>");
                presRels.append("<Relationship Id=\"rId").append(100 + i)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide")
                        .append(i).append(".xml\"/>");
            }
            pres.append("</p:sldIdLst><p:sldSz cx=\"").append(slideW).append("\" cy=\"").append(slideH).append("\"/>")
                    .append("<p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
            presRels.append("<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>")
                    .append("<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/presProps\" Target=\"presProps.xml\"/>")
                    .append("<Relationship Id=\"rId4\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/viewProps\" Target=\"viewProps.xml\"/>")
                    .append("<Relationship Id=\"rId5\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/tableStyles\" Target=\"tableStyles.xml\"/>")
                    .append("</Relationships>");
            z.put("ppt/presentation.xml", pres.toString());
            z.put("ppt/_rels/presentation.xml.rels", presRels.toString());

            z.put("ppt/presProps.xml", HEAD + "<p:presentationPr" + NS + "/>");
            z.put("ppt/viewProps.xml", HEAD + "<p:viewPr" + NS + "><p:gridSpacing cx=\"76200\" cy=\"76200\"/></p:viewPr>");
            z.put("ppt/tableStyles.xml", HEAD + "<a:tblStyleLst xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
                    + " def=\"{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}\"/>");

            z.put("ppt/slideMasters/slideMaster1.xml", HEAD + "<p:sldMaster" + NS + "><p:cSld>"
                    + "<p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg>"
                    + "<p:spTree>" + EMPTY_TREE + "</p:spTree></p:cSld>"
                    + "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\""
                    + " accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
                    + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
                    + "<p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>");
            z.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", HEAD
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/>"
                    + "</Relationships>");
            z.put("ppt/slideLayouts/slideLayout1.xml", HEAD + "<p:sldLayout" + NS + " type=\"blank\" preserve=\"1\">"
                    + "<p:cSld name=\"Blank\"><p:spTree>" + EMPTY_TREE + "</p:spTree></p:cSld>"
                    + "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>");
            z.put("ppt/slideLayouts/_rels/slideLayout1.xml.rels", HEAD
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/>"
                    + "</Relationships>");
            z.put("ppt/theme/theme1.xml", theme());

            for (int i = 1; i <= n; i++) {
                float[] size = sizes.get(i - 1);
                double scale = Math.min(slideW / (double) Math.max(1, size[0]), slideH / (double) Math.max(1, size[1]));
                long cx = Math.round(size[0] * scale);
                long cy = Math.round(size[1] * scale);
                long x = (slideW - cx) / 2;
                long y = (slideH - cy) / 2;
                String media = "image" + i + "." + exts.get(i - 1);
                z.put("ppt/media/" + media, images.get(i - 1));
                z.put("ppt/slides/slide" + i + ".xml", HEAD + "<p:sld" + NS + "><p:cSld><p:spTree>" + EMPTY_TREE
                        + "<p:pic><p:nvPicPr><p:cNvPr id=\"2\" name=\"Sayfa " + i + "\"/><p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>"
                        + "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                        + "<p:spPr><a:xfrm><a:off x=\"" + x + "\" y=\"" + y + "\"/><a:ext cx=\"" + cx + "\" cy=\"" + cy + "\"/></a:xfrm>"
                        + "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
                        + "</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>");
                z.put("ppt/slides/_rels/slide" + i + ".xml.rels", HEAD
                        + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                        + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>"
                        + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/" + media + "\"/>"
                        + "</Relationships>");
            }
        }
    }

    private static String theme() {
        String[][] accents = {{"accent1", "4472C4"}, {"accent2", "ED7D31"}, {"accent3", "A5A5A5"},
                {"accent4", "FFC000"}, {"accent5", "5B9BD5"}, {"accent6", "70AD47"},
                {"hlink", "0563C1"}, {"folHlink", "954F72"}};
        StringBuilder sb = new StringBuilder(HEAD + "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Office Theme\">"
                + "<a:themeElements><a:clrScheme name=\"Office\">"
                + "<a:dk1><a:sysClr val=\"windowText\" lastClr=\"000000\"/></a:dk1>"
                + "<a:lt1><a:sysClr val=\"window\" lastClr=\"FFFFFF\"/></a:lt1>"
                + "<a:dk2><a:srgbClr val=\"44546A\"/></a:dk2><a:lt2><a:srgbClr val=\"E7E6E6\"/></a:lt2>");
        for (String[] a : accents) {
            sb.append("<a:").append(a[0]).append("><a:srgbClr val=\"").append(a[1]).append("\"/></a:").append(a[0]).append('>');
        }
        sb.append("</a:clrScheme><a:fontScheme name=\"Office\">"
                + "<a:majorFont><a:latin typeface=\"Calibri Light\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>"
                + "<a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>"
                + "</a:fontScheme><a:fmtScheme name=\"Office\"><a:fillStyleLst>");
        String solid = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>";
        sb.append(solid).append(solid).append(solid).append("</a:fillStyleLst><a:lnStyleLst>");
        for (int i = 0; i < 3; i++) {
            sb.append("<a:ln w=\"").append(6350 * (i + 1)).append("\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\">")
                    .append(solid).append("<a:prstDash val=\"solid\"/><a:miter lim=\"800000\"/></a:ln>");
        }
        sb.append("</a:lnStyleLst><a:effectStyleLst>");
        for (int i = 0; i < 3; i++) {
            sb.append("<a:effectStyle><a:effectLst/></a:effectStyle>");
        }
        sb.append("</a:effectStyleLst><a:bgFillStyleLst>").append(solid).append(solid).append(solid)
                .append("</a:bgFillStyleLst></a:fmtScheme></a:themeElements>"
                        + "<a:objectDefaults/><a:extraClrSchemeLst/></a:theme>");
        return sb.toString();
    }
}
