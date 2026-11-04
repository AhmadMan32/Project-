package com.ahmadman.pdfconverter.core;

import java.util.ArrayList;
import java.util.List;

/** RFC 4180 style CSV reading/writing with delimiter detection. */
public final class Csv {

    private static final String BOM = String.valueOf((char) 0xFEFF);

    private Csv() {
    }

    /** Picks the most likely delimiter (comma, semicolon, tab or pipe) from the first lines. */
    public static char detectDelimiter(String text) {
        char[] candidates = {',', ';', '\t', '|'};
        int[] score = new int[candidates.length];
        int lines = 0;
        int i = 0;
        while (i < text.length() && lines < 20) {
            int end = text.indexOf('\n', i);
            if (end < 0) {
                end = text.length();
            }
            String line = text.substring(i, end);
            for (int c = 0; c < candidates.length; c++) {
                int count = 0;
                boolean quoted = false;
                for (int k = 0; k < line.length(); k++) {
                    char ch = line.charAt(k);
                    if (ch == '"') {
                        quoted = !quoted;
                    } else if (!quoted && ch == candidates[c]) {
                        count++;
                    }
                }
                score[c] += count;
            }
            lines++;
            i = end + 1;
        }
        int best = 0;
        for (int c = 1; c < candidates.length; c++) {
            if (score[c] > score[best]) {
                best = c;
            }
        }
        return candidates[best];
    }

    public static List<List<String>> parse(String text, char delimiter) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        int start = text.startsWith(BOM) ? 1 : 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    public static String write(List<List<String>> rows, char delimiter) {
        StringBuilder sb = new StringBuilder(BOM); // BOM: Excel then reads UTF-8 (Turkish letters) correctly
        for (List<String> row : rows) {
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) {
                    sb.append(delimiter);
                }
                String v = row.get(i) == null ? "" : row.get(i);
                boolean quote = v.indexOf(delimiter) >= 0 || v.indexOf('"') >= 0
                        || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
                if (quote) {
                    sb.append('"').append(v.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(v);
                }
            }
            sb.append("\r\n");
        }
        return sb.toString();
    }
}
