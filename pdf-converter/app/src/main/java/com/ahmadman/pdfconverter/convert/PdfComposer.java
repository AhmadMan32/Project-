package com.ahmadman.pdfconverter.convert;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;

import com.ahmadman.pdfconverter.core.Doc;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Lays out a {@link Doc} on PDF pages with Android's PdfDocument: flowing text with styles,
 * lists, images, tables (repeating headers) and positioned slides.
 */
public final class PdfComposer {

    public static final float A4_W = 595.28f;
    public static final float A4_H = 841.89f;
    public static final float LETTER_W = 612f;
    public static final float LETTER_H = 792f;

    private final Job job;
    private final float pageW;
    private final float pageH;
    private final float margin;
    private final float baseSize;
    private final PdfDocument pdf = new PdfDocument();
    private PdfDocument.Page page;
    private Canvas canvas;
    private int pageNumber;
    private float y;

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

    public PdfComposer(Job job, float pageW, float pageH, float margin, float baseSize) {
        this.job = job;
        this.pageW = pageW;
        this.pageH = pageH;
        this.margin = margin;
        this.baseSize = baseSize;
        textPaint.setColor(Color.BLACK);
        textPaint.setTextSize(baseSize);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(0.6f);
        linePaint.setColor(0xFF9AA0A6);
    }

    /** Page size from the "page" option: a4 / a4l (landscape) / letter / letterl. */
    public static float[] pageSize(String option) {
        switch (option) {
            case "a4l":
                return new float[]{A4_H, A4_W};
            case "letter":
                return new float[]{LETTER_W, LETTER_H};
            case "letterl":
                return new float[]{LETTER_H, LETTER_W};
            default:
                return new float[]{A4_W, A4_H};
        }
    }

    private float contentW() {
        return pageW - 2 * margin;
    }

    private float bottom() {
        return pageH - margin;
    }

    private void newPage() throws Job.CancelledException {
        finishPage();
        job.check();
        pageNumber++;
        page = pdf.startPage(new PdfDocument.PageInfo.Builder(Math.round(pageW), Math.round(pageH), pageNumber).create());
        canvas = page.getCanvas();
        y = margin;
    }

    private void ensurePage() throws Job.CancelledException {
        if (page == null) {
            newPage();
        }
    }

    private void finishPage() {
        if (page != null) {
            pdf.finishPage(page);
            page = null;
            canvas = null;
        }
    }

    // ---------------------------------------------------------------------------------------
    // Flowing documents
    // ---------------------------------------------------------------------------------------

    public void render(Doc doc) throws Job.CancelledException {
        int total = doc.blocks.size();
        int i = 0;
        for (Doc.Block b : doc.blocks) {
            if (i % 20 == 0) {
                job.progress(i, total, job.string(com.ahmadman.pdfconverter.R.string.progress_layout));
            }
            i++;
            if (b instanceof Doc.Para) {
                para((Doc.Para) b);
            } else if (b instanceof Doc.Image) {
                image((Doc.Image) b);
            } else if (b instanceof Doc.Table) {
                table((Doc.Table) b);
            } else if (b instanceof Doc.PageBreak) {
                if (page != null && y > margin + 1) {
                    newPage();
                }
            } else if (b instanceof Doc.Rule) {
                ensurePage();
                float ry = Math.min(y + 6, bottom());
                canvas.drawLine(margin, ry, pageW - margin, ry, linePaint);
                y = ry + 10;
            }
        }
        ensurePage();
    }

    private void para(Doc.Para p) throws Job.CancelledException {
        if (p.pageBreakBefore && page != null && y > margin + 1) {
            newPage();
        }
        ensurePage();
        float size = p.heading > 0 ? baseSize * headingScale(p.heading) : baseSize;
        float spacingAfter = p.heading > 0 ? size * 0.45f : baseSize * 0.55f;
        if (p.heading > 0 && y > margin + 1) {
            y += size * 0.5f;
        }
        CharSequence text = styled(p, size);
        if (text.toString().trim().isEmpty()) {
            y += baseSize * 0.9f; // empty paragraph = blank line
            if (y > bottom()) {
                newPage();
            }
            return;
        }
        float indent = (p.bullet || p.number > 0 ? 16 : 0) + p.indentLevel * 18 + (p.quote ? 14 : 0);
        String marker = p.bullet ? "•" : p.number > 0 ? p.number + "." : null;
        TextPaint paint = new TextPaint(textPaint);
        paint.setTextSize(size);
        paint.setTypeface(p.heading > 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        StaticLayout layout = layout(text, paint, contentW() - indent, align(p.align));
        drawFlowing(layout, paint, margin + indent, marker, size, p.quote);
        y += spacingAfter;
    }

    private static float headingScale(int level) {
        switch (level) {
            case 1:
                return 1.9f;
            case 2:
                return 1.5f;
            case 3:
                return 1.25f;
            default:
                return 1.1f;
        }
    }

    /** Draws a layout line by line, continuing on new pages as needed. */
    private void drawFlowing(StaticLayout layout, TextPaint paint, float x, String marker, float size, boolean quote)
            throws Job.CancelledException {
        int lines = layout.getLineCount();
        int line = 0;
        boolean first = true;
        while (line < lines) {
            float available = bottom() - y;
            int end = line;
            float start = layout.getLineTop(line);
            while (end < lines && layout.getLineBottom(end) - start <= available) {
                end++;
            }
            if (end == line) {
                if (y > margin + 1) {
                    newPage();
                    continue;
                }
                end = line + 1; // a single line taller than the page
            }
            float height = layout.getLineBottom(end - 1) - start;
            canvas.save();
            canvas.translate(x, y - start);
            canvas.clipRect(0, start, layout.getWidth(), start + height);
            layout.draw(canvas);
            canvas.restore();
            if (first && marker != null) {
                TextPaint mp = new TextPaint(paint);
                mp.setTextSize(size);
                mp.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(marker, x - 5, y + layout.getLineBaseline(line) - start, mp);
            }
            if (quote) {
                Paint q = new Paint(fillPaint);
                q.setColor(0xFFB0B8C1);
                canvas.drawRect(x - 10, y, x - 7, y + height, q);
            }
            first = false;
            y += height;
            line = end;
            if (line < lines) {
                newPage();
            }
        }
    }

    private CharSequence styled(Doc.Para p, float size) {
        if (p.styled != null) {
            return p.styled;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (Doc.Run r : p.runs) {
            int start = sb.length();
            sb.append(r.text.replace('\t', ' ').replace("\u000B", "\n"));
            int end = sb.length();
            if (end == start) {
                continue;
            }
            int style = (r.bold ? Typeface.BOLD : 0) | (r.italic ? Typeface.ITALIC : 0);
            if (style != 0) {
                sb.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.underline) {
                sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.strike) {
                sb.setSpan(new StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.monospace) {
                sb.setSpan(new TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new BackgroundColorSpan(0xFFF1F3F5), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.size > 0 && p.heading == 0) {
                float s = Math.max(4, Math.min(96, r.size));
                sb.setSpan(new AbsoluteSizeSpan(Math.round(s)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (r.color >= 0) {
                sb.setSpan(new ForegroundColorSpan(0xFF000000 | r.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return sb;
    }

    private static Layout.Alignment align(int a) {
        switch (a) {
            case Doc.ALIGN_CENTER:
                return Layout.Alignment.ALIGN_CENTER;
            case Doc.ALIGN_RIGHT:
                return Layout.Alignment.ALIGN_OPPOSITE;
            default:
                return Layout.Alignment.ALIGN_NORMAL;
        }
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, float width, Layout.Alignment alignment) {
        int w = Math.max(10, Math.round(width));
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, w)
                .setAlignment(alignment)
                .setLineSpacing(0, 1.15f)
                .setIncludePad(false)
                .build();
    }

    private void image(Doc.Image img) throws Job.CancelledException {
        Bitmap bmp = Images.decode(img.data, 1800);
        if (bmp == null) {
            return; // unsupported format (EMF/WMF/SVG ...)
        }
        ensurePage();
        float w = img.width > 0 ? img.width : bmp.getWidth() * 0.75f;
        float h = img.height > 0 ? img.height : bmp.getHeight() * 0.75f;
        float maxH = pageH - 2 * margin;
        float s = Math.min(1f, Math.min(contentW() / w, maxH / h));
        w *= s;
        h *= s;
        if (y + h > bottom()) {
            newPage();
        }
        canvas.drawBitmap(bmp, null, new RectF(margin, y, margin + w, y + h), bitmapPaint);
        bmp.recycle();
        y += h + baseSize * 0.6f;
    }

    // ---------------------------------------------------------------------------------------
    // Tables
    // ---------------------------------------------------------------------------------------

    private void table(Doc.Table t) throws Job.CancelledException {
        if (t.rows.isEmpty()) {
            return;
        }
        ensurePage();
        if (t.caption != null && !t.caption.isEmpty()) {
            Doc.Para cap = new Doc.Para();
            cap.add(t.caption);
            cap.heading = 3;
            para(cap);
        }
        int cols = 0;
        for (List<String> r : t.rows) {
            cols = Math.max(cols, r.size());
        }
        if (cols == 0) {
            return;
        }
        float size = cols > 8 ? Math.max(5.5f, baseSize * 8 / cols) : baseSize * 0.9f;
        float[] widths = columnWidths(t, cols, size);
        float pad = 3f;
        List<String> header = t.header ? t.rows.get(0) : null;
        for (int r = 0; r < t.rows.size(); r++) {
            if (r % 50 == 0) {
                job.check();
            }
            List<String> row = t.rows.get(r);
            boolean isHeader = t.header && r == 0;
            StaticLayout[] cells = cellLayouts(row, cols, widths, size, pad, isHeader);
            float h = rowHeight(cells, pad, size);
            if (y + h > bottom() && y > margin + 1) {
                newPage();
                if (header != null && !isHeader) {
                    StaticLayout[] hc = cellLayouts(header, cols, widths, size, pad, true);
                    drawRow(hc, widths, rowHeight(hc, pad, size), pad, true);
                }
            }
            drawRow(cells, widths, h, pad, isHeader);
        }
        y += baseSize * 0.8f;
    }

    private StaticLayout[] cellLayouts(List<String> row, int cols, float[] widths, float size, float pad,
                                       boolean bold) {
        StaticLayout[] out = new StaticLayout[cols];
        TextPaint paint = new TextPaint(textPaint); // a layout keeps its paint: one per row
        paint.setTextSize(size);
        paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        for (int c = 0; c < cols; c++) {
            String v = c < row.size() && row.get(c) != null ? row.get(c) : "";
            if (v.length() > 2000) {
                v = v.substring(0, 2000) + "…";
            }
            out[c] = layout(v, paint, widths[c] - 2 * pad, Layout.Alignment.ALIGN_NORMAL);
        }
        return out;
    }

    private float rowHeight(StaticLayout[] cells, float pad, float size) {
        float h = size * 1.2f;
        for (StaticLayout l : cells) {
            h = Math.max(h, l.getHeight());
        }
        return Math.min(h + 2 * pad, pageH - 2 * margin);
    }

    private void drawRow(StaticLayout[] cells, float[] widths, float h, float pad, boolean header) {
        float x = margin;
        if (header) {
            Paint bg = new Paint(fillPaint);
            bg.setColor(0xFFEEF1F5);
            float total = 0;
            for (float w : widths) {
                total += w;
            }
            canvas.drawRect(margin, y, margin + total, y + h, bg);
        }
        for (int c = 0; c < cells.length; c++) {
            canvas.save();
            canvas.clipRect(x, y, x + widths[c], y + h);
            canvas.translate(x + pad, y + pad);
            cells[c].draw(canvas);
            canvas.restore();
            canvas.drawRect(x, y, x + widths[c], y + h, linePaint);
            x += widths[c];
        }
        y += h;
    }

    /** Column widths proportional to content length, shrunk to fit the page width. */
    private float[] columnWidths(Doc.Table t, int cols, float size) {
        float[] want = new float[cols];
        textPaint.setTextSize(size);
        int sample = Math.min(t.rows.size(), 200);
        for (int r = 0; r < sample; r++) {
            List<String> row = t.rows.get(r);
            for (int c = 0; c < Math.min(cols, row.size()); c++) {
                String v = row.get(c) == null ? "" : row.get(c);
                String longest = v;
                if (v.indexOf('\n') >= 0) {
                    longest = "";
                    for (String part : v.split("\n")) {
                        if (part.length() > longest.length()) {
                            longest = part;
                        }
                    }
                }
                if (longest.length() > 60) {
                    longest = longest.substring(0, 60);
                }
                want[c] = Math.max(want[c], textPaint.measureText(longest) + 8);
            }
        }
        float min = Math.min(28, contentW() / cols);
        float total = 0;
        for (int c = 0; c < cols; c++) {
            want[c] = Math.max(min, want[c]);
            total += want[c];
        }
        if (total > contentW()) {
            // shrink wide columns first, keep narrow ones readable
            float scale = contentW() / total;
            total = 0;
            for (int c = 0; c < cols; c++) {
                want[c] = Math.max(min, want[c] * scale);
                total += want[c];
            }
            float fix = contentW() / total;
            for (int c = 0; c < cols; c++) {
                want[c] *= fix;
            }
        }
        return want;
    }

    // ---------------------------------------------------------------------------------------
    // Slides
    // ---------------------------------------------------------------------------------------

    /** Renders each slide on its own page of the slide's size. */
    public static void renderSlides(Job job, Doc doc, File out) throws IOException, Job.CancelledException {
        PdfDocument pdf = new PdfDocument();
        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1f);
        Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        try {
            int n = 0;
            for (Doc.Slide s : doc.slides) {
                job.progress(n, doc.slides.size(), job.string(com.ahmadman.pdfconverter.R.string.progress_slide, n + 1));
                n++;
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(
                        Math.max(1, Math.round(s.width)), Math.max(1, Math.round(s.height)), n).create());
                Canvas c = page.getCanvas();
                c.drawColor(0xFF000000 | s.background);
                for (Doc.Shape sh : s.shapes) {
                    RectF r = new RectF(sh.x, sh.y, sh.x + sh.w, sh.y + sh.h);
                    if (sh.fill >= 0) {
                        fill.setColor(0xFF000000 | sh.fill);
                        c.drawRect(r, fill);
                    }
                    if (sh.line >= 0) {
                        stroke.setColor(0xFF000000 | sh.line);
                        c.drawRect(r, stroke);
                    }
                    if (sh.image != null) {
                        Bitmap b = Images.decode(sh.image, 1600);
                        if (b != null) {
                            c.drawBitmap(b, null, r, bmpPaint);
                            b.recycle();
                        }
                    }
                    if (sh.table != null) {
                        slideTable(c, sh, tp);
                    }
                    if (!sh.paras.isEmpty()) {
                        slideText(c, sh, tp);
                    }
                }
                pdf.finishPage(page);
            }
            if (n == 0) {
                throw new IOException("presentation has no slides");
            }
            try (OutputStream os = new FileOutputStream(out)) {
                pdf.writeTo(os);
            }
        } finally {
            pdf.close();
        }
    }

    private static StaticLayout slideLayout(CharSequence text, TextPaint tp, int width, Layout.Alignment a) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), tp, Math.max(10, width))
                .setAlignment(a).setLineSpacing(0, 1.05f).setIncludePad(false).build();
    }

    private static void slideText(Canvas c, Doc.Shape sh, TextPaint tp) {
        float inset = 7.2f;
        int width = Math.round(sh.w - 2 * inset);
        List<StaticLayout> layouts = new ArrayList<>();
        List<Float> indents = new ArrayList<>();
        float total = 0;
        for (Doc.Para p : sh.paras) {
            SpannableStringBuilder sb = new SpannableStringBuilder();
            float indent = p.indentLevel * 18f;
            if (p.bullet && !p.isEmpty()) {
                sb.append("• ");
            }
            float maxSize = sh.fontSize;
            for (Doc.Run r : p.runs) {
                int start = sb.length();
                sb.append(r.text);
                int end = sb.length();
                if (end == start) {
                    continue;
                }
                int style = (r.bold || sh.title ? Typeface.BOLD : 0) | (r.italic ? Typeface.ITALIC : 0);
                if (style != 0) {
                    sb.setSpan(new StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (r.underline) {
                    sb.setSpan(new UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                if (r.size > 0) {
                    sb.setSpan(new AbsoluteSizeSpan(Math.round(r.size)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    maxSize = Math.max(maxSize, r.size);
                }
                if (r.color >= 0) {
                    sb.setSpan(new ForegroundColorSpan(0xFF000000 | r.color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
            if (sb.length() == 0) {
                sb.append(' ');
            }
            TextPaint paint = new TextPaint(tp);
            paint.setTextSize(sh.fontSize);
            paint.setColor(Color.BLACK);
            paint.setTypeface(Typeface.DEFAULT);
            StaticLayout l = slideLayout(sb, paint, Math.round(width - indent), align(p.align));
            layouts.add(l);
            indents.add(indent);
            total += l.getHeight() + maxSize * 0.2f;
        }
        float top = sh.y + inset;
        float space = sh.h - 2 * inset;
        if (sh.vAnchor == 1) {
            top += Math.max(0, (space - total) / 2);
        } else if (sh.vAnchor == 2) {
            top += Math.max(0, space - total);
        }
        float yy = top;
        for (int i = 0; i < layouts.size(); i++) {
            StaticLayout l = layouts.get(i);
            c.save();
            c.translate(sh.x + inset + indents.get(i), yy);
            l.draw(c);
            c.restore();
            yy += l.getHeight() + sh.fontSize * 0.2f;
        }
    }

    private static void slideTable(Canvas c, Doc.Shape sh, TextPaint tp) {
        Doc.Table t = sh.table;
        int cols = 0;
        for (List<String> r : t.rows) {
            cols = Math.max(cols, r.size());
        }
        if (cols == 0 || t.rows.isEmpty()) {
            return;
        }
        float cw = sh.w / cols;
        float rh = sh.h / t.rows.size();
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setColor(0xFF8A94A6);
        Paint head = new Paint();
        head.setColor(0xFF4472C4);
        float size = Math.min(sh.fontSize, rh * 0.5f);
        for (int r = 0; r < t.rows.size(); r++) {
            List<String> row = t.rows.get(r);
            for (int col = 0; col < cols; col++) {
                RectF cell = new RectF(sh.x + col * cw, sh.y + r * rh, sh.x + (col + 1) * cw, sh.y + (r + 1) * rh);
                if (t.header && r == 0) {
                    c.drawRect(cell, head);
                }
                c.drawRect(cell, line);
                String v = col < row.size() ? row.get(col) : "";
                tp.setTextSize(size);
                tp.setColor(t.header && r == 0 ? Color.WHITE : Color.BLACK);
                tp.setTypeface(t.header && r == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                StaticLayout l = slideLayout(v, tp, Math.round(cw - 8), Layout.Alignment.ALIGN_NORMAL);
                c.save();
                c.clipRect(cell);
                c.translate(cell.left + 4, cell.top + Math.max(2, (rh - l.getHeight()) / 2));
                l.draw(c);
                c.restore();
            }
        }
    }

    // ---------------------------------------------------------------------------------------

    public int pageCount() {
        return pageNumber;
    }

    public void writeTo(File out) throws IOException {
        finishPage();
        try (OutputStream os = new FileOutputStream(out)) {
            pdf.writeTo(os);
        } finally {
            pdf.close();
        }
    }

    /** Releases resources without writing (on errors / cancel). */
    public void abort() {
        try {
            finishPage();
        } catch (RuntimeException ignored) {
            // nothing to do
        }
        pdf.close();
    }
}
