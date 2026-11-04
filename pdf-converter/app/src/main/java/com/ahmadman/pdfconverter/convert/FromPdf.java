package com.ahmadman.pdfconverter.convert;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import com.ahmadman.pdfconverter.R;
import com.ahmadman.pdfconverter.core.Csv;
import com.ahmadman.pdfconverter.core.DocxWriter;
import com.ahmadman.pdfconverter.core.EpubWriter;
import com.ahmadman.pdfconverter.core.PageSpec;
import com.ahmadman.pdfconverter.core.PdfText;
import com.ahmadman.pdfconverter.core.PptxWriter;
import com.ahmadman.pdfconverter.core.TextAnalyzer;
import com.ahmadman.pdfconverter.core.TextFormats;
import com.ahmadman.pdfconverter.core.XlsxWriter;
import com.ahmadman.pdfconverter.core.Zip;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** PDF -> Word, Excel, PowerPoint, images, text, HTML, Markdown, RTF, CSV, EPUB. */
public final class FromPdf {

    private FromPdf() {
    }

    static List<Integer> pages(Job job, int count) throws Job.UserException {
        try {
            return PageSpec.parse(job.opt("pages", ""), count);
        } catch (PageSpec.SpecException e) {
            throw new Job.UserException(job.string(R.string.err_pages, e.token));
        }
    }

    /** Positioned text of the selected pages. */
    private static List<PdfText.Page> readText(Job job) throws Exception {
        File f = Pdfs.input(job, 0);
        String name = Sources.displayName(job.context, job.inputs.get(0));
        try (PDDocument doc = Pdfs.load(job, f, name)) {
            List<Integer> sel = pages(job, doc.getNumberOfPages());
            return Pdfs.text(job, doc, sel);
        }
    }

    private static List<PdfText.Page> requireText(Job job, List<PdfText.Page> pages) throws Job.UserException {
        for (PdfText.Page p : pages) {
            if (!p.isEmpty()) {
                return pages;
            }
        }
        throw new Job.UserException(job.string(R.string.err_no_text));
    }

    private static List<List<PdfText.Paragraph>> paragraphs(List<PdfText.Page> pages) {
        float body = TextAnalyzer.bodyFontSize(pages);
        List<List<PdfText.Paragraph>> out = new ArrayList<>();
        for (PdfText.Page p : pages) {
            out.add(TextAnalyzer.paragraphs(p, body));
        }
        return out;
    }

    private static File writeText(Job job, String ext, String text) throws IOException {
        File out = job.out(job.baseName + "." + ext);
        try (OutputStream os = new FileOutputStream(out)) {
            os.write(text.getBytes(ext.equals("rtf") ? StandardCharsets.US_ASCII : StandardCharsets.UTF_8));
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------
    // Word
    // ---------------------------------------------------------------------------------------

    public static List<File> toWord(Job job) throws Exception {
        File f = Pdfs.input(job, 0);
        String name = Sources.displayName(job.context, job.inputs.get(0));
        boolean imageMode = job.opt("mode", "text").equals("image");
        int dpi = job.optInt("dpi", 150);
        List<Integer> sel;
        List<PdfText.Page> text = null;
        try (PDDocument doc = Pdfs.load(job, f, name)) {
            sel = pages(job, doc.getNumberOfPages());
            if (!imageMode) {
                text = Pdfs.text(job, doc, sel);
            }
        }
        DocxWriter w = new DocxWriter();
        Pdfs.Renderer r = null; // only opened when page pictures are needed
        try {
            if (imageMode) {
                r = new Pdfs.Renderer(job, f, name);
                float[] first = r.size(sel.get(0) - 1);
                w.pageSize(first[0], first[1], 0);
                for (int i = 0; i < sel.size(); i++) {
                    job.progress(i, sel.size(), job.string(R.string.progress_rendering_page, sel.get(i)));
                    if (i > 0) {
                        w.pageBreak();
                    }
                    addPageImage(w, r, sel.get(i) - 1, dpi, first[0] - 2, first[1] - 2);
                }
            } else {
                w.pageSize(text.get(0).width, text.get(0).height, 56.7f);
                float body = TextAnalyzer.bodyFontSize(text);
                for (int i = 0; i < text.size(); i++) {
                    job.progress(i, text.size(), job.string(R.string.progress_writing_page, sel.get(i)));
                    PdfText.Page page = text.get(i);
                    if (i > 0) {
                        w.pageBreak();
                    }
                    if (page.isEmpty()) {
                        // scanned page: keep it as a picture so nothing is lost
                        if (r == null) {
                            r = new Pdfs.Renderer(job, f, name);
                        }
                        addPageImage(w, r, sel.get(i) - 1, dpi, w.contentWidth(), w.contentHeight());
                        continue;
                    }
                    for (PdfText.Paragraph p : TextAnalyzer.paragraphs(page, body)) {
                        float size = Math.abs(p.fontSize - body) > 0.6f ? Math.round(p.fontSize * 2) / 2f : 0;
                        w.paragraph(p.text, size, p.bold, p.italic, p.heading);
                    }
                }
            }
        } finally {
            if (r != null) {
                r.close();
            }
        }
        job.progress(sel.size(), sel.size(), job.string(R.string.progress_writing));
        File out = job.out(job.baseName + ".docx");
        try (OutputStream os = new FileOutputStream(out)) {
            w.write(os);
        }
        return Collections.singletonList(out);
    }

    private static void addPageImage(DocxWriter w, Pdfs.Renderer r, int index, int dpi, float maxW, float maxH) {
        Bitmap bmp = r.render(index, dpi);
        byte[] jpg = Images.encode(bmp, "jpg", 85);
        float pw = bmp.getWidth();
        float ph = bmp.getHeight();
        bmp.recycle();
        float s = Math.min(maxW / pw, maxH / ph);
        w.image(jpg, "jpeg", pw * s, ph * s);
    }

    // ---------------------------------------------------------------------------------------
    // Excel / CSV
    // ---------------------------------------------------------------------------------------

    public static List<File> toExcel(Job job) throws Exception {
        List<PdfText.Page> pages = requireText(job, readText(job));
        XlsxWriter x = new XlsxWriter();
        if (job.opt("mode", "sheets").equals("single")) {
            List<List<String>> all = new ArrayList<>();
            for (PdfText.Page p : pages) {
                if (!all.isEmpty()) {
                    all.add(new ArrayList<>());
                }
                all.addAll(TextAnalyzer.table(p));
            }
            x.addSheet(job.baseName, all, false);
        } else {
            for (PdfText.Page p : pages) {
                x.addSheet(job.string(R.string.sheet_page, p.number), TextAnalyzer.table(p), false);
            }
        }
        job.progress(1, 1, job.string(R.string.progress_writing));
        File out = job.out(job.baseName + ".xlsx");
        try (OutputStream os = new FileOutputStream(out)) {
            x.write(os);
        }
        return Collections.singletonList(out);
    }

    public static List<File> toCsv(Job job) throws Exception {
        List<PdfText.Page> pages = requireText(job, readText(job));
        List<List<String>> all = new ArrayList<>();
        for (PdfText.Page p : pages) {
            all.addAll(TextAnalyzer.table(p));
        }
        char delimiter = job.opt("delimiter", ";").equals(",") ? ',' : ';';
        return Collections.singletonList(writeText(job, "csv", Csv.write(all, delimiter)));
    }

    // ---------------------------------------------------------------------------------------
    // PowerPoint
    // ---------------------------------------------------------------------------------------

    public static List<File> toPowerPoint(Job job) throws Exception {
        File f = Pdfs.input(job, 0);
        String name = Sources.displayName(job.context, job.inputs.get(0));
        int dpi = job.optInt("dpi", 150);
        PptxWriter p = new PptxWriter();
        try (Pdfs.Renderer r = new Pdfs.Renderer(job, f, name)) {
            List<Integer> sel = pages(job, r.pageCount());
            float[] first = r.size(sel.get(0) - 1);
            p.slideSize(first[0], first[1]);
            for (int i = 0; i < sel.size(); i++) {
                job.progress(i, sel.size(), job.string(R.string.progress_rendering_page, sel.get(i)));
                Bitmap bmp = r.render(sel.get(i) - 1, dpi);
                p.addImageSlide(Images.encode(bmp, "jpg", 85), "jpeg", bmp.getWidth(), bmp.getHeight());
                bmp.recycle();
            }
        }
        job.progress(1, 1, job.string(R.string.progress_writing));
        File out = job.out(job.baseName + ".pptx");
        try (OutputStream os = new FileOutputStream(out)) {
            p.write(os);
        }
        return Collections.singletonList(out);
    }

    // ---------------------------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------------------------

    public static List<File> toImages(Job job) throws Exception {
        File f = Pdfs.input(job, 0);
        String name = Sources.displayName(job.context, job.inputs.get(0));
        String ext = job.opt("format", "jpg");
        int dpi = job.optInt("dpi", 150);
        int quality = job.optInt("quality", 90);
        List<File> files = new ArrayList<>();
        try (Pdfs.Renderer r = new Pdfs.Renderer(job, f, name)) {
            List<Integer> sel = pages(job, r.pageCount());
            int digits = String.valueOf(r.pageCount()).length();
            for (int i = 0; i < sel.size(); i++) {
                int n = sel.get(i);
                job.progress(i, sel.size(), job.string(R.string.progress_rendering_page, n));
                Bitmap bmp = r.render(n - 1, dpi);
                File out = job.out(job.baseName + "_" + job.string(R.string.file_page)
                        + String.format(java.util.Locale.ROOT, "%0" + digits + "d", n) + "." + ext);
                Images.save(bmp, ext, quality, out);
                bmp.recycle();
                files.add(out);
            }
        }
        if (job.optBool("zip", false) && files.size() > 1) {
            return Collections.singletonList(zip(job, files, job.baseName + ".zip"));
        }
        return files;
    }

    static File zip(Job job, List<File> files, String name) throws IOException, Job.CancelledException {
        job.progress(1, 1, job.string(R.string.progress_writing));
        File out = job.out(name);
        try (OutputStream os = new FileOutputStream(out); Zip.Writer z = new Zip.Writer(os)) {
            for (File f : files) {
                try (InputStream in = new java.io.FileInputStream(f)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    Sources.copy(in, bos);
                    z.put(f.getName(), bos.toByteArray());
                }
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        return out;
    }

    /** All selected pages stacked into one tall picture. */
    public static List<File> toLongImage(Job job) throws Exception {
        File f = Pdfs.input(job, 0);
        String name = Sources.displayName(job.context, job.inputs.get(0));
        String ext = job.opt("format", "jpg");
        int dpi = job.optInt("dpi", 100);
        final long maxPixels = 40_000_000L;
        try (Pdfs.Renderer r = new Pdfs.Renderer(job, f, name)) {
            List<Integer> sel = pages(job, r.pageCount());
            float maxW = 0;
            float totalH = 0;
            for (int n : sel) {
                float[] s = r.size(n - 1);
                maxW = Math.max(maxW, s[0]);
            }
            for (int n : sel) {
                float[] s = r.size(n - 1);
                totalH += s[1] * (maxW / s[0]);
            }
            float scale = dpi / 72f;
            if ((double) maxW * scale * totalH * scale > maxPixels) {
                scale = (float) Math.sqrt(maxPixels / ((double) maxW * totalH));
            }
            int width = Math.round(maxW * scale);
            int height = Math.round(totalH * scale);
            if (width < 300 || height > 65000) {
                throw new Job.UserException(job.string(R.string.err_too_long));
            }
            Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            out.eraseColor(Color.WHITE);
            Canvas c = new Canvas(out);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            int y = 0;
            int pageDpi = Math.max(36, Math.round(scale * 72));
            for (int i = 0; i < sel.size(); i++) {
                job.progress(i, sel.size(), job.string(R.string.progress_rendering_page, sel.get(i)));
                Bitmap page = r.render(sel.get(i) - 1, pageDpi);
                int h = Math.round(page.getHeight() * (width / (float) page.getWidth()));
                c.drawBitmap(page, null, new Rect(0, y, width, y + h), p);
                page.recycle();
                y += h;
            }
            File file = job.out(job.baseName + "_" + job.string(R.string.file_long) + "." + ext);
            job.progress(1, 1, job.string(R.string.progress_writing));
            Images.save(out, ext, job.optInt("quality", 88), file);
            out.recycle();
            return Collections.singletonList(file);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Text formats
    // ---------------------------------------------------------------------------------------

    public static List<File> toText(Job job) throws Exception {
        List<PdfText.Page> pages = requireText(job, readText(job));
        return Collections.singletonList(writeText(job, "txt", TextFormats.txt(pages)));
    }

    public static List<File> toHtml(Job job) throws Exception {
        List<PdfText.Page> pages = requireText(job, readText(job));
        return Collections.singletonList(writeText(job, "html", TextFormats.html(job.baseName, paragraphs(pages))));
    }

    public static List<File> toMarkdown(Job job) throws Exception {
        List<PdfText.Page> pages = requireText(job, readText(job));
        return Collections.singletonList(writeText(job, "md", TextFormats.markdown(paragraphs(pages))));
    }

    public static List<File> toRtf(Job job) throws Exception {
        List<PdfText.Page> pages = requireText(job, readText(job));
        return Collections.singletonList(writeText(job, "rtf",
                TextFormats.rtf(paragraphs(pages), TextAnalyzer.bodyFontSize(pages))));
    }

    public static List<File> toEpub(Job job) throws Exception {
        List<PdfText.Page> pages = requireText(job, readText(job));
        List<List<PdfText.Paragraph>> paras = paragraphs(pages);
        EpubWriter e = new EpubWriter(job.baseName, "tr");
        for (int i = 0; i < paras.size(); i++) {
            if (paras.get(i).isEmpty()) {
                continue;
            }
            e.addChapter(job.string(R.string.sheet_page, pages.get(i).number), EpubWriter.paragraphsToXhtml(paras.get(i)));
        }
        File out = job.out(job.baseName + ".epub");
        try (OutputStream os = new FileOutputStream(out)) {
            e.write(os);
        }
        return Collections.singletonList(out);
    }

    // ---------------------------------------------------------------------------------------
    // Embedded pictures
    // ---------------------------------------------------------------------------------------

    public static List<File> extractImages(Job job) throws Exception {
        File f = Pdfs.input(job, 0);
        String name = Sources.displayName(job.context, job.inputs.get(0));
        List<File> files = new ArrayList<>();
        Map<COSBase, Boolean> seen = new IdentityHashMap<>();
        try (PDDocument doc = Pdfs.load(job, f, name)) {
            List<Integer> sel = pages(job, doc.getNumberOfPages());
            for (int i = 0; i < sel.size(); i++) {
                job.progress(i, sel.size(), job.string(R.string.progress_reading_page, sel.get(i)));
                PDPage page = doc.getPage(sel.get(i) - 1);
                collectImages(job, page.getResources(), sel.get(i), seen, files, 0);
                if (files.size() >= 2000) {
                    break;
                }
            }
        }
        if (files.isEmpty()) {
            throw new Job.UserException(job.string(R.string.err_no_images));
        }
        if (job.optBool("zip", false) && files.size() > 1) {
            return Collections.singletonList(zip(job, files, job.baseName + "_" + job.string(R.string.file_images) + ".zip"));
        }
        return files;
    }

    private static void collectImages(Job job, PDResources res, int pageNo, Map<COSBase, Boolean> seen,
                                      List<File> files, int depth) throws Job.CancelledException {
        if (res == null || depth > 8) {
            return;
        }
        for (COSName n : res.getXObjectNames()) {
            job.check();
            PDXObject x;
            try {
                x = res.getXObject(n);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            if (x == null || seen.containsKey(x.getCOSObject())) {
                continue;
            }
            seen.put(x.getCOSObject(), Boolean.TRUE);
            if (x instanceof PDFormXObject) {
                collectImages(job, ((PDFormXObject) x).getResources(), pageNo, seen, files, depth + 1);
            } else if (x instanceof PDImageXObject) {
                PDImageXObject img = (PDImageXObject) x;
                if (img.getWidth() < 16 || img.getHeight() < 16 || img.isStencil()) {
                    continue;
                }
                try {
                    String base = job.baseName + "_" + job.string(R.string.file_page) + pageNo + "_"
                            + job.string(R.string.file_image) + (files.size() + 1);
                    byte[] jpeg = rawJpeg(img);
                    File out;
                    if (jpeg != null) {
                        out = job.out(base + ".jpg");
                        try (OutputStream os = new FileOutputStream(out)) {
                            os.write(jpeg);
                        }
                    } else {
                        Bitmap bmp = img.getImage();
                        if (bmp == null) {
                            continue;
                        }
                        out = job.out(base + ".png");
                        Images.save(bmp, "png", 100, out);
                        bmp.recycle();
                    }
                    files.add(out);
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    // unsupported image encoding (JBIG2, JPX ...): skip it
                }
            }
        }
    }

    /** Original JPEG bytes when the image is stored as plain DCT (lossless extraction). */
    static byte[] rawJpeg(PDImageXObject img) throws IOException {
        List<COSName> filters = img.getStream().getFilters();
        if (filters == null || filters.size() != 1 || !COSName.DCT_DECODE.equals(filters.get(0))) {
            return null;
        }
        String cs = img.getColorSpace() == null ? "" : img.getColorSpace().getName();
        if (cs.contains("CMYK") || cs.equals("ICCBased") && img.getColorSpace().getNumberOfComponents() == 4) {
            return null; // CMYK JPEGs look wrong in most viewers: decode instead
        }
        try (InputStream in = img.getCOSObject().createRawInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            Sources.copy(in, bos);
            byte[] data = bos.toByteArray();
            return Images.isJpeg(data) ? data : null;
        }
    }
}
