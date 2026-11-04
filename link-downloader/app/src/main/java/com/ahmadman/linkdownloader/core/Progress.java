package com.ahmadman.linkdownloader.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses yt-dlp's output lines into something to show: percent, size, speed, step. */
public final class Progress {

    public enum Step { STARTING, DOWNLOADING, MERGING, CONVERTING, FINISHING }

    private static final Pattern DOWNLOAD = Pattern.compile(
            "\\[download]\\s+([0-9.]+)%(?:\\s+of\\s+~?\\s*([0-9.]+\\s*[KMGT]?i?B))?(?:\\s+at\\s+([0-9.]+\\s*[KMGT]?i?B/s))?"
                    + "(?:\\s+ETA\\s+([0-9:]+))?");
    private static final Pattern DESTINATION = Pattern.compile("\\[download] Destination: .*\\.f\\d+\\.");

    public final Step step;
    public final float percent;
    public final String total;
    public final String speed;
    public final String eta;

    private Progress(Step step, float percent, String total, String speed, String eta) {
        this.step = step;
        this.percent = percent;
        this.total = total;
        this.speed = speed;
        this.eta = eta;
    }

    /** Null when the line carries no progress information. */
    public static Progress parse(String line) {
        if (line == null) {
            return null;
        }
        String l = line.trim();
        Matcher m = DOWNLOAD.matcher(l);
        if (m.find()) {
            float pct;
            try {
                pct = Float.parseFloat(m.group(1));
            } catch (NumberFormatException e) {
                return null;
            }
            return new Progress(Step.DOWNLOADING, Math.max(0, Math.min(100, pct)), m.group(2), m.group(3), m.group(4));
        }
        if (l.startsWith("[Merger]") || l.startsWith("[VideoRemuxer]")) {
            return new Progress(Step.MERGING, 100, null, null, null);
        }
        if (l.startsWith("[ExtractAudio]") || l.startsWith("[VideoConvertor]")) {
            return new Progress(Step.CONVERTING, 100, null, null, null);
        }
        if (l.startsWith("[EmbedThumbnail]") || l.startsWith("[Metadata]") || l.startsWith("[ThumbnailsConvertor]")
                || l.startsWith("[MoveFiles]")) {
            return new Progress(Step.FINISHING, 100, null, null, null);
        }
        return null;
    }

    /** True if yt-dlp is downloading the second part (audio after video) of a merged file. */
    public static boolean isSecondPart(String line, boolean sawFirst) {
        return sawFirst && line != null && DESTINATION.matcher(line).find();
    }

    /** "1:05:09" / "4:07" from seconds. */
    public static String duration(int seconds) {
        if (seconds <= 0) {
            return "";
        }
        int h = seconds / 3600;
        int m = seconds % 3600 / 60;
        int s = seconds % 60;
        return h > 0 ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) : String.format(Locale.ROOT, "%d:%02d", m, s);
    }
}
