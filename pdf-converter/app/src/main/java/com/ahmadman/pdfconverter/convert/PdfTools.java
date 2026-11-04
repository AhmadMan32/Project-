package com.ahmadman.pdfconverter.convert;

import android.graphics.Bitmap;

import com.ahmadman.pdfconverter.R;
import com.ahmadman.pdfconverter.core.PageSpec;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import com.tom_roush.pdfbox.util.Matrix;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Merge, split, pick/reorder/delete/rotate pages, compress, protect, unlock, stamp, grayscale. */
public final class PdfTools {

    private static final String FONT = "com/tom_roush/pdfbox/resources/ttf/LiberationSans-Regular.ttf";

    private PdfTools() {
    }

    private static MemoryUsageSetting memory() {
        return MemoryUsageSetting.setupMixed(48L * 1024 * 1024);
    }

    /** Loads input {@code i} (copied into the work folder). */
    private static PDDocument open(Job job, int i) throws Exception {
        File f = Pdfs.input(job, i);
        return Pdfs.load(job, f, Sources.displayName(job.context, job.inputs.get(i)));
    }

    private static File save(Job job, PDDocument doc, String name) throws IOException, Job.CancelledException {
        job.progress(1, 1, job.string(R.string.progress_writing));
        File out = job.out(name);
        doc.save(out);
        return out;
    }

    private static List<List<Integer>> groups(Job job, String spec, int count) throws Job.UserException {
        try {
            return PageSpec.parseGroups(spec, count);
        } catch (PageSpec.SpecException e) {
            throw new Job.UserException(job.string(R.string.err_pages, e.token));
        }
    }

    // ---------------------------------------------------------------------------------------

    public static List<File> merge(Job job) throws Exception {
        List<PDDocument> sources = new ArrayList<>();
        try (PDDocument dest = new PDDocument(memory())) {
            PDFMergerUtility merger = new PDFMergerUtility();
            for (int i = 0; i < job.inputs.size(); i++) {
                job.progress(i, job.inputs.size(), job.string(R.string.progress_file, i + 1, job.inputs.size()));
                PDDocument src = open(job, i);
                sources.add(src); // must stay open until the merged file is saved
                merger.appendDocument(dest, src);
            }
            return Collections.singletonList(save(job, dest, job.baseName + "_" + job.string(R.string.file_merged) + ".pdf"));
        } finally {
            for (PDDocument d : sources) {
                d.close();
            }
        }
    }

    public static List<File> split(Job job) throws Exception {
        List<File> out = new ArrayList<>();
        try (PDDocument src = open(job, 0)) {
            int count = src.getNumberOfPages();
            List<List<Integer>> parts = new ArrayList<>();
            String mode = job.opt("mode", "each");
            if (mode.equals("ranges")) {
                parts = groups(job, job.opt("ranges", ""), count);
            } else {
                int every = mode.equals("every") ? Math.max(1, job.optInt("every", 2)) : 1;
                for (int start = 1; start <= count; start += every) {
                    List<Integer> g = new ArrayList<>();
                    for (int p = start; p < start + every && p <= count; p++) {
                        g.add(p);
                    }
                    parts.add(g);
                }
            }
            for (int i = 0; i < parts.size(); i++) {
                job.progress(i, parts.size(), job.string(R.string.progress_file, i + 1, parts.size()));
                List<Integer> g = parts.get(i);
                try (PDDocument part = new PDDocument(memory())) {
                    for (int p : g) {
                        part.importPage(src.getPage(p - 1));
                    }
                    String label = g.size() == 1 ? String.valueOf(g.get(0))
                            : g.get(0) + "-" + g.get(g.size() - 1);
                    File f = job.out(job.baseName + "_" + job.string(R.string.file_pages) + label + ".pdf");
                    part.save(f);
                    out.add(f);
                }
            }
        }
        if (job.optBool("zip", false) && out.size() > 1) {
            return Collections.singletonList(FromPdf.zip(job, out, job.baseName + "_" + job.string(R.string.file_parts) + ".zip"));
        }
        return out;
    }

    /** Keeps the selected pages in the given order (also used to reorder or duplicate). */
    public static List<File> extract(Job job) throws Exception {
        try (PDDocument src = open(job, 0); PDDocument dest = new PDDocument(memory())) {
            List<Integer> pages = FromPdf.pages(job, src.getNumberOfPages());
            if (job.opt("pages", "").trim().isEmpty() && job.optBool("reverse", false)) {
                Collections.reverse(pages);
            }
            for (int i = 0; i < pages.size(); i++) {
                if (i % 20 == 0) {
                    job.progress(i, pages.size(), job.string(R.string.progress_reading_page, pages.get(i)));
                }
                dest.importPage(src.getPage(pages.get(i) - 1));
            }
            return Collections.singletonList(save(job, dest, job.baseName + "_" + job.string(R.string.file_selected) + ".pdf"));
        }
    }

    public static List<File> delete(Job job) throws Exception {
        try (PDDocument doc = open(job, 0)) {
            int count = doc.getNumberOfPages();
            if (job.opt("pages", "").trim().isEmpty()) {
                throw new Job.UserException(job.string(R.string.err_pages_required));
            }
            Set<Integer> remove;
            try {
                remove = PageSpec.parseSet(job.opt("pages", ""), count);
            } catch (PageSpec.SpecException e) {
                throw new Job.UserException(job.string(R.string.err_pages, e.token));
            }
            if (remove.size() >= count) {
                throw new Job.UserException(job.string(R.string.err_all_deleted));
            }
            List<Integer> desc = new ArrayList<>(remove);
            Collections.sort(desc, Collections.reverseOrder());
            for (int p : desc) {
                doc.removePage(p - 1);
            }
            return Collections.singletonList(save(job, doc, job.baseName + "_" + job.string(R.string.file_edited) + ".pdf"));
        }
    }

    public static List<File> rotate(Job job) throws Exception {
        int angle = job.optInt("angle", 90);
        try (PDDocument doc = open(job, 0)) {
            for (int p : FromPdf.pages(job, doc.getNumberOfPages())) {
                PDPage page = doc.getPage(p - 1);
                page.setRotation(((page.getRotation() + angle) % 360 + 360) % 360);
            }
            return Collections.singletonList(save(job, doc, job.baseName + "_" + job.string(R.string.file_rotated) + ".pdf"));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Compress: re-encode big pictures as JPEG (text and vectors stay sharp)
    // ---------------------------------------------------------------------------------------

    public static List<File> compress(Job job) throws Exception {
        String level = job.opt("level", "medium");
        float quality = level.equals("low") ? 0.82f : level.equals("high") ? 0.45f : 0.65f;
        int maxSide = level.equals("low") ? 2500 : level.equals("high") ? 1100 : 1700;
        try (PDDocument doc = open(job, 0)) {
            IdentityHashMap<Object, Boolean> done = new IdentityHashMap<>();
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                job.progress(i, n, job.string(R.string.progress_compress_page, i + 1));
                recompress(job, doc, doc.getPage(i).getResources(), quality, maxSide, done, 0);
            }
            return Collections.singletonList(save(job, doc, job.baseName + "_" + job.string(R.string.file_compressed) + ".pdf"));
        }
    }

    private static void recompress(Job job, PDDocument doc, PDResources res, float quality, int maxSide,
                                   IdentityHashMap<Object, Boolean> done, int depth) throws Job.CancelledException {
        if (res == null || depth > 8) {
            return;
        }
        List<COSName> names = new ArrayList<>();
        for (COSName n : res.getXObjectNames()) {
            names.add(n);
        }
        for (COSName name : names) {
            job.check();
            try {
                PDXObject x = res.getXObject(name);
                if (x == null || done.containsKey(x.getCOSObject())) {
                    continue;
                }
                done.put(x.getCOSObject(), Boolean.TRUE);
                if (x instanceof PDFormXObject) {
                    recompress(job, doc, ((PDFormXObject) x).getResources(), quality, maxSide, done, depth + 1);
                    continue;
                }
                if (!(x instanceof PDImageXObject)) {
                    continue;
                }
                PDImageXObject img = (PDImageXObject) x;
                long before = img.getStream().getLength();
                if (img.isStencil() || img.getSoftMask() != null || before < 40_000
                        || img.getBitsPerComponent() == 1) {
                    continue; // masks, transparency, tiny or black/white scans: leave as is
                }
                Bitmap bmp = img.getImage();
                if (bmp == null) {
                    continue;
                }
                Bitmap scaled = bmp;
                int side = Math.max(bmp.getWidth(), bmp.getHeight());
                if (side > maxSide) {
                    float s = maxSide / (float) side;
                    scaled = Bitmap.createScaledBitmap(bmp, Math.max(1, Math.round(bmp.getWidth() * s)),
                            Math.max(1, Math.round(bmp.getHeight() * s)), true);
                }
                Bitmap flat = scaled.hasAlpha() ? Images.flatten(scaled) : scaled;
                PDImageXObject smaller = JPEGFactory.createFromImage(doc, flat, quality);
                if (smaller.getStream().getLength() < before * 0.9) {
                    res.put(name, smaller);
                    done.put(smaller.getCOSObject(), Boolean.TRUE);
                }
                if (flat != scaled) {
                    flat.recycle();
                }
                if (scaled != bmp) {
                    scaled.recycle();
                }
                bmp.recycle();
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // undecodable image (JBIG2/JPX ...): keep the original
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Security
    // ---------------------------------------------------------------------------------------

    public static List<File> protect(Job job) throws Exception {
        String pw = job.opt("password", "");
        if (pw.isEmpty()) {
            throw new Job.UserException(job.string(R.string.err_password_empty));
        }
        if (!pw.equals(job.opt("password2", ""))) {
            throw new Job.UserException(job.string(R.string.err_password_mismatch));
        }
        try (PDDocument doc = open(job, 0)) {
            if (doc.isEncrypted()) {
                doc.setAllSecurityToBeRemoved(true);
            }
            AccessPermission ap = new AccessPermission();
            ap.setCanPrint(job.optBool("allowPrint", true));
            ap.setCanExtractContent(job.optBool("allowCopy", true));
            ap.setCanModify(job.optBool("allowEdit", false));
            // random owner password: the restrictions above really apply to the user password
            StandardProtectionPolicy policy = new StandardProtectionPolicy(UUID.randomUUID().toString(), pw, ap);
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);
            return Collections.singletonList(save(job, doc, job.baseName + "_" + job.string(R.string.file_protected) + ".pdf"));
        }
    }

    public static List<File> unlock(Job job) throws Exception {
        try (PDDocument doc = open(job, 0)) {
            if (!doc.isEncrypted()) {
                throw new Job.UserException(job.string(R.string.err_not_encrypted));
            }
            doc.setAllSecurityToBeRemoved(true);
            return Collections.singletonList(save(job, doc, job.baseName + "_" + job.string(R.string.file_unlocked) + ".pdf"));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Stamps
    // ---------------------------------------------------------------------------------------

    private static PDFont font(Job job, PDDocument doc) throws IOException {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(job.context);
        }
        try (InputStream in = PDFBoxResourceLoader.getStream(FONT)) {
            return PDType0Font.load(doc, in, true);
        }
    }

    /** Removes characters the font cannot draw (emoji ...), so showText cannot fail. */
    private static String drawable(PDFont font, String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            try {
                font.getStringWidth(ch);
                font.encode(ch);
                sb.append(ch);
            } catch (IOException | RuntimeException e) {
                sb.append('?');
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static float[] rgb(String color) {
        switch (color) {
            case "red":
                return new float[]{0.86f, 0.15f, 0.15f};
            case "blue":
                return new float[]{0.11f, 0.35f, 0.85f};
            case "black":
                return new float[]{0.1f, 0.1f, 0.1f};
            default:
                return new float[]{0.45f, 0.45f, 0.45f};
        }
    }

    public static List<File> watermark(Job job) throws Exception {
        String raw = job.opt("text", "").trim();
        if (raw.isEmpty()) {
            throw new Job.UserException(job.string(R.string.err_text_empty));
        }
        float opacity = Math.max(5, Math.min(100, job.optInt("opacity", 25))) / 100f;
        float[] color = rgb(job.opt("color", "gray"));
        boolean diagonal = job.opt("layout", "diagonal").equals("diagonal");
        try (PDDocument doc = open(job, 0)) {
            PDFont font = font(job, doc);
            String text = drawable(font, raw);
            float unitWidth = font.getStringWidth(text) / 1000f;
            PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
            gs.setNonStrokingAlphaConstant(opacity);
            List<Integer> pages = FromPdf.pages(job, doc.getNumberOfPages());
            for (int i = 0; i < pages.size(); i++) {
                job.progress(i, pages.size(), job.string(R.string.progress_reading_page, pages.get(i)));
                PDPage page = doc.getPage(pages.get(i) - 1);
                PDRectangle box = page.getCropBox();
                float w = box.getWidth();
                float h = box.getHeight();
                double angle = diagonal ? Math.atan2(h, w) : 0;
                float span = diagonal ? (float) Math.hypot(w, h) * 0.75f : w * 0.8f;
                float size = Math.min(job.optInt("size", 60), span / Math.max(0.01f, unitWidth));
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setGraphicsStateParameters(gs);
                    cs.setNonStrokingColor(color[0], color[1], color[2]);
                    cs.transform(Matrix.getTranslateInstance(box.getLowerLeftX() + w / 2, box.getLowerLeftY() + h / 2));
                    cs.transform(Matrix.getRotateInstance(angle, 0, 0));
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.newLineAtOffset(-unitWidth * size / 2, -size * 0.35f);
                    cs.showText(text);
                    cs.endText();
                }
            }
            return Collections.singletonList(save(job, doc, job.baseName + "_" + job.string(R.string.file_watermarked) + ".pdf"));
        }
    }

    public static List<File> pageNumbers(Job job) throws Exception {
        String position = job.opt("position", "bottom-center");
        String format = job.opt("format", "n");
        int start = job.optInt("start", 1);
        float size = Math.max(6, Math.min(24, job.optInt("size", 10)));
        try (PDDocument doc = open(job, 0)) {
            PDFont font = font(job, doc);
            int total = doc.getNumberOfPages();
            List<Integer> pages = FromPdf.pages(job, total);
            int lastNumber = start + total - 1;
            for (int i = 0; i < pages.size(); i++) {
                int p = pages.get(i);
                if (i % 10 == 0) {
                    job.progress(i, pages.size(), job.string(R.string.progress_reading_page, p));
                }
                int number = start + p - 1;
                String label;
                switch (format) {
                    case "n/N":
                        label = number + " / " + lastNumber;
                        break;
                    case "page":
                        label = job.string(R.string.page_label, number);
                        break;
                    default:
                        label = String.valueOf(number);
                }
                label = drawable(font, label);
                PDPage page = doc.getPage(p - 1);
                PDRectangle box = page.getCropBox();
                float tw = font.getStringWidth(label) / 1000f * size;
                float margin = 28;
                float x;
                if (position.endsWith("left")) {
                    x = box.getLowerLeftX() + margin;
                } else if (position.endsWith("right")) {
                    x = box.getUpperRightX() - margin - tw;
                } else {
                    x = box.getLowerLeftX() + (box.getWidth() - tw) / 2;
                }
                float y = position.startsWith("top") ? box.getUpperRightY() - margin : box.getLowerLeftY() + margin - size / 2;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                        PDPageContentStream.AppendMode.APPEND, true, true)) {
                    cs.setNonStrokingColor(0.2f, 0.2f, 0.2f);
                    cs.beginText();
                    cs.setFont(font, size);
                    cs.newLineAtOffset(x, y);
                    cs.showText(label);
                    cs.endText();
                }
            }
            return Collections.singletonList(save(job, doc, job.baseName + "_" + job.string(R.string.file_numbered) + ".pdf"));
        }
    }

    /** Re-renders pages as pictures: black & white, or "flattened" in color. */
    public static List<File> rasterize(Job job) throws Exception {
        File f = Pdfs.input(job, 0);
        String name = Sources.displayName(job.context, job.inputs.get(0));
        boolean gray = job.opt("color", "gray").equals("gray");
        int dpi = job.optInt("dpi", 150);
        try (Pdfs.Renderer r = new Pdfs.Renderer(job, f, name);
             PDDocument out = new PDDocument(memory())) {
            List<Integer> pages = FromPdf.pages(job, r.pageCount());
            for (int i = 0; i < pages.size(); i++) {
                job.progress(i, pages.size(), job.string(R.string.progress_rendering_page, pages.get(i)));
                float[] size = r.size(pages.get(i) - 1);
                Bitmap bmp = r.render(pages.get(i) - 1, dpi);
                Bitmap use = gray ? Images.grayscale(bmp) : bmp;
                PDImageXObject img = JPEGFactory.createFromImage(out, use, 0.8f);
                if (use != bmp) {
                    use.recycle();
                }
                bmp.recycle();
                PDPage page = new PDPage(new PDRectangle(size[0], size[1]));
                out.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(out, page)) {
                    cs.drawImage(img, 0, 0, size[0], size[1]);
                }
            }
            String suffix = job.string(gray ? R.string.file_gray : R.string.file_flattened);
            return Collections.singletonList(save(job, out, job.baseName + "_" + suffix + ".pdf"));
        }
    }
}
