package com.ahmadman.pdfconverter.core;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain text, Markdown and CSV sources turned into a {@link Doc}. */
public final class TextReaders {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d{1,4})[.)]\\s+(.*)$");
    private static final Pattern INLINE = Pattern.compile("(\\*\\*|__)(.+?)\\1|(\\*|_)(.+?)\\3|`([^`]+)`|\\[([^]]+)]\\([^)]+\\)");

    private TextReaders() {
    }

    public static Doc plainText(String text) {
        Doc doc = new Doc();
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith(BOM)) {
            normalized = normalized.substring(1);
        }
        for (String line : normalized.split("\n", -1)) {
            if (line.startsWith("\f")) {
                Doc.Para p = new Doc.Para();
                p.pageBreakBefore = true;
                doc.blocks.add(p);
                line = line.substring(1);
            }
            Doc.Para p = new Doc.Para();
            p.add(line.replace("\f", ""));
            doc.blocks.add(p);
        }
        return doc;
    }

    public static Doc markdown(String text) {
        Doc doc = new Doc();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder para = new StringBuilder();
        boolean code = false;
        StringBuilder codeBuf = new StringBuilder();
        Doc.Table table = null;
        for (String raw : lines) {
            String line = raw;
            if (line.trim().startsWith("```")) {
                flushPara(doc, para);
                if (code) {
                    Doc.Para p = new Doc.Para();
                    Doc.Run r = new Doc.Run(codeBuf.toString().replaceAll("\n$", ""));
                    r.monospace = true;
                    p.runs.add(r);
                    doc.blocks.add(p);
                    codeBuf.setLength(0);
                }
                code = !code;
                continue;
            }
            if (code) {
                codeBuf.append(line).append('\n');
                continue;
            }
            String trimmed = line.trim();
            if (trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 1) {
                flushPara(doc, para);
                String inner = trimmed.substring(1, trimmed.length() - 1);
                if (inner.matches("[\\s:|-]+")) {
                    if (table != null) {
                        table.header = true;
                    }
                    continue; // separator row
                }
                if (table == null) {
                    table = new Doc.Table();
                    doc.blocks.add(table);
                }
                java.util.List<String> row = new java.util.ArrayList<>();
                for (String c : inner.split("\\|", -1)) {
                    row.add(stripInline(c.trim()));
                }
                table.rows.add(row);
                continue;
            }
            table = null;
            Matcher h = HEADING.matcher(line);
            Matcher b = BULLET.matcher(line);
            Matcher n = NUMBERED.matcher(line);
            if (trimmed.isEmpty()) {
                flushPara(doc, para);
            } else if (h.matches()) {
                flushPara(doc, para);
                Doc.Para p = inline(h.group(2));
                p.heading = h.group(1).length();
                doc.blocks.add(p);
            } else if (trimmed.matches("(-{3,}|\\*{3,}|_{3,})")) {
                flushPara(doc, para);
                doc.blocks.add(new Doc.Rule());
            } else if (b.matches()) {
                flushPara(doc, para);
                Doc.Para p = inline(b.group(2));
                p.bullet = true;
                p.indentLevel = b.group(1).length() / 2;
                doc.blocks.add(p);
            } else if (n.matches()) {
                flushPara(doc, para);
                Doc.Para p = inline(n.group(3));
                p.number = Integer.parseInt(n.group(2));
                p.indentLevel = n.group(1).length() / 2;
                doc.blocks.add(p);
            } else if (trimmed.startsWith(">")) {
                flushPara(doc, para);
                Doc.Para p = inline(trimmed.replaceFirst("^>+\\s?", ""));
                p.quote = true;
                doc.blocks.add(p);
            } else {
                if (para.length() > 0) {
                    para.append(line.endsWith("  ") ? "\n" : " ");
                }
                para.append(trimmed);
            }
        }
        if (code && codeBuf.length() > 0) {
            Doc.Para p = new Doc.Para();
            Doc.Run r = new Doc.Run(codeBuf.toString());
            r.monospace = true;
            p.runs.add(r);
            doc.blocks.add(p);
        }
        flushPara(doc, para);
        return doc;
    }

    private static void flushPara(Doc doc, StringBuilder para) {
        if (para.length() > 0) {
            doc.blocks.add(inline(para.toString()));
            para.setLength(0);
        }
    }

    /** **bold**, *italic*, `code` and [links](url) inside a line. */
    static Doc.Para inline(String text) {
        Doc.Para p = new Doc.Para();
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) {
                p.runs.add(new Doc.Run(text.substring(last, m.start())));
            }
            Doc.Run r;
            if (m.group(2) != null) {
                r = new Doc.Run(m.group(2));
                r.bold = true;
            } else if (m.group(4) != null) {
                r = new Doc.Run(m.group(4));
                r.italic = true;
            } else if (m.group(5) != null) {
                r = new Doc.Run(m.group(5));
                r.monospace = true;
            } else {
                r = new Doc.Run(m.group(6));
                r.underline = true;
                r.color = 0x1A56DB;
            }
            p.runs.add(r);
            last = m.end();
        }
        if (last < text.length()) {
            p.runs.add(new Doc.Run(text.substring(last)));
        }
        return p;
    }

    private static String stripInline(String s) {
        return inline(s).text();
    }

    public static Doc csv(String text) {
        Doc doc = new Doc();
        Doc.Table t = new Doc.Table();
        t.header = true;
        List<List<String>> rows = Csv.parse(text, Csv.detectDelimiter(text));
        t.rows.addAll(rows.size() > XlsxReader.MAX_ROWS ? rows.subList(0, XlsxReader.MAX_ROWS) : rows);
        doc.blocks.add(t);
        return doc;
    }
}
