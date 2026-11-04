package com.ahmadman.pdfconverter.convert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;

import com.ahmadman.pdfconverter.core.Doc;
import com.ahmadman.pdfconverter.core.DocxReader;
import com.ahmadman.pdfconverter.core.XlsxReader;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runs the PDFBox based tools for real (Robolectric provides the Android framework). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PdfToolsTest {

    private static final File KEEP = new File(System.getProperty("user.dir"), "build/test-out/android");

    private Context ctx;
    private File root;
    private int jobs;

    @Before
    public void setUp() throws Exception {
        ctx = RuntimeEnvironment.getApplication();
        PDFBoxResourceLoader.init(ctx);
        root = new File(ctx.getCacheDir(), "t" + System.nanoTime());
        root.mkdirs();
        KEEP.mkdirs();
    }

    /** A PDF with a big title, a two-column table and Turkish text on every page. */
    private File pdf(String name, int pages, boolean withImage) throws Exception {
        File f = new File(root, name);
        try (PDDocument doc = new PDDocument()) {
            PDType0Font font;
            try (InputStream in = PDFBoxResourceLoader.getStream("com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                font = PDType0Font.load(doc, in);
            }
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    text(cs, font, 26, 72, 760, name + " Başlık " + i);
                    text(cs, font, 11, 72, 720, "Bu paragraf ğüşıöç ĞÜŞİÖÇ harfleriyle yazılmış uzun bir cümledir ve satırı doldurur.");
                    text(cs, font, 11, 72, 706, "İkinci satır paragrafın devamıdır.");
                    text(cs, font, 11, 72, 670, "Ürün");
                    text(cs, font, 11, 260, 670, "Adet");
                    text(cs, font, 11, 72, 655, "Elma");
                    text(cs, font, 11, 260, 655, "12");
                    if (withImage && i == 1) {
                        Bitmap bmp = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888);
                        bmp.eraseColor(Color.rgb(200, 30, 30));
                        cs.drawImage(LosslessFactory.createFromImage(doc, bmp), 72, 400, 120, 60);
                    }
                }
            }
            doc.save(f);
        }
        return f;
    }

    private static void text(PDPageContentStream cs, PDType0Font font, float size, float x, float y, String s)
            throws Exception {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    private Job job(List<File> inputs, Map<String, String> options, String password) {
        File dir = new File(root, "job" + jobs++);
        File out = new File(dir, "out");
        File work = new File(dir, "work");
        out.mkdirs();
        work.mkdirs();
        List<Uri> uris = new ArrayList<>();
        for (File f : inputs) {
            uris.add(Uri.fromFile(f));
        }
        String base = inputs.get(0).getName().replace(".pdf", "");
        return new Job(ctx, uris, options, out, work, base, password, null);
    }

    private static Map<String, String> opts(String... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String textOf(File pdf, String password) throws Exception {
        try (PDDocument d = PDDocument.load(pdf, password)) {
            return new PDFTextStripper().getText(d);
        }
    }

    private static int pages(File pdf) throws Exception {
        try (PDDocument d = PDDocument.load(pdf)) {
            return d.getNumberOfPages();
        }
    }

    private static String read(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            Sources.copy(in, bos);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static void keep(List<File> files) throws Exception {
        for (File f : files) {
            try (InputStream in = new FileInputStream(f);
                 java.io.OutputStream out = new java.io.FileOutputStream(new File(KEEP, f.getName()))) {
                Sources.copy(in, out);
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    @Test
    public void mergeSplitExtractDeleteRotate() throws Exception {
        File a = pdf("a.pdf", 2, false);
        File b = pdf("b.pdf", 3, false);

        List<File> merged = PdfTools.merge(job(Arrays.asList(a, b), opts(), ""));
        assertEquals(5, pages(merged.get(0)));
        assertTrue(textOf(merged.get(0), "").contains("b.pdf Başlık 3"));

        List<File> each = PdfTools.split(job(Arrays.asList(merged.get(0)), opts("mode", "each"), ""));
        assertEquals(5, each.size());
        List<File> ranges = PdfTools.split(job(Arrays.asList(merged.get(0)), opts("mode", "ranges", "ranges", "1-2, 3-"), ""));
        assertEquals(2, ranges.size());
        assertEquals(3, pages(ranges.get(1)));
        List<File> every = PdfTools.split(job(Arrays.asList(merged.get(0)), opts("mode", "every", "every", "2", "zip", "true"), ""));
        assertEquals(1, every.size());
        assertTrue(every.get(0).getName().endsWith(".zip"));

        File picked = PdfTools.extract(job(Arrays.asList(b), opts("pages", "3,1"), "")).get(0);
        String t = textOf(picked, "");
        assertEquals(2, pages(picked));
        assertTrue(t.indexOf("Başlık 3") < t.indexOf("Başlık 1"));

        File deleted = PdfTools.delete(job(Arrays.asList(b), opts("pages", "2"), "")).get(0);
        assertEquals(2, pages(deleted));
        assertFalse(textOf(deleted, "").contains("Başlık 2"));
        try {
            PdfTools.delete(job(Arrays.asList(b), opts("pages", "1-3"), ""));
            fail("deleting every page must be refused");
        } catch (Job.UserException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }

        File rotated = PdfTools.rotate(job(Arrays.asList(b), opts("angle", "270", "pages", "2"), "")).get(0);
        try (PDDocument d = PDDocument.load(rotated)) {
            assertEquals(0, d.getPage(0).getRotation());
            assertEquals(270, d.getPage(1).getRotation());
        }
        keep(Arrays.asList(merged.get(0), picked));
    }

    @Test
    public void protectAndUnlock() throws Exception {
        File a = pdf("gizli.pdf", 1, false);
        try {
            PdfTools.protect(job(Arrays.asList(a), opts("password", "abc", "password2", "abd"), ""));
            fail("mismatch");
        } catch (Job.UserException expected) {
            // ok
        }
        File locked = PdfTools.protect(job(Arrays.asList(a),
                opts("password", "Şifre123", "password2", "Şifre123", "allowCopy", "false"), "")).get(0);
        try {
            PDDocument.load(locked).close();
            fail("opened without password");
        } catch (InvalidPasswordException expected) {
            // ok
        }
        try (PDDocument d = PDDocument.load(locked, "Şifre123")) {
            assertTrue(d.isEncrypted());
            assertFalse(d.getCurrentAccessPermission().canExtractContent());
            assertTrue(d.getCurrentAccessPermission().canPrint());
        }
        // The app's loader turns a wrong password into a PasswordException (-> password dialog).
        try {
            PdfTools.unlock(job(Arrays.asList(locked), opts(), "yanlış"));
            fail("wrong password accepted");
        } catch (Job.PasswordException expected) {
            assertEquals(locked.getName(), expected.fileName);
        }
        File open = PdfTools.unlock(job(Arrays.asList(locked), opts(), "Şifre123")).get(0);
        try (PDDocument d = PDDocument.load(open)) {
            assertFalse(d.isEncrypted());
        }
        assertTrue(textOf(open, "").contains("Başlık 1"));
        keep(Arrays.asList(locked));
    }

    @Test
    public void watermarkAndPageNumbers() throws Exception {
        File a = pdf("damga.pdf", 3, false);
        File wm = PdfTools.watermark(job(Arrays.asList(a), opts("text", "GİZLİ ŞİRKET 😀", "color", "red"), "")).get(0);
        // diagonal text comes back in fragments: compare without whitespace
        String t = textOf(wm, "").replaceAll("\\s", "");
        assertTrue(t, t.contains("GİZLİŞİRKET?")); // emoji has no glyph -> replaced, not a crash
        File flat = PdfTools.watermark(job(Arrays.asList(a), opts("text", "TASLAK", "layout", "horizontal"), "")).get(0);
        assertTrue(textOf(flat, "").contains("TASLAK"));
        File numbered = PdfTools.pageNumbers(job(Arrays.asList(a), opts("format", "n/N", "start", "5"), "")).get(0);
        String n = textOf(numbered, "");
        assertTrue(n, n.contains("5 / 7") && n.contains("7 / 7"));
        File pageWord = PdfTools.pageNumbers(job(Arrays.asList(a), opts("format", "page", "position", "top-right"), "")).get(0);
        String expected = ctx.getString(com.ahmadman.pdfconverter.R.string.page_label, 3); // "Sayfa 3" / "Page 3"
        assertTrue(textOf(pageWord, "").contains(expected));
        keep(Arrays.asList(wm, numbered));
    }

    @Test
    public void textConversions() throws Exception {
        File a = pdf("rapor.pdf", 2, false);
        String txt = read(FromPdf.toText(job(Arrays.asList(a), opts(), "")).get(0));
        assertTrue(txt, txt.contains("ğüşıöç ĞÜŞİÖÇ"));

        String html = read(FromPdf.toHtml(job(Arrays.asList(a), opts(), "")).get(0));
        assertTrue(html, html.contains("<h1>rapor.pdf Başlık 1</h1>"));
        assertTrue(html, html.contains("doldurur. İkinci satır")); // wrapped lines joined

        String md = read(FromPdf.toMarkdown(job(Arrays.asList(a), opts("pages", "2"), "")).get(0));
        assertTrue(md, md.startsWith("# rapor.pdf Başlık 2"));

        String csv = read(FromPdf.toCsv(job(Arrays.asList(a), opts("delimiter", ";"), "")).get(0));
        assertTrue(csv, csv.contains("Ürün;Adet") && csv.contains("Elma;12"));

        File xlsx = FromPdf.toExcel(job(Arrays.asList(a), opts("mode", "sheets"), "")).get(0);
        Doc x = XlsxReader.read(new FileInputStream(xlsx));
        Doc.Table first = (Doc.Table) x.blocks.get(0);
        boolean row = false;
        for (List<String> r : first.rows) {
            row |= r.size() >= 2 && r.get(0).equals("Elma") && r.get(1).equals("12");
        }
        assertTrue(first.rows.toString(), row);

        File docx = FromPdf.toWord(job(Arrays.asList(a), opts("mode", "text"), "")).get(0);
        Doc d = DocxReader.read(new FileInputStream(docx));
        int headings = 0;
        StringBuilder all = new StringBuilder();
        for (Doc.Block b : d.blocks) {
            if (b instanceof Doc.Para) {
                headings += ((Doc.Para) b).heading > 0 ? 1 : 0;
                all.append(((Doc.Para) b).text()).append('\n');
            }
        }
        assertEquals(2, headings);
        assertTrue(all.toString(), all.toString().contains("İkinci satır paragrafın devamıdır."));

        assertTrue(read(FromPdf.toRtf(job(Arrays.asList(a), opts(), "")).get(0)).startsWith("{\\rtf1"));
        File epub = FromPdf.toEpub(job(Arrays.asList(a), opts(), "")).get(0);
        assertTrue(epub.length() > 500);
        keep(Arrays.asList(docx, xlsx, epub));
    }

    @Test
    public void imagesAreExtractedAndBadPagesRejected() throws Exception {
        File a = pdf("resimli.pdf", 2, true);
        List<File> imgs = FromPdf.extractImages(job(Arrays.asList(a), opts(), ""));
        assertEquals(1, imgs.size());
        assertTrue(imgs.get(0).getName().endsWith(".png"));
        try {
            FromPdf.extractImages(job(Arrays.asList(a), opts("pages", "2"), ""));
            fail("page 2 has no image");
        } catch (Job.UserException expected) {
            // ok
        }
        try {
            FromPdf.toText(job(Arrays.asList(a), opts("pages", "1-9"), ""));
            fail("bad range accepted");
        } catch (Job.UserException expected) {
            assertTrue(expected.getMessage().contains("1-9"));
        }
        File compressed = PdfTools.compress(job(Arrays.asList(a), opts("level", "high"), "")).get(0);
        assertEquals(2, pages(compressed));
    }

    @Test
    public void imagesToPdfKeepsJpegAndRotation() throws Exception {
        Bitmap bmp = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
        bmp.eraseColor(Color.rgb(30, 120, 200));
        File jpg = new File(root, "foto.jpg");
        Images.save(bmp, "jpg", 90, jpg);
        File png = new File(root, "ekran.png");
        Images.save(bmp, "png", 100, png);
        File out = ToPdf.images(job(Arrays.asList(jpg, png), opts("page", "auto", "margin", "small"), "")).get(0);
        try (PDDocument d = PDDocument.load(out)) {
            assertEquals(2, d.getNumberOfPages());
            // wide picture -> landscape A4
            assertTrue(d.getPage(0).getMediaBox().getWidth() > d.getPage(0).getMediaBox().getHeight());
        }
        File notImage = new File(root, "not.jpg");
        java.nio.file.Files.write(notImage.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
        try {
            ToPdf.images(job(Arrays.asList(notImage), opts(), ""));
            fail("text accepted as image");
        } catch (Job.UserException expected) {
            assertTrue(expected.getMessage().contains("not.jpg"));
        }
    }
}
