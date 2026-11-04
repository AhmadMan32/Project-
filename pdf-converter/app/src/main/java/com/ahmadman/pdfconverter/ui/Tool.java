package com.ahmadman.pdfconverter.ui;

import com.ahmadman.pdfconverter.convert.Job;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One conversion or PDF tool shown on the home screen. */
public final class Tool {

    public static final int FROM_PDF = 0;
    public static final int TO_PDF = 1;
    public static final int PDF_TOOLS = 2;

    public interface Runner {
        List<File> run(Job job) throws Exception;
    }

    public final String id;
    public final int category;
    public final int title;
    public final int description;
    public final String badge;
    public final int color;
    public final String[] mimeTypes;
    public final boolean multiple;
    public final Runner runner;
    public final List<Option> options = new ArrayList<>();
    /** Extra explanation shown on the tool screen (0 = none). */
    public int note;

    Tool(String id, int category, int title, int description, String badge, int color, String[] mimeTypes,
         boolean multiple, Runner runner, Option... options) {
        this.id = id;
        this.category = category;
        this.title = title;
        this.description = description;
        this.badge = badge;
        this.color = color;
        this.mimeTypes = mimeTypes;
        this.multiple = multiple;
        this.runner = runner;
        this.options.addAll(Arrays.asList(options));
    }

    Tool note(int res) {
        note = res;
        return this;
    }

    /** Does an incoming file of this MIME type fit this tool? */
    public boolean accepts(String mime) {
        if (mime == null) {
            return false;
        }
        for (String m : mimeTypes) {
            if (m.equals("*/*") || m.equals(mime)
                    || (m.endsWith("/*") && mime.startsWith(m.substring(0, m.length() - 1)))) {
                return true;
            }
        }
        return false;
    }

    /** A setting of a tool. */
    public static final class Option {
        public static final int CHOICE = 0;
        public static final int TEXT = 1;
        public static final int PASSWORD = 2;
        public static final int NUMBER = 3;
        public static final int TOGGLE = 4;
        public static final int PAGES = 5;

        public final String key;
        public final int kind;
        public final int label;
        public final String[] values;
        public final int[] labels;
        public final String def;
        public int hint;
        /** Only shown when another option has a given value: {key, value}. */
        public String[] visibleWhen;
        /** Not remembered between uses (passwords). */
        public boolean secret;

        private Option(String key, int kind, int label, String[] values, int[] labels, String def) {
            this.key = key;
            this.kind = kind;
            this.label = label;
            this.values = values;
            this.labels = labels;
            this.def = def;
        }

        static Option choice(String key, int label, String def, Object... pairs) {
            String[] values = new String[pairs.length / 2];
            int[] labels = new int[pairs.length / 2];
            for (int i = 0; i < pairs.length; i += 2) {
                values[i / 2] = (String) pairs[i];
                labels[i / 2] = (Integer) pairs[i + 1];
            }
            return new Option(key, CHOICE, label, values, labels, def);
        }

        static Option text(String key, int label, int hint, String def) {
            Option o = new Option(key, TEXT, label, null, null, def);
            o.hint = hint;
            return o;
        }

        static Option password(String key, int label) {
            Option o = new Option(key, PASSWORD, label, null, null, "");
            o.secret = true;
            return o;
        }

        static Option number(String key, int label, String def) {
            return new Option(key, NUMBER, label, null, null, def);
        }

        static Option toggle(String key, int label, boolean def) {
            return new Option(key, TOGGLE, label, null, null, String.valueOf(def));
        }

        static Option pages(int label, int hint) {
            Option o = new Option("pages", PAGES, label, null, null, "");
            o.hint = hint;
            return o;
        }

        Option when(String key, String value) {
            visibleWhen = new String[]{key, value};
            return this;
        }
    }
}
