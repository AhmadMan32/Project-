package com.ahmadman.pdfconverter.core;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** EPUB 3 e-book writer (with an EPUB 2 NCX for older readers). */
public final class EpubWriter {

    private final String title;
    private final String language;
    private final List<String> chapterTitles = new ArrayList<>();
    private final List<String> chapterBodies = new ArrayList<>();

    public EpubWriter(String title, String language) {
        this.title = title == null || title.trim().isEmpty() ? "Belge" : title.trim();
        this.language = language;
    }

    /** @param bodyXhtml body content; must already be escaped well-formed XHTML */
    public void addChapter(String chapterTitle, String bodyXhtml) {
        chapterTitles.add(chapterTitle);
        chapterBodies.add(bodyXhtml);
    }

    public static String paragraphsToXhtml(List<PdfText.Paragraph> paras) {
        StringBuilder sb = new StringBuilder();
        for (PdfText.Paragraph p : paras) {
            String text = Xml.esc(p.text);
            if (p.heading == 1) {
                sb.append("<h1>").append(text).append("</h1>\n");
            } else if (p.heading == 2) {
                sb.append("<h2>").append(text).append("</h2>\n");
            } else if (p.bold) {
                sb.append("<p><strong>").append(text).append("</strong></p>\n");
            } else if (p.italic) {
                sb.append("<p><em>").append(text).append("</em></p>\n");
            } else {
                sb.append("<p>").append(text).append("</p>\n");
            }
        }
        return sb.toString();
    }

    public void write(OutputStream out) throws IOException {
        if (chapterBodies.isEmpty()) {
            addChapter(title, "<p></p>");
        }
        String id = "urn:uuid:" + UUID.randomUUID();
        try (Zip.Writer z = new Zip.Writer(out)) {
            z.putStored("mimetype", "application/epub+zip".getBytes(StandardCharsets.US_ASCII));
            z.put("META-INF/container.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">"
                    + "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/>"
                    + "</rootfiles></container>");
            z.put("OEBPS/style.css", "body{font-family:serif;line-height:1.5;margin:0 4%;}"
                    + "h1{font-size:1.6em;margin:1em 0 .5em;}h2{font-size:1.3em;margin:1em 0 .4em;}p{margin:0 0 .8em;text-indent:0;}");

            StringBuilder manifest = new StringBuilder();
            StringBuilder spine = new StringBuilder();
            StringBuilder navList = new StringBuilder();
            StringBuilder ncxPoints = new StringBuilder();
            for (int i = 0; i < chapterBodies.size(); i++) {
                String file = "chap" + (i + 1) + ".xhtml";
                String t = Xml.esc(chapterTitles.get(i));
                z.put("OEBPS/" + file, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n"
                        + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\""
                        + language + "\" lang=\"" + language + "\"><head><meta charset=\"UTF-8\"/><title>" + t
                        + "</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head><body>\n"
                        + chapterBodies.get(i) + "\n</body></html>");
                manifest.append("<item id=\"c").append(i + 1).append("\" href=\"").append(file)
                        .append("\" media-type=\"application/xhtml+xml\"/>");
                spine.append("<itemref idref=\"c").append(i + 1).append("\"/>");
                navList.append("<li><a href=\"").append(file).append("\">").append(t).append("</a></li>");
                ncxPoints.append("<navPoint id=\"n").append(i + 1).append("\" playOrder=\"").append(i + 1)
                        .append("\"><navLabel><text>").append(t).append("</text></navLabel><content src=\"")
                        .append(file).append("\"/></navPoint>");
            }
            z.put("OEBPS/nav.xhtml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n"
                    + "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" xml:lang=\""
                    + language + "\" lang=\"" + language + "\"><head><meta charset=\"UTF-8\"/><title>" + Xml.esc(title)
                    + "</title></head><body><nav epub:type=\"toc\" id=\"toc\"><h1>İçindekiler</h1><ol>"
                    + navList + "</ol></nav></body></html>");
            z.put("OEBPS/toc.ncx", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<ncx xmlns=\"http://www.daisy.org/z3986/2005/ncx/\" version=\"2005-1\"><head>"
                    + "<meta name=\"dtb:uid\" content=\"" + id + "\"/><meta name=\"dtb:depth\" content=\"1\"/>"
                    + "<meta name=\"dtb:totalPageCount\" content=\"0\"/><meta name=\"dtb:maxPageNumber\" content=\"0\"/>"
                    + "</head><docTitle><text>" + Xml.esc(title) + "</text></docTitle><navMap>" + ncxPoints
                    + "</navMap></ncx>");
            z.put("OEBPS/content.opf", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\" xml:lang=\"" + language + "\">"
                    + "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">"
                    + "<dc:identifier id=\"bookid\">" + id + "</dc:identifier>"
                    + "<dc:title>" + Xml.esc(title) + "</dc:title><dc:language>" + language + "</dc:language>"
                    + "<dc:creator>" + Xml.esc(OfficeCommon.APP) + "</dc:creator>"
                    + "<meta property=\"dcterms:modified\">" + OfficeCommon.now() + "</meta></metadata>"
                    + "<manifest><item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>"
                    + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                    + "<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>" + manifest
                    + "</manifest><spine toc=\"ncx\">" + spine + "</spine></package>");
        }
    }
}
