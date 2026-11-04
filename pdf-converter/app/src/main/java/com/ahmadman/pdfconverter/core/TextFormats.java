package com.ahmadman.pdfconverter.core;

import java.util.List;

/** Plain text based outputs: TXT, HTML, Markdown and RTF. */
public final class TextFormats {

    private TextFormats() {
    }

    /** Text with the original line breaks; pages separated by a blank line. */
    public static String txt(List<PdfText.Page> pages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append("\n\n");
            }
            for (PdfText.Line l : pages.get(i).lines) {
                sb.append(l.text()).append('\n');
            }
        }
        return sb.toString();
    }

    public static String html(String title, List<List<PdfText.Paragraph>> pages) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html lang=\"tr\"><head><meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n<title>")
                .append(Xml.esc(title)).append("</title>\n<style>\n"
                        + "body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;line-height:1.55;"
                        + "max-width:52rem;margin:0 auto;padding:1.5rem;color:#1b1f24;background:#fff}\n"
                        + "section{border-bottom:1px solid #e3e6ea;padding:1rem 0}\n"
                        + ".page{color:#6b7280;font-size:.8rem;text-transform:uppercase;letter-spacing:.05em}\n"
                        + "@media (prefers-color-scheme:dark){body{background:#111418;color:#e6e8eb}section{border-color:#2a2f36}}\n"
                        + "</style></head><body>\n");
        for (int i = 0; i < pages.size(); i++) {
            sb.append("<section id=\"sayfa-").append(i + 1).append("\">\n<div class=\"page\">Sayfa ")
                    .append(i + 1).append("</div>\n");
            sb.append(EpubWriter.paragraphsToXhtml(pages.get(i)));
            sb.append("</section>\n");
        }
        return sb.append("</body></html>\n").toString();
    }

    public static String markdown(List<List<PdfText.Paragraph>> pages) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append("\n---\n\n");
            }
            for (PdfText.Paragraph p : pages.get(i)) {
                String t = mdEscape(p.text);
                if (p.heading == 1) {
                    sb.append("# ").append(t);
                } else if (p.heading == 2) {
                    sb.append("## ").append(t);
                } else if (p.bold) {
                    sb.append("**").append(t).append("**");
                } else if (p.italic) {
                    sb.append('*').append(t).append('*');
                } else {
                    sb.append(t);
                }
                sb.append("\n\n");
            }
        }
        return sb.toString();
    }

    private static String mdEscape(String s) {
        String t = s.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("`", "\\`");
        if (t.startsWith("#") || t.startsWith(">")) {
            t = "\\" + t;
        }
        return t;
    }

    /** Rich Text Format; non-ASCII characters as \\uN so Turkish letters survive everywhere. */
    public static String rtf(List<List<PdfText.Paragraph>> pages, float bodySize) {
        StringBuilder sb = new StringBuilder("{\\rtf1\\ansi\\ansicpg1254\\deff0{\\fonttbl{\\f0\\fswiss Arial;}}\n");
        int body = Math.max(16, Math.round((bodySize > 0 ? bodySize : 11) * 2));
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) {
                sb.append("\\page\n");
            }
            for (PdfText.Paragraph p : pages.get(i)) {
                int size = p.heading == 1 ? Math.round(body * 1.6f) : p.heading == 2 ? Math.round(body * 1.3f) : body;
                sb.append("\\pard\\sa160\\f0\\fs").append(size);
                if (p.bold || p.heading > 0) {
                    sb.append("\\b");
                }
                if (p.italic) {
                    sb.append("\\i");
                }
                sb.append(' ').append(rtfEscape(p.text));
                if (p.bold || p.heading > 0) {
                    sb.append("\\b0");
                }
                if (p.italic) {
                    sb.append("\\i0");
                }
                sb.append("\\par\n");
            }
        }
        return sb.append('}').toString();
    }

    static String rtfEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '{' || c == '}') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\line ");
            } else if (c == '\t') {
                sb.append("\\tab ");
            } else if (c < 0x80) {
                sb.append(c);
            } else {
                sb.append("\\u").append((int) (short) c).append('?');
            }
        }
        return sb.toString();
    }
}
