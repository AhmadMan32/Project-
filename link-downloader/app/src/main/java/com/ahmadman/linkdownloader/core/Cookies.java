package com.ahmadman.linkdownloader.core;

import java.util.Locale;

/** Netscape cookies.txt (the format yt-dlp reads) from a browser "Cookie:" header. */
public final class Cookies {

    private Cookies() {
    }

    /**
     * @param domain       e.g. "instagram.com" (written as ".instagram.com" for subdomains)
     * @param cookieHeader "name=value; name2=value2" as returned by CookieManager.getCookie
     * @param expiresEpoch expiry in seconds since 1970
     */
    public static String netscape(String domain, String cookieHeader, long expiresEpoch) {
        StringBuilder sb = new StringBuilder("# Netscape HTTP Cookie File\n");
        if (cookieHeader == null) {
            return sb.toString();
        }
        String d = domain.startsWith(".") ? domain : "." + domain;
        for (String part : cookieHeader.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String name = p.substring(0, eq).trim();
            String value = p.substring(eq + 1).trim();
            if (name.isEmpty() || name.indexOf('\t') >= 0 || value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0) {
                continue;
            }
            sb.append(d).append("\tTRUE\t/\tTRUE\t").append(expiresEpoch).append('\t')
                    .append(name).append('\t').append(value).append('\n');
        }
        return sb.toString();
    }

    /** Instagram is logged in when the session cookie is present. */
    public static boolean hasInstagramSession(String cookieHeader) {
        if (cookieHeader == null) {
            return false;
        }
        for (String part : cookieHeader.split(";")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.startsWith("sessionid=") && p.length() > "sessionid=".length()) {
                return true;
            }
        }
        return false;
    }
}
