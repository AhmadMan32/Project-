package com.ahmadman.pdfconverter.core;

import java.util.ArrayList;
import java.util.List;

/** Text extracted from a PDF with positions (PDF points, origin top-left). */
public final class PdfText {

    private PdfText() {
    }

    public static final class Word {
        public final String text;
        public final float x;
        public final float y;
        public final float width;
        public final float height;
        public final float fontSize;
        public final boolean bold;
        public final boolean italic;
        /** Width of a space in this font (for gap detection). */
        public final float spaceWidth;

        public Word(String text, float x, float y, float width, float height, float fontSize,
                    boolean bold, boolean italic, float spaceWidth) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.fontSize = fontSize;
            this.bold = bold;
            this.italic = italic;
            this.spaceWidth = spaceWidth;
        }

        public float right() {
            return x + width;
        }
    }

    public static final class Line {
        public final List<Word> words = new ArrayList<>();

        public float x() {
            return words.isEmpty() ? 0 : words.get(0).x;
        }

        public float right() {
            float r = 0;
            for (Word w : words) {
                r = Math.max(r, w.right());
            }
            return r;
        }

        /** Baseline y of the line. */
        public float y() {
            float y = 0;
            for (Word w : words) {
                y = Math.max(y, w.y);
            }
            return y;
        }

        public float height() {
            float h = 0;
            for (Word w : words) {
                h = Math.max(h, w.height);
            }
            return h <= 0 ? fontSize() : h;
        }

        /** Character-weighted font size. */
        public float fontSize() {
            float sum = 0;
            int n = 0;
            for (Word w : words) {
                sum += w.fontSize * w.text.length();
                n += w.text.length();
            }
            return n == 0 ? 0 : sum / n;
        }

        public boolean bold() {
            int b = 0;
            int n = 0;
            for (Word w : words) {
                n += w.text.length();
                if (w.bold) {
                    b += w.text.length();
                }
            }
            return n > 0 && b * 2 > n;
        }

        public boolean italic() {
            int b = 0;
            int n = 0;
            for (Word w : words) {
                n += w.text.length();
                if (w.italic) {
                    b += w.text.length();
                }
            }
            return n > 0 && b * 2 > n;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Word w : words) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(w.text);
            }
            return sb.toString();
        }
    }

    public static final class Page {
        public final int number;
        public final float width;
        public final float height;
        public final List<Line> lines = new ArrayList<>();

        public Page(int number, float width, float height) {
            this.number = number;
            this.width = width;
            this.height = height;
        }

        public boolean isEmpty() {
            for (Line l : lines) {
                if (!l.text().trim().isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Line l : lines) {
                sb.append(l.text()).append('\n');
            }
            return sb.toString();
        }
    }

    /** A reflowed paragraph (for Word, HTML, EPUB, Markdown, RTF). */
    public static final class Paragraph {
        public final String text;
        public final float fontSize;
        public final boolean bold;
        public final boolean italic;
        /** 0 = body text, 1 = main heading, 2 = sub heading. */
        public final int heading;

        public Paragraph(String text, float fontSize, boolean bold, boolean italic, int heading) {
            this.text = text;
            this.fontSize = fontSize;
            this.bold = bold;
            this.italic = italic;
            this.heading = heading;
        }
    }
}
