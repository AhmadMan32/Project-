package com.ahmadman.pdfconverter.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Turns positioned words into paragraphs (reflow) or table rows (columns). */
public final class TextAnalyzer {

    private static final Pattern LIST_START = Pattern.compile(
            "^(\\s*([•▪◦●■\\-*–]|\\d{1,3}[.)]|[a-zA-Z][.)])\\s+).*");

    private TextAnalyzer() {
    }

    /** Most common font size of the body text (character weighted median). */
    public static float bodyFontSize(List<PdfText.Page> pages) {
        List<float[]> sizes = new ArrayList<>(); // {size, weight}
        long total = 0;
        for (PdfText.Page p : pages) {
            for (PdfText.Line l : p.lines) {
                for (PdfText.Word w : l.words) {
                    if (w.fontSize > 0) {
                        sizes.add(new float[]{w.fontSize, w.text.length()});
                        total += w.text.length();
                    }
                }
            }
        }
        if (sizes.isEmpty()) {
            return 11f;
        }
        Collections.sort(sizes, (a, b) -> Float.compare(a[0], b[0]));
        long half = total / 2;
        long acc = 0;
        for (float[] s : sizes) {
            acc += (long) s[1];
            if (acc >= half) {
                return s[0];
            }
        }
        return sizes.get(sizes.size() - 1)[0];
    }

    public static int headingLevel(float size, float body) {
        if (body <= 0 || size <= 0) {
            return 0;
        }
        if (size >= body * 1.6f) {
            return 1;
        }
        if (size >= body * 1.25f) {
            return 2;
        }
        return 0;
    }

    /** Joins lines into paragraphs using spacing, indentation, style and line length. */
    public static List<PdfText.Paragraph> paragraphs(PdfText.Page page, float body) {
        List<PdfText.Paragraph> out = new ArrayList<>();
        List<PdfText.Line> lines = new ArrayList<>();
        for (PdfText.Line l : page.lines) {
            if (!l.text().trim().isEmpty()) {
                lines.add(l);
            }
        }
        if (lines.isEmpty()) {
            return out;
        }
        float maxRight = 0;
        float minX = Float.MAX_VALUE;
        for (PdfText.Line l : lines) {
            maxRight = Math.max(maxRight, l.right());
            minX = Math.min(minX, l.x());
        }
        float textWidth = Math.max(1, maxRight - minX);

        StringBuilder text = new StringBuilder();
        PdfText.Line first = null;
        PdfText.Line prev = null;
        for (PdfText.Line l : lines) {
            boolean join = false;
            if (prev != null) {
                float gap = l.y() - prev.y();
                float lineH = Math.max(prev.height(), prev.fontSize());
                boolean sameStyle = Math.abs(l.fontSize() - prev.fontSize()) < 0.6f
                        && l.bold() == prev.bold();
                boolean closeEnough = gap > 0 && gap <= lineH * 1.75f;
                boolean prevFull = prev.right() - minX >= textWidth * 0.72f;
                boolean listItem = LIST_START.matcher(l.text()).matches();
                boolean heading = headingLevel(prev.fontSize(), body) > 0;
                join = sameStyle && closeEnough && prevFull && !listItem && !heading;
            }
            if (!join && first != null) {
                out.add(make(text.toString(), first, body));
                text.setLength(0);
            }
            if (!join) {
                first = l;
                text.append(l.text().trim());
            } else {
                String t = l.text().trim();
                int len = text.length();
                if (len > 1 && text.charAt(len - 1) == '-' && Character.isLetter(text.charAt(len - 2))
                        && !t.isEmpty() && Character.isLowerCase(t.charAt(0))) {
                    text.setLength(len - 1); // hyphenated word split over two lines
                } else {
                    text.append(' ');
                }
                text.append(t);
            }
            prev = l;
        }
        if (first != null) {
            out.add(make(text.toString(), first, body));
        }
        return out;
    }

    private static PdfText.Paragraph make(String text, PdfText.Line first, float body) {
        float size = first.fontSize();
        return new PdfText.Paragraph(text, size, first.bold(), first.italic(), headingLevel(size, body));
    }

    /** Splits a line into cells where the horizontal gap between words is large. */
    public static List<Cell> cells(PdfText.Line line) {
        List<Cell> out = new ArrayList<>();
        Cell cur = null;
        PdfText.Word prev = null;
        for (PdfText.Word w : line.words) {
            if (prev != null) {
                float space = prev.spaceWidth > 0 ? prev.spaceWidth : prev.fontSize * 0.28f;
                float gap = w.x - prev.right();
                if (gap > Math.max(space * 2.2f, prev.fontSize * 0.9f)) {
                    cur = null;
                }
            }
            if (cur == null) {
                cur = new Cell(w.x);
                out.add(cur);
            } else {
                cur.text.append(' ');
            }
            cur.text.append(w.text);
            cur.right = w.right();
            prev = w;
        }
        return out;
    }

    public static final class Cell {
        public final float x;
        public float right;
        public final StringBuilder text = new StringBuilder();

        Cell(float x) {
            this.x = x;
            this.right = x;
        }
    }

    /**
     * Rows of a page with cells aligned to shared columns: cell start positions of all lines
     * are clustered into column anchors, then every cell goes to its column.
     */
    public static List<List<String>> table(PdfText.Page page) {
        List<List<Cell>> rowCells = new ArrayList<>();
        List<Float> starts = new ArrayList<>();
        for (PdfText.Line l : page.lines) {
            List<Cell> cells = cells(l);
            if (cells.isEmpty()) {
                continue;
            }
            rowCells.add(cells);
            for (Cell c : cells) {
                starts.add(c.x);
            }
        }
        List<Float> anchors = clusterColumns(starts);
        List<List<String>> rows = new ArrayList<>();
        for (List<Cell> cells : rowCells) {
            String[] row = new String[Math.max(1, anchors.size())];
            int lastCol = -1;
            for (Cell c : cells) {
                int col = nearest(anchors, c.x);
                if (col <= lastCol) {
                    col = Math.min(row.length - 1, lastCol + 1);
                }
                if (row[col] == null) {
                    row[col] = c.text.toString();
                } else {
                    row[col] = row[col] + " " + c.text;
                }
                lastCol = col;
            }
            int len = row.length;
            while (len > 1 && row[len - 1] == null) {
                len--;
            }
            List<String> r = new ArrayList<>();
            for (int i = 0; i < len; i++) {
                r.add(row[i] == null ? "" : row[i]);
            }
            rows.add(r);
        }
        return rows;
    }

    static List<Float> clusterColumns(List<Float> starts) {
        List<Float> sorted = new ArrayList<>(starts);
        Collections.sort(sorted);
        List<Float> anchors = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        float tolerance = 8f;
        for (float x : sorted) {
            int last = anchors.size() - 1;
            if (last >= 0 && x - anchors.get(last) <= tolerance) {
                int n = counts.get(last);
                anchors.set(last, (anchors.get(last) * n + x) / (n + 1));
                counts.set(last, n + 1);
            } else {
                anchors.add(x);
                counts.add(1);
            }
        }
        return anchors;
    }

    private static int nearest(List<Float> anchors, float x) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < anchors.size(); i++) {
            float d = Math.abs(anchors.get(i) - x);
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /**
     * Typed value for spreadsheets: a Double for unambiguous numbers, else the trimmed string.
     * Leading zeros (IDs, phone numbers) and "1.234"-style values (thousands or decimals?) stay text.
     */
    public static Object cellValue(String s) {
        String t = s == null ? "" : s.trim();
        try {
            if (t.matches("-?(0|[1-9]\\d{0,14})")) {
                return Double.parseDouble(t);
            }
            if (t.matches("-?\\d{1,12}[.,](\\d{1,2}|\\d{4,6})")) {
                return Double.parseDouble(t.replace(',', '.'));
            }
            if (t.matches("-?\\d{1,3}((\\.\\d{3}){2,}(,\\d{1,6})?|(\\.\\d{3})+,\\d{1,6})")) { // 1.234.567,89
                return Double.parseDouble(t.replace(".", "").replace(',', '.'));
            }
            if (t.matches("-?\\d{1,3}((,\\d{3}){2,}(\\.\\d{1,6})?|(,\\d{3})+\\.\\d{1,6})")) { // 1,234,567.89
                return Double.parseDouble(t.replace(",", ""));
            }
        } catch (NumberFormatException ignored) {
            // fall through
        }
        return t;
    }
}
