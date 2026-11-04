package com.ahmadman.pdfconverter.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses page selections such as {@code "1-3, 5, 8-"}. Pages are 1-based.
 * <ul>
 *   <li>empty: every page</li>
 *   <li>{@code a-b}: range (descending ranges like {@code 5-1} give reversed order)</li>
 *   <li>{@code a-} / {@code -b}: open ranges</li>
 *   <li>{@code son} / {@code last} / {@code n}: the last page</li>
 * </ul>
 * Order is kept, so {@code "3,1,2"} reorders pages.
 */
public final class PageSpec {

    private PageSpec() {
    }

    /** Thrown for an invalid selection; the message is shown to the user. */
    public static final class SpecException extends IllegalArgumentException {
        public final String token;

        SpecException(String message, String token) {
            super(message);
            this.token = token;
        }
    }

    public static List<Integer> parse(String spec, int pageCount) {
        List<Integer> out = new ArrayList<>();
        for (List<Integer> g : parseGroups(spec, pageCount)) {
            out.addAll(g);
        }
        return out;
    }

    /** Distinct pages, in document order. */
    public static Set<Integer> parseSet(String spec, int pageCount) {
        List<Integer> pages = parse(spec, pageCount);
        java.util.TreeSet<Integer> sorted = new java.util.TreeSet<>(pages);
        return new LinkedHashSet<>(sorted);
    }

    /** One list per comma separated token, e.g. for splitting into several files. */
    public static List<List<Integer>> parseGroups(String spec, int pageCount) {
        if (pageCount <= 0) {
            throw new SpecException("empty document", "");
        }
        List<List<Integer>> groups = new ArrayList<>();
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) {
            List<Integer> all = new ArrayList<>();
            for (int i = 1; i <= pageCount; i++) {
                all.add(i);
            }
            groups.add(all);
            return groups;
        }
        for (String raw : s.split("[,;]")) {
            String token = raw.trim().replace(" ", "").replace('–', '-').replace('—', '-');
            if (token.isEmpty()) {
                continue;
            }
            int dash = token.indexOf('-', 1);
            if (token.startsWith("-")) {
                dash = 0;
            }
            List<Integer> g = new ArrayList<>();
            if (dash < 0) {
                g.add(number(token, pageCount, raw));
            } else {
                String a = token.substring(0, dash);
                String b = token.substring(dash + 1);
                int from = a.isEmpty() ? 1 : number(a, pageCount, raw);
                int to = b.isEmpty() ? pageCount : number(b, pageCount, raw);
                if (from <= to) {
                    for (int i = from; i <= to; i++) {
                        g.add(i);
                    }
                } else {
                    for (int i = from; i >= to; i--) {
                        g.add(i);
                    }
                }
            }
            groups.add(g);
        }
        if (groups.isEmpty()) {
            throw new SpecException("no pages", s);
        }
        return groups;
    }

    private static int number(String t, int pageCount, String raw) {
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("son") || lower.equals("last") || lower.equals("n")) {
            return pageCount;
        }
        int n;
        try {
            n = Integer.parseInt(t);
        } catch (NumberFormatException e) {
            throw new SpecException("not a number", raw.trim());
        }
        if (n < 1 || n > pageCount) {
            throw new SpecException("out of range", raw.trim());
        }
        return n;
    }
}
