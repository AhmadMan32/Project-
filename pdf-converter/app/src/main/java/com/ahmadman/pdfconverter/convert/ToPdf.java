package com.ahmadman.pdfconverter.convert;

import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ImageSpan;

import com.ahmadman.pdfconverter.R;
import com.ahmadman.pdfconverter.core.Doc;
import com.ahmadman.pdfconverter.core.DocxReader;
import com.ahmadman.pdfconverter.core.PptxReader;
import com.ahmadman.pdfconverter.core.TextReaders;
import com.ahmadman.pdfconverter.core.XlsxReader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Images, Word, Excel, PowerPoint, text, Markdown, CSV and HTML -> PDF. */
public final class ToPdf {

    private static final long MAX_IMAGE_BYTES = 80L * 1024 * 1024;

    private ToPdf() {
    }

    // ---------------------------------------------------------------------------------------
    // Images -> PDF (PDFBox keeps original JPEG data: small files, no quality loss)
    // ---------------------------------------------------------------------------------------

    public static List<File> images(Job job) throws Exception {
        String pageOpt = job.opt("page", "a4");
        float margin = marginOf(job.opt("margin", "small"));
        boolean compress = job.opt("quality", "original").equals("compressed");
        File out = job.out(job.baseName + ".pdf");
        try (PDDocument doc = new PDDocument(MemoryUsageSetting.setupMixed(32L * 1024 * 1024))) {
            int n = job.inputs.size();
            for (int i = 0; i < n; i++) {
                job.progress(i, n, job.string(R.string.progress_image, i + 1, n));
                String name = Sources.displayName(job.context, job.inputs.get(i));
                byte[] data = Sources.readBytes(job.context, job.inputs.get(i), MAX_IMAGE_BYTES);
                PDImageXObject img = imageObject(doc, data, compress);
                if (img == null) {
                    throw new Job.UserException(job.string(R.string.err_not_image, name));
                }
                float iw = img.getWidth() * 0.75f; // 96 dpi pixels -> points
                float ih = img.getHeight() * 0.75f;
                float pw;
                float ph;
                if (pageOpt.equals("fit")) {
                    float s = Math.min(1f, 14400f / Math.max(iw, ih));
                    pw = iw * s + 2 * margin;
                    ph = ih * s + 2 * margin;
                } else if (pageOpt.equals("auto")) {
                    float[] a4 = iw > ih ? new float[]{PdfComposer.A4_H, PdfComposer.A4_W}
                            : new float[]{PdfComposer.A4_W, PdfComposer.A4_H};
                    pw = a4[0];
                    ph = a4[1];
                } else {
                    float[] s = PdfComposer.pageSize(pageOpt);
                    pw = s[0];
                    ph = s[1];
                }
                PDPage page = new PDPage(new PDRectangle(pw, ph));
                doc.addPage(page);
                float s = Math.min((pw - 2 * margin) / iw, (ph - 2 * margin) / ih);
                float dw = iw * s;
                float dh = ih * s;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.drawImage(img, (pw - dw) / 2, (ph - dh) / 2, dw, dh);
                }
            }
            job.progress(n, n, job.string(R.string.progress_writing));
            doc.save(out);
        }
        return Collections.singletonList(out);
    }

    private static float marginOf(String m) {
        switch (m) {
            case "none":
                return 0;
            case "large":
                return 48;
            default:
                return 18;
        }
    }

    private static float documentMargin(String m) {
        switch (m) {
            case "small":
                return 36;
            case "large":
                return 72;
            default:
                return 56;
        }
    }

    private static PDImageXObject imageObject(PDDocument doc, byte[] data, boolean compress) throws Exception {
        if (Images.isJpeg(data) && !compress && Images.exifRotation(data) == 0) {
            try {
                return JPEGFactory.createFromByteArray(doc, data);
            } catch (RuntimeException e) {
                // unusual JPEG (CMYK, progressive variants): decode below
            }
        }
        Bitmap bmp = Images.decode(data, compress ? 2000 : 4000);
        if (bmp == null) {
            return null;
        }
        try {
            if (bmp.hasAlpha() && !compress) {
                return LosslessFactory.createFromImage(doc, bmp);
            }
            Bitmap flat = bmp.hasAlpha() ? Images.flatten(bmp) : bmp;
            PDImageXObject img = JPEGFactory.createFromImage(doc, flat, compress ? 0.75f : 0.92f);
            if (flat != bmp) {
                flat.recycle();
            }
            return img;
        } finally {
            bmp.recycle();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Documents -> PDF
    // ---------------------------------------------------------------------------------------

    /** Detects the input type and lays it out as PDF. */
    public static List<File> document(Job job) throws Exception {
        String name = Sources.displayName(job.context, job.inputs.get(0));
        String ext = Sources.extension(name);
        String mime = Sources.mime(job.context, job.inputs.get(0));
        job.progress(0, 1, job.string(R.string.progress_reading_file));
        Doc doc;
        try (InputStream in = Sources.open(job.context, job.inputs.get(0))) {
            if (ext.equals("docx") || ext.equals("docm") || mime.contains("wordprocessingml")) {
                doc = DocxReader.read(in);
            } else if (ext.equals("xlsx") || ext.equals("xlsm") || mime.contains("spreadsheetml")) {
                doc = XlsxReader.read(in);
            } else if (ext.equals("pptx") || ext.equals("pptm") || mime.contains("presentationml")) {
                doc = PptxReader.read(in);
            } else if (ext.equals("csv") || ext.equals("tsv") || mime.contains("csv")
                    || mime.equals("text/tab-separated-values")) {
                doc = TextReaders.csv(Sources.readText(job.context, job.inputs.get(0)));
            } else if (ext.equals("md") || ext.equals("markdown") || mime.equals("text/markdown")) {
                doc = TextReaders.markdown(Sources.readText(job.context, job.inputs.get(0)));
            } else if (ext.equals("html") || ext.equals("htm") || ext.equals("xhtml") || mime.contains("html")) {
                doc = html(Sources.readText(job.context, job.inputs.get(0)));
            } else if (ext.equals("doc") || ext.equals("xls") || ext.equals("ppt")) {
                throw new Job.UserException(job.string(R.string.err_legacy_office, ext.toUpperCase(Locale.ROOT)));
            } else if (mime.startsWith("text/") || ext.equals("txt") || ext.equals("log") || ext.equals("json")
                    || ext.equals("xml") || ext.equals("java") || ext.equals("ini")) {
                doc = TextReaders.plainText(Sources.readText(job.context, job.inputs.get(0)));
            } else {
                throw new Job.UserException(job.string(R.string.err_unsupported, ext.isEmpty() ? mime : ext));
            }
        }
        File out = job.out(job.baseName + ".pdf");
        if (doc.slides != null) {
            PdfComposer.renderSlides(job, doc, out);
            return Collections.singletonList(out);
        }
        if (doc.blocks.isEmpty()) {
            throw new Job.UserException(job.string(R.string.err_empty_doc));
        }
        float[] size = PdfComposer.pageSize(job.opt("page", "a4"));
        float font = job.optInt("fontsize", 11);
        PdfComposer c = new PdfComposer(job, size[0], size[1], documentMargin(job.opt("margin", "normal")), font);
        try {
            c.render(doc);
            job.progress(1, 1, job.string(R.string.progress_writing));
            c.writeTo(out);
        } catch (Exception e) {
            c.abort();
            throw e;
        }
        return Collections.singletonList(out);
    }

    /** HTML via Android's parser: headings, bold/italic, lists, links keep their styling. */
    @SuppressWarnings("deprecation")
    static Doc html(String source) {
        String cleaned = source
                .replaceAll("(?is)<!--.*?-->", "")
                .replaceAll("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>", "")
                .replaceAll("(?is)<head[^>]*>.*?</head>", "");
        Html.ImageGetter noImages = s -> {
            Drawable d = new ColorDrawable(0);
            d.setBounds(0, 0, 0, 0);
            return d;
        };
        Spanned spanned = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                ? Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY, noImages, null)
                : Html.fromHtml(cleaned, noImages, null);
        SpannableStringBuilder sb = new SpannableStringBuilder(spanned);
        for (ImageSpan span : sb.getSpans(0, sb.length(), ImageSpan.class)) {
            int start = sb.getSpanStart(span);
            int end = sb.getSpanEnd(span);
            sb.removeSpan(span);
            if (start >= 0 && end > start) {
                sb.delete(start, end);
            }
        }
        while (sb.length() > 0 && Character.isWhitespace(sb.charAt(sb.length() - 1))) {
            sb.delete(sb.length() - 1, sb.length());
        }
        Doc doc = new Doc();
        Doc.Para p = new Doc.Para();
        p.styled = sb;
        doc.blocks.add(p);
        return doc;
    }
}
