package com.ahmadman.pdfconverter.core;

import java.util.ArrayList;
import java.util.List;

/** Format-neutral document model that the PDF composer renders (Word, Excel, text, HTML ...). */
public final class Doc {

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;
    public static final int ALIGN_JUSTIFY = 3;

    public final List<Block> blocks = new ArrayList<>();
    /** Non-null for presentations: one entry per slide (blocks unused). */
    public List<Slide> slides;
    public String title;

    public interface Block {
    }

    public static final class Run {
        public final String text;
        public boolean bold;
        public boolean italic;
        public boolean underline;
        public boolean strike;
        public boolean monospace;
        /** Point size, 0 = paragraph default. */
        public float size;
        /** 0xRRGGBB or -1 for default. */
        public int color = -1;

        public Run(String text) {
            this.text = text;
        }
    }

    public static final class Para implements Block {
        public final List<Run> runs = new ArrayList<>();
        /** 0 = body, 1..6 = heading level. */
        public int heading;
        public int align = ALIGN_LEFT;
        public boolean bullet;
        /** Number of a numbered list item, 0 if none. */
        public int number;
        public int indentLevel;
        public boolean pageBreakBefore;
        public boolean quote;
        /** Ready-made styled text (e.g. parsed HTML); used instead of {@link #runs} when set. */
        public CharSequence styled;

        public Para add(String text) {
            runs.add(new Run(text));
            return this;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Run r : runs) {
                sb.append(r.text);
            }
            return sb.toString();
        }

        public boolean isEmpty() {
            return text().trim().isEmpty();
        }
    }

    public static final class Image implements Block {
        public final byte[] data;
        /** Desired display size in points (0 = natural size, fitted to the page). */
        public float width;
        public float height;

        public Image(byte[] data) {
            this.data = data;
        }
    }

    public static final class Table implements Block {
        public final List<List<String>> rows = new ArrayList<>();
        public boolean header;
        public String caption;
    }

    public static final class PageBreak implements Block {
    }

    public static final class Rule implements Block {
    }

    /** A positioned slide (points). */
    public static final class Slide {
        public float width;
        public float height;
        /** 0xRRGGBB background. */
        public int background = 0xFFFFFF;
        public final List<Shape> shapes = new ArrayList<>();
    }

    public static final class Shape {
        public float x;
        public float y;
        public float w;
        public float h;
        /** 0xRRGGBB fill or -1 for none. */
        public int fill = -1;
        public int line = -1;
        public byte[] image;
        public final List<Para> paras = new ArrayList<>();
        /** Default text size of the shape in points. */
        public float fontSize = 18;
        public boolean title;
        public Table table;
        /** 0 = top, 1 = middle, 2 = bottom. */
        public int vAnchor;
    }
}
