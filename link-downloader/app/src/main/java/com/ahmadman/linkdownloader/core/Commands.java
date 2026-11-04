package com.ahmadman.linkdownloader.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * yt-dlp options for each download type. Kept free of Android code so the exact same
 * arguments can be tested with a desktop yt-dlp.
 */
public final class Commands {

    public static final String VIDEO = "video";
    public static final String AUDIO = "audio";

    public static final String MP3 = "mp3";
    public static final String M4A = "m4a";

    /** Selectable video heights; 0 means "best available". */
    public static final int[] HEIGHTS = {2160, 1440, 1080, 720, 480, 360};
    public static final int DEFAULT_HEIGHT = 1080;

    /** Marker printed by yt-dlp with the final file path (after merging/converting). */
    public static final String PATH_MARKER = "LINKDL_FILE:";

    private Commands() {
    }

    /** Options shared by every download. */
    static List<String> common(String outputTemplate, String cookiesFile) {
        List<String> a = new ArrayList<>(Arrays.asList(
                "--no-playlist",          // a video inside a playlist/mix link: just that video
                "--no-mtime",             // file date = download date, so it shows up first in galleries
                "--windows-filenames",    // no ?:*|"<> in names (not allowed on Android storage)
                "--trim-filenames", "180",
                "--retries", "5",
                "--fragment-retries", "10",
                "--concurrent-fragments", "4",
                "--newline",
                "--progress",
                "--print", "after_move:" + PATH_MARKER + "%(filepath)s",
                "--no-quiet",             // --print would hide the [Merger]/[ExtractAudio] step lines
                "-o", outputTemplate));
        if (cookiesFile != null) {
            a.add("--cookies");
            a.add(cookiesFile);
        }
        return a;
    }

    /**
     * MP4 at the chosen height (or the closest lower one), video and audio merged.
     *
     * @param preferH264 prefer H.264/AAC so the file plays everywhere (WhatsApp, old phones);
     *                   resolution always wins over codec
     */
    public static List<String> video(int maxHeight, boolean preferH264, String outputTemplate, String cookiesFile) {
        List<String> a = common(outputTemplate, cookiesFile);
        String res = maxHeight > 0 ? "res:" + maxHeight : "res";
        a.add("-S");
        a.add(preferH264 ? res + ",fps,vcodec:h264,acodec:aac" : res + ",fps");
        a.add("-f");
        a.add("bv*+ba/b");
        a.add("--merge-output-format");
        a.add("mp4");
        a.add("--remux-video");
        a.add("mp4");
        a.add("--embed-metadata");
        return a;
    }

    /**
     * Audio only. MP3 is encoded at 320 kbps (the highest MP3 bitrate) with cover art and
     * title/artist tags; M4A keeps the original AAC stream when possible (no re-encoding).
     */
    public static List<String> audio(String format, String outputTemplate, String cookiesFile) {
        List<String> a = common(outputTemplate, cookiesFile);
        a.add("-f");
        if (M4A.equals(format)) {
            a.add("ba[ext=m4a]/ba/b");
            a.add("-x");
            a.add("--audio-format");
            a.add("m4a");
        } else {
            a.add("ba/b");
            a.add("-x");
            a.add("--audio-format");
            a.add("mp3");
            a.add("--audio-quality");
            a.add("320K");
        }
        a.add("--embed-thumbnail");
        a.add("--embed-metadata");
        a.add("--convert-thumbnails");
        a.add("jpg"); // WEBP covers -> JPG, which every music player shows
        return a;
    }

    /** Options for reading title, duration and formats without downloading. */
    public static List<String> info(String cookiesFile) {
        List<String> a = new ArrayList<>(Arrays.asList("--no-playlist", "--skip-download"));
        if (cookiesFile != null) {
            a.add("--cookies");
            a.add(cookiesFile);
        }
        return a;
    }

    /** Output template inside a folder: "Title [id].ext". */
    public static String template(String folder) {
        return folder + "/%(title).150B [%(id)s].%(ext)s";
    }

    /** The file path printed by {@link #PATH_MARKER}, or null. */
    public static String findPath(String stdout) {
        if (stdout == null) {
            return null;
        }
        String found = null;
        for (String line : stdout.split("\n")) {
            String l = line.trim();
            if (l.startsWith(PATH_MARKER)) {
                found = l.substring(PATH_MARKER.length()).trim();
            }
        }
        return found == null || found.isEmpty() || found.equals("NA") ? null : found;
    }
}
