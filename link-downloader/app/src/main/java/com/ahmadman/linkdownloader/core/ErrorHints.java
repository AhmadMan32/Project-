package com.ahmadman.linkdownloader.core;

import java.util.Locale;

/** Turns yt-dlp error output into a reason the app can explain in plain words. */
public final class ErrorHints {

    public enum Reason {
        BOT_CHECK, LOGIN_REQUIRED, PRIVATE, UNAVAILABLE, GEO_BLOCKED, AGE_RESTRICTED, UNSUPPORTED,
        RATE_LIMITED, NETWORK, NO_FORMATS, LIVE, STORAGE, UNKNOWN
    }

    private ErrorHints() {
    }

    public static Reason classify(String output) {
        if (output == null) {
            return Reason.UNKNOWN;
        }
        String s = output.toLowerCase(Locale.ROOT);
        if (s.contains("confirm you") && s.contains("not a bot") || s.contains("sign in to confirm")) {
            return Reason.BOT_CHECK;
        }
        if (s.contains("private video") || s.contains("this video is private") || s.contains("account is private")
                || s.contains("this account is private")) {
            return Reason.PRIVATE;
        }
        if (s.contains("confirm your age") || s.contains("age-restricted") || s.contains("inappropriate for some users")) {
            return Reason.AGE_RESTRICTED;
        }
        if (s.contains("login required") || s.contains("requires login") || s.contains("log in")
                || s.contains("--cookies") || s.contains("cookies-from-browser") || s.contains("not logged in")
                || s.contains("rate-limit reached") || s.contains("main webpage is locked behind the login page")) {
            return Reason.LOGIN_REQUIRED;
        }
        if (s.contains("http error 429") || s.contains("too many requests")) {
            return Reason.RATE_LIMITED;
        }
        if (s.contains("in your country") || s.contains("geo restricted") || s.contains("geo-restricted")
                || s.contains("from your location")) {
            return Reason.GEO_BLOCKED;
        }
        if (s.contains("unsupported url")) {
            return Reason.UNSUPPORTED;
        }
        if (s.contains("is live") || s.contains("live event will begin") || s.contains("premieres in")) {
            return Reason.LIVE;
        }
        if (s.contains("video unavailable") || s.contains("has been removed") || s.contains("http error 404")
                || s.contains("does not exist") || s.contains("no longer available") || s.contains("post isn't available")) {
            return Reason.UNAVAILABLE;
        }
        if (s.contains("requested format is not available") || s.contains("no video formats found")
                || s.contains("there is no video in this post")) {
            return Reason.NO_FORMATS;
        }
        if (s.contains("no space left") || s.contains("disk full") || s.contains("errno 28")) {
            return Reason.STORAGE;
        }
        if (s.contains("unable to download webpage") || s.contains("getaddrinfo") || s.contains("name or service not known")
                || s.contains("network is unreachable") || s.contains("timed out") || s.contains("connection reset")
                || s.contains("failed to resolve") || s.contains("no address associated")) {
            return Reason.NETWORK;
        }
        return Reason.UNKNOWN;
    }

    /** The last "ERROR: ..." line without the "[extractor] id:" prefix, for showing details. */
    public static String lastError(String output) {
        if (output == null) {
            return "";
        }
        String found = null;
        for (String line : output.split("\n")) {
            String l = line.trim();
            if (l.startsWith("ERROR:")) {
                found = l.substring(6).trim();
            }
        }
        if (found == null) {
            String[] lines = output.trim().split("\n");
            found = lines.length == 0 ? "" : lines[lines.length - 1].trim();
        }
        found = found.replaceFirst("^\\[[^]]+]\\s*[^:\\s]+:\\s*", "");
        int see = found.indexOf(" See  http");
        if (see < 0) {
            see = found.indexOf(" See http");
        }
        if (see > 0) {
            found = found.substring(0, see).trim();
        }
        return found.length() > 300 ? found.substring(0, 300) + "…" : found;
    }
}
