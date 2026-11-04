package com.ahmadman.pdfconverter.convert;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.ahmadman.pdfconverter.core.PdfText;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Opening, rendering and reading PDFs (PDFBox for structure/text, PdfRenderer for pixels). */
public final class Pdfs {

    /** Upper bound for one rendered page, to stay clear of out-of-memory errors. */
    private static final long MAX_PIXELS = 24_000_000L;
    private static final int MAX_SIDE = 8000;

    private Pdfs() {
    }

    /** A PDF input copied into the work folder. */
    public static File input(Job job, int index) throws IOException {
        String name = Sources.displayName(job.context, job.inputs.get(index));
        return Sources.copyToFile(job.context, job.inputs.get(index), job.workDir,
                "in" + index + "_" + Sources.stem(name) + ".pdf");
    }

    /** Loads with PDFBox, using temp files for big documents; wrong/missing password -> PasswordException. */
    public static PDDocument load(Job job, File file, String displayName) throws Job.PasswordException, IOException {
        try {
            return PDDocument.load(file, job.password, MemoryUsageSetting.setupMixed(48L * 1024 * 1024));
        } catch (InvalidPasswordException e) {
            throw new Job.PasswordException(displayName);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Rendering
    // ---------------------------------------------------------------------------------------

    /** PdfRenderer wrapper that transparently decrypts password protected files first. */
    public static final class Renderer implements Closeable {
        private final ParcelFileDescriptor fd;
        private final PdfRenderer renderer;

        public Renderer(Job job, File file, String displayName) throws IOException, Job.PasswordException {
            File source = file;
            PdfRenderer r;
            ParcelFileDescriptor d = ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                r = new PdfRenderer(d);
            } catch (SecurityException | IOException e) {
                d.close();
                // Encrypted (or slightly broken): let PDFBox decrypt / repair it, then render the copy.
                File plain = new File(job.workDir, "plain_" + file.getName());
                try (PDDocument doc = load(job, file, displayName)) {
                    doc.setAllSecurityToBeRemoved(true);
                    doc.save(plain);
                }
                d = ParcelFileDescriptor.open(plain, ParcelFileDescriptor.MODE_READ_ONLY);
                try {
                    r = new PdfRenderer(d);
                } catch (SecurityException | IOException e2) {
                    d.close();
                    throw new IOException("cannot render PDF", e2);
                }
            }
            fd = d;
            renderer = r;
        }

        public int pageCount() {
            return renderer.getPageCount();
        }

        /** Page size in points. */
        public float[] size(int index) {
            synchronized (renderer) {
                try (PdfRenderer.Page p = renderer.openPage(index)) {
                    return new float[]{p.getWidth(), p.getHeight()};
                }
            }
        }

        /**
         * Renders a page (0-based) at the given DPI on a white background. The resolution is
         * reduced automatically for very large pages.
         */
        public Bitmap render(int index, int dpi) {
            synchronized (renderer) {
                try (PdfRenderer.Page p = renderer.openPage(index)) {
                    float scale = dpi / 72f;
                    float w = p.getWidth() * scale;
                    float h = p.getHeight() * scale;
                    float limit = 1f;
                    if (w * h > MAX_PIXELS) {
                        limit = (float) Math.sqrt(MAX_PIXELS / (w * h));
                    }
                    if (Math.max(w, h) * limit > MAX_SIDE) {
                        limit = MAX_SIDE / Math.max(w, h);
                    }
                    scale *= limit;
                    int bw = Math.max(1, Math.round(p.getWidth() * scale));
                    int bh = Math.max(1, Math.round(p.getHeight() * scale));
                    Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                    return bmp;
                }
            }
        }

        @Override
        public void close() throws IOException {
            renderer.close();
            fd.close();
        }
    }

    // ---------------------------------------------------------------------------------------
    // Text with positions
    // ---------------------------------------------------------------------------------------

    /** Extracts positioned words for the selected pages (1-based, in the given order). */
    public static List<PdfText.Page> text(Job job, PDDocument doc, List<Integer> pages) throws IOException,
            Job.CancelledException {
        List<PdfText.Page> out = new ArrayList<>();
        int done = 0;
        for (int number : pages) {
            job.progress(done++, pages.size(), job.string(com.ahmadman.pdfconverter.R.string.progress_reading_page, number));
            PositionStripper s = new PositionStripper();
            s.setSortByPosition(true);
            s.setStartPage(number);
            s.setEndPage(number);
            s.writeText(doc, new StringWriter());
            PDPage page = doc.getPage(number - 1);
            PDRectangle box = page.getCropBox();
            boolean rotated = page.getRotation() % 180 != 0;
            PdfText.Page p = new PdfText.Page(number, rotated ? box.getHeight() : box.getWidth(),
                    rotated ? box.getWidth() : box.getHeight());
            p.lines.addAll(s.lines);
            out.add(p);
        }
        return out;
    }

    /** PDFTextStripper that keeps words with their positions and font information. */
    private static final class PositionStripper extends PDFTextStripper {
        final List<PdfText.Line> lines = new ArrayList<>();
        private PdfText.Line current = new PdfText.Line();

        PositionStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (positions == null || positions.isEmpty() || text.trim().isEmpty()) {
                return;
            }
            TextPosition first = positions.get(0);
            TextPosition last = positions.get(positions.size() - 1);
            float size = 0;
            float height = 0;
            for (TextPosition t : positions) {
                size += t.getFontSizeInPt();
                height = Math.max(height, t.getHeightDir());
            }
            size /= positions.size();
            String font = "";
            try {
                if (first.getFont() != null && first.getFont().getName() != null) {
                    font = first.getFont().getName().toLowerCase(Locale.ROOT);
                }
            } catch (RuntimeException ignored) {
                // unnamed font
            }
            boolean bold = font.contains("bold") || font.contains("black") || font.contains("heavy")
                    || font.contains("semibold") || font.contains(",b");
            boolean italic = font.contains("italic") || font.contains("oblique");
            float x = first.getXDirAdj();
            float right = last.getXDirAdj() + last.getWidthDirAdj();
            current.words.add(new PdfText.Word(text.trim(), x, first.getYDirAdj(), Math.max(0, right - x),
                    height, size, bold, italic, first.getWidthOfSpace()));
        }

        @Override
        protected void writeLineSeparator() {
            endLine();
        }

        @Override
        protected void endPage(PDPage page) throws IOException {
            endLine();
            super.endPage(page);
        }

        private void endLine() {
            if (!current.words.isEmpty()) {
                lines.add(current);
                current = new PdfText.Line();
            }
        }
    }
}
