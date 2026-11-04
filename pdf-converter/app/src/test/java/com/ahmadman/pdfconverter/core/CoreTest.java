package com.ahmadman.pdfconverter.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class CoreTest {

    /** Generated files land here so they can also be opened by LibreOffice / python. */
    static final File OUT = new File(System.getProperty("user.dir"), "build/test-out");

    @BeforeClass
    public static void setUp() {
        OUT.mkdirs();
    }

    private static InputStream fixture(String name) {
        InputStream in = CoreTest.class.getResourceAsStream("/fixtures/" + name);
        assertNotNull(name, in);
        return in;
    }

    /** Minimal PNG encoder (checkerboard), no java.awt on the Android test classpath. */
    private static byte[] png(int w, int h, int rgb) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (int y = 0; y < h; y++) {
            raw.write(0);
            for (int x = 0; x < w; x++) {
                int c = (x / 10 + y / 10) % 2 == 0 ? rgb : 0xFFFFFF;
                raw.write(c >> 16 & 0xFF);
                raw.write(c >> 8 & 0xFF);
                raw.write(c & 0xFF);
            }
        }
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream d = new java.util.zip.DeflaterOutputStream(z)) {
            d.write(raw.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        java.nio.ByteBuffer ihdr = java.nio.ByteBuffer.allocate(13).putInt(w).putInt(h);
        ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) throws Exception {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.write(java.nio.ByteBuffer.allocate(4).putInt(data.length).array());
        out.write(t);
        out.write(data);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(t);
        crc.update(data);
        out.write(java.nio.ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    private static void save(String name, Writer w) throws Exception {
        try (OutputStream out = new FileOutputStream(new File(OUT, name))) {
            w.write(out);
        }
    }

    interface Writer {
        void write(OutputStream out) throws Exception;
    }

    private static PdfText.Word word(String t, float x, float y, float size, boolean bold) {
        return new PdfText.Word(t, x, y, t.length() * size * 0.5f, size, size, bold, false, size * 0.28f);
    }

    private static PdfText.Line line(float y, float size, Object... xs) {
        PdfText.Line l = new PdfText.Line();
        for (int i = 0; i < xs.length; i += 2) {
            l.words.add(word((String) xs[i], ((Number) xs[i + 1]).floatValue(), y, size, false));
        }
        return l;
    }

    // ------------------------------------------------------------------ PageSpec

    @Test
    public void pageSpecRangesOrderAndErrors() {
        assertEquals(Arrays.asList(1, 2, 3, 4, 5), PageSpec.parse("", 5));
        assertEquals(Arrays.asList(1, 2, 3, 5), PageSpec.parse("1-3, 5", 5));
        assertEquals(Arrays.asList(3, 1, 2), PageSpec.parse("3,1,2", 5));
        assertEquals(Arrays.asList(5, 4, 3), PageSpec.parse("5-3", 5));
        assertEquals(Arrays.asList(4, 5), PageSpec.parse("4-", 5));
        assertEquals(Arrays.asList(1, 2), PageSpec.parse("-2", 5));
        assertEquals(Arrays.asList(5), PageSpec.parse("son", 5));
        assertEquals(Arrays.asList(2, 3), PageSpec.parse("2 – 3", 5));
        assertEquals(Arrays.asList(1, 3), new ArrayList<>(PageSpec.parseSet("3,1,3", 5)));
        assertEquals(2, PageSpec.parseGroups("1-2;3-5", 5).size());
        for (String bad : new String[]{"0", "6", "a", "1-9"}) {
            try {
                PageSpec.parse(bad, 5);
                fail(bad);
            } catch (PageSpec.SpecException expected) {
                assertFalse(expected.token.isEmpty());
            }
        }
    }

    // ------------------------------------------------------------------ CSV & cells

    @Test
    public void csvRoundTripWithQuotesAndTurkish() {
        List<List<String>> rows = Arrays.asList(
                Arrays.asList("Ad", "Not", "Açıklama"),
                Arrays.asList("Ayşe", "95", "\"iyi\"; çalışkan"),
                Arrays.asList("Çağrı", "", "satır\nikinci"));
        String text = Csv.write(rows, ';');
        assertTrue(text.startsWith("﻿"));
        assertEquals(';', Csv.detectDelimiter(text.substring(1)));
        assertEquals(rows, Csv.parse(text, ';'));
        assertEquals(',', Csv.detectDelimiter("a,b,c\n1,2,3\n"));
        assertEquals('\t', Csv.detectDelimiter("a\tb\n1\t2\n"));
    }

    @Test
    public void cellValuesOnlyConvertUnambiguousNumbers() {
        assertEquals(42.0, TextAnalyzer.cellValue("42"));
        assertEquals(3.5, TextAnalyzer.cellValue("3,50"));
        assertEquals(-0.25, TextAnalyzer.cellValue("-0.25"));
        assertEquals(1234567.89, TextAnalyzer.cellValue("1.234.567,89"));
        assertEquals(1234.5, TextAnalyzer.cellValue("1.234,5"));
        assertEquals(1234567.0, TextAnalyzer.cellValue("1,234,567"));
        assertEquals("1.234", TextAnalyzer.cellValue("1.234"));   // thousands or decimals?
        assertEquals("0532", TextAnalyzer.cellValue("0532"));     // leading zero kept
        assertEquals("12 TL", TextAnalyzer.cellValue("12 TL"));
    }

    // ------------------------------------------------------------------ Text analysis

    @Test
    public void paragraphsJoinWrappedLinesAndDetectHeadings() {
        PdfText.Page page = new PdfText.Page(1, 595, 842);
        page.lines.add(line(80, 22, "Büyük", 72, "Başlık", 140));
        page.lines.add(line(120, 11, "Bu", 72, "paragrafın", 90, "ilk", 160, "satırı", 180, "uzundur", 220, "ve", 262, "devam", 280, "ediyor", 320, "burada", 360, "son", 400, "kelime", 420, "genişlik", 460));
        page.lines.add(line(134, 11, "ikinci", 72, "satırı", 110));
        page.lines.add(line(170, 11, "•", 72, "liste", 80, "öğesi", 120));
        page.lines.add(line(184, 11, "Yeni", 72, "paragraf", 100));
        float body = TextAnalyzer.bodyFontSize(java.util.Collections.singletonList(page));
        assertEquals(11f, body, 0.01);
        List<PdfText.Paragraph> paras = TextAnalyzer.paragraphs(page, body);
        assertEquals(4, paras.size());
        assertEquals(1, paras.get(0).heading);
        assertTrue(paras.get(1).text.endsWith("genişlik ikinci satırı"));
        assertTrue(paras.get(2).text.startsWith("•"));
        assertEquals("Yeni paragraf", paras.get(3).text);
    }

    @Test
    public void hyphenatedWordsAreRejoined() {
        PdfText.Page page = new PdfText.Page(1, 595, 842);
        page.lines.add(line(100, 11, "uzun", 72, "bir", 100, "satır", 130, "burada", 170, "biraz", 220, "daha", 260, "uzayıp", 300, "gidiyor", 350, "ve", 400, "bölün-", 430));
        page.lines.add(line(114, 11, "müş", 72, "kelime", 100));
        List<PdfText.Paragraph> paras = TextAnalyzer.paragraphs(page, 11);
        assertEquals(1, paras.size());
        assertTrue(paras.get(0).text, paras.get(0).text.contains("bölünmüş kelime"));
    }

    @Test
    public void tableColumnsAreAligned() {
        PdfText.Page page = new PdfText.Page(1, 595, 842);
        page.lines.add(line(100, 10, "Ürün", 50, "Adet", 200, "Birim", 300, "Fiyat", 326));
        page.lines.add(line(114, 10, "Elma", 50, "kırmızı", 76, "12", 202, "3,50", 301));
        page.lines.add(line(128, 10, "Armut", 50, "7", 201));
        List<List<String>> rows = TextAnalyzer.table(page);
        assertEquals(Arrays.asList("Ürün", "Adet", "Birim Fiyat"), rows.get(0));
        assertEquals(Arrays.asList("Elma kırmızı", "12", "3,50"), rows.get(1));
        assertEquals(Arrays.asList("Armut", "7"), rows.get(2));
    }

    // ------------------------------------------------------------------ Writers

    @Test
    public void writesDocxXlsxPptxEpubAndTextFormats() throws Exception {
        byte[] img = png(200, 100, 0x2B6CF6);
        DocxWriter docx = new DocxWriter().pageSize(595, 842, 56);
        docx.paragraph("Başlık ğüşıöç", 0, false, false, 1);
        docx.paragraph("Kalın satır & <özel> \"karakterler\"", 12, true, false, 0);
        docx.paragraph("Sekme\tve\nsatır sonu", 0, false, true, 0);
        docx.table(Arrays.asList(Arrays.asList("A", "B"), Arrays.asList("1", "2")), true);
        docx.pageBreak();
        docx.image(img, "png", 300, 150);
        docx.paragraph("İkinci sayfa", 0, false, false, 2);
        save("sample-out.docx", docx::write);

        XlsxWriter xlsx = new XlsxWriter();
        xlsx.addSheet("Sayfa 1", Arrays.asList(Arrays.asList("Ad", "Tutar"),
                Arrays.asList("Ayşe", "1.234,50"), Arrays.asList("Çağrı", "0532")), true);
        xlsx.addSheet("Sayfa 1", Arrays.asList(Arrays.asList("ikinci", "42")), false); // duplicate name
        xlsx.addSheet("bad/name:*?[x]", new ArrayList<>(), false);
        save("sample-out.xlsx", xlsx::write);

        PptxWriter pptx = new PptxWriter();
        pptx.slideSize(595, 842);
        pptx.addImageSlide(img, "png", 200, 100);
        pptx.addImageSlide(png(100, 100, 0xED7D31), "png", 100, 100);
        save("sample-out.pptx", pptx::write);

        List<PdfText.Paragraph> paras = Arrays.asList(
                new PdfText.Paragraph("Bölüm & Başlık", 20, true, false, 1),
                new PdfText.Paragraph("Metin <b>değil</b> ğüş", 11, false, false, 0),
                new PdfText.Paragraph("Vurgulu", 11, true, false, 0));
        EpubWriter epub = new EpubWriter("Kitap Adı", "tr");
        epub.addChapter("Sayfa 1", EpubWriter.paragraphsToXhtml(paras));
        epub.addChapter("Sayfa 2", EpubWriter.paragraphsToXhtml(paras));
        save("sample-out.epub", epub::write);

        List<List<PdfText.Paragraph>> pages = Arrays.asList(paras, paras);
        save("sample-out.html", o -> o.write(TextFormats.html("Başlık", pages).getBytes(StandardCharsets.UTF_8)));
        save("sample-out.md", o -> o.write(TextFormats.markdown(pages).getBytes(StandardCharsets.UTF_8)));
        save("sample-out.rtf", o -> o.write(TextFormats.rtf(pages, 11).getBytes(StandardCharsets.US_ASCII)));

        assertTrue(TextFormats.rtf(pages, 11).contains("\\u287?")); // ğ
        assertTrue(TextFormats.markdown(pages).startsWith("# Bölüm & Başlık"));
        assertTrue(TextFormats.html("x", pages).contains("&lt;b&gt;değil&lt;/b&gt;"));

        // EPUB: "mimetype" must be the first, uncompressed entry.
        byte[] epubBytes = java.nio.file.Files.readAllBytes(new File(OUT, "sample-out.epub").toPath());
        assertEquals("mimetype", new String(epubBytes, 30, 8, StandardCharsets.US_ASCII));
        assertEquals(0, epubBytes[8]); // compression method low byte = STORED
    }

    @Test
    public void xmlEscapingDropsIllegalCharacters() {
        assertEquals("a&amp;b&lt;c&gt;&quot;&apos;", Xml.esc("a&b<c>\"'"));
        assertEquals("ab", Xml.esc("a\u0001\u0008b"));
        assertEquals("😀", Xml.esc("😀"));
    }

    @Test
    public void zipPathsResolve() {
        assertEquals("ppt/slideLayouts/slideLayout1.xml", Zip.resolve("ppt/slides/slide1.xml", "../slideLayouts/slideLayout1.xml"));
        assertEquals("word/media/image1.png", Zip.resolve("word/document.xml", "media/image1.png"));
        assertEquals("xl/worksheets/sheet1.xml", Zip.resolve("xl/workbook.xml", "/xl/worksheets/sheet1.xml"));
        assertEquals("ppt/slides/_rels/slide3.xml.rels", Zip.relsPath("ppt/slides/slide3.xml"));
        assertEquals("AB", OfficeCommon.column(27));
        assertEquals(27, OfficeCommon.columnIndex("AB12"));
        assertEquals(12, OfficeCommon.rowNumber("AB12"));
    }

    // ------------------------------------------------------------------ Readers

    @Test
    public void readsWordFromPythonDocx() throws Exception {
        Doc doc = DocxReader.read(fixture("sample.docx"));
        StringBuilder all = new StringBuilder();
        int headings = 0;
        int bullets = 0;
        int numbered = 0;
        int images = 0;
        int tables = 0;
        boolean bold = false;
        boolean italic = false;
        boolean pageBreak = false;
        for (Doc.Block b : doc.blocks) {
            if (b instanceof Doc.Para) {
                Doc.Para p = (Doc.Para) b;
                all.append(p.text()).append('\n');
                headings += p.heading > 0 ? 1 : 0;
                bullets += p.bullet ? 1 : 0;
                numbered += p.number > 0 ? 1 : 0;
                pageBreak |= p.pageBreakBefore;
                for (Doc.Run r : p.runs) {
                    bold |= r.bold && r.text.equals("kalın");
                    italic |= r.italic && r.text.equals("italik");
                }
            } else if (b instanceof Doc.Image) {
                images++;
                assertEquals(72f, ((Doc.Image) b).width, 0.5f);
            } else if (b instanceof Doc.Table) {
                tables++;
                assertEquals(Arrays.asList("Elma", "12", "3,50"), ((Doc.Table) b).rows.get(1));
            }
        }
        String text = all.toString();
        assertTrue(text, text.contains("Çeyrek Raporu"));
        assertTrue(text.contains("ğüşıöç ĞÜŞİÖÇ"));
        assertTrue(text.contains("İkinci sayfa metni."));
        assertEquals(2, headings);
        assertEquals(2, bullets);
        assertEquals(2, numbered);
        assertEquals(1, images);
        assertEquals(1, tables);
        assertTrue(bold && italic && pageBreak);
    }

    @Test
    public void readsExcelFromOpenpyxl() throws Exception {
        Doc doc = XlsxReader.read(fixture("sample.xlsx"));
        List<Doc.Table> tables = new ArrayList<>();
        for (Doc.Block b : doc.blocks) {
            if (b instanceof Doc.Table) {
                tables.add((Doc.Table) b);
            }
        }
        assertEquals(2, tables.size());
        Doc.Table t = tables.get(0);
        assertEquals("Satışlar", t.caption);
        assertEquals(Arrays.asList("Ay", "Tutar", "Tarih", "Onay", "", ""), t.rows.get(0));
        assertEquals("1250,5", t.rows.get(1).get(1));
        assertEquals("31.01.2026", t.rows.get(1).get(2));
        assertEquals("DOĞRU", t.rows.get(1).get(3));
        assertEquals("Şubat", t.rows.get(2).get(0));
        assertEquals(10, t.rows.size());                 // up to F10
        assertEquals("uzak hücre", t.rows.get(9).get(5));
        assertEquals("İkinci Sayfa", tables.get(1).caption);
    }

    @Test
    public void readsPowerPointFromPythonPptx() throws Exception {
        Doc doc = PptxReader.read(fixture("sample.pptx"));
        assertEquals(2, doc.slides.size());
        Doc.Slide s1 = doc.slides.get(0);
        assertEquals(720f, s1.width, 0.5f);
        assertEquals(540f, s1.height, 0.5f);
        boolean title = false;
        boolean body = false;
        for (Doc.Shape s : s1.shapes) {
            String text = s.paras.isEmpty() ? "" : s.paras.get(0).text();
            if (s.title && text.equals("Sunum Başlığı")) {
                title = s.w > 0 && s.h > 0; // geometry inherited from the layout
            }
            if (text.equals("Madde bir")) {
                body = s.paras.size() == 2 && s.paras.get(0).bullet;
            }
        }
        assertTrue("title", title);
        assertTrue("body", body);

        Doc.Slide s2 = doc.slides.get(1);
        int pictures = 0;
        boolean big = false;
        boolean grouped = false;
        boolean table = false;
        for (Doc.Shape s : s2.shapes) {
            if (s.image != null) {
                pictures++;
                assertEquals(72f, s.x, 0.5f);
                assertEquals(216f, s.y, 0.5f);
            }
            if (!s.paras.isEmpty() && s.paras.get(0).text().equals("Serbest metin kutusu")) {
                Doc.Run r = s.paras.get(0).runs.get(0);
                big = r.size == 28f && r.bold;
            }
            if (!s.paras.isEmpty() && s.paras.get(0).text().equals("Grup içi")) {
                grouped = Math.abs(s.x - 432f) < 1f;
            }
            if (s.table != null) {
                table = s.table.rows.get(1).equals(Arrays.asList("Ayşe", "95"));
            }
        }
        assertEquals(1, pictures);
        assertTrue("font", big);
        assertTrue("group", grouped);
        assertTrue("table", table);
    }

    @Test
    public void ownWritersRoundTripThroughReaders() throws Exception {
        writesDocxXlsxPptxEpubAndTextFormats();
        Doc d = DocxReader.read(new java.io.FileInputStream(new File(OUT, "sample-out.docx")));
        StringBuilder text = new StringBuilder();
        for (Doc.Block b : d.blocks) {
            if (b instanceof Doc.Para) {
                text.append(((Doc.Para) b).text()).append('|');
            }
        }
        assertTrue(text.toString(), text.toString().contains("Kalın satır & <özel> \"karakterler\""));
        assertTrue(text.toString().contains("Sekme\tve\nsatır sonu"));

        Doc x = XlsxReader.read(new java.io.FileInputStream(new File(OUT, "sample-out.xlsx")));
        Doc.Table t = (Doc.Table) x.blocks.get(0);
        assertEquals("1234,5", t.rows.get(1).get(1));
        assertEquals("0532", t.rows.get(2).get(1));

        Doc p = PptxReader.read(new java.io.FileInputStream(new File(OUT, "sample-out.pptx")));
        assertEquals(2, p.slides.size());
        assertEquals(595f, p.slides.get(0).width, 0.5f);
        assertTrue(p.slides.get(0).shapes.get(0).image != null);
    }

    @Test
    public void markdownAndCsvReaders() {
        Doc md = TextReaders.markdown("# Başlık\n\nMetin **kalın** ve *italik* `kod`.\n\n- bir\n- iki\n1. ilk\n\n| A | B |\n|---|---|\n| 1 | 2 |\n\n---\n> alıntı\n");
        Doc.Para h = (Doc.Para) md.blocks.get(0);
        assertEquals(1, h.heading);
        Doc.Para p = (Doc.Para) md.blocks.get(1);
        assertEquals("Metin kalın ve italik kod.", p.text());
        assertTrue(p.runs.get(1).bold);
        assertTrue(p.runs.get(3).italic);
        assertTrue(p.runs.get(5).monospace);
        assertTrue(((Doc.Para) md.blocks.get(2)).bullet);
        assertEquals(1, ((Doc.Para) md.blocks.get(4)).number);
        Doc.Table t = (Doc.Table) md.blocks.get(5);
        assertTrue(t.header);
        assertEquals(Arrays.asList("1", "2"), t.rows.get(1));
        assertTrue(md.blocks.get(6) instanceof Doc.Rule);
        assertTrue(((Doc.Para) md.blocks.get(7)).quote);

        Doc csv = TextReaders.csv("ad;soyad\nAyşe;Yılmaz\n");
        assertEquals(Arrays.asList("Ayşe", "Yılmaz"), ((Doc.Table) csv.blocks.get(0)).rows.get(1));
    }

    @Test
    public void zipLimitsRejectNonArchives() {
        try {
            Map<String, byte[]> m = Zip.readAll(new ByteArrayInputStream("not a zip".getBytes(StandardCharsets.UTF_8)));
            fail("read " + m.size());
        } catch (java.io.IOException expected) {
            // ok
        }
    }
}
