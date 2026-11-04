package com.ahmadman.linkdownloader.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the link in pasted / shared text ("Check this out https://vm.tiktok.com/xyz/ ..."),
 * cleans tracking parameters and tells which platform it belongs to.
 */
public final class LinkDetector {

    private static final Pattern URL = Pattern.compile("(?i)\\bhttps?://[^\\s<>\"'«»“”‘’]+");
    /** Links shared without a scheme, e.g. "youtu.be/abc" or "www.instagram.com/reel/x". */
    private static final Pattern BARE = Pattern.compile(
            "(?i)\\b((?:www\\.|m\\.|vm\\.|vt\\.)?(?:youtube\\.com|youtu\\.be|instagram\\.com|tiktok\\.com|"
                    + "twitter\\.com|x\\.com|facebook\\.com|fb\\.watch|vimeo\\.com|reddit\\.com|soundcloud\\.com|"
                    + "pin\\.it|pinterest\\.com|twitch\\.tv|dailymotion\\.com|dai\\.ly)/[^\\s<>\"']*)");

    private LinkDetector() {
    }

    /** First http(s) link in the text (trailing punctuation removed), or null. */
    public static String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL.matcher(text);
        String found = null;
        if (m.find()) {
            found = m.group();
        } else {
            Matcher b = BARE.matcher(text);
            if (b.find()) {
                found = "https://" + b.group(1);
            }
        }
        if (found == null) {
            return null;
        }
        while (!found.isEmpty() && ".,;:!?)]}'\"…".indexOf(found.charAt(found.length() - 1)) >= 0) {
            // keep a closing parenthesis that belongs to the URL, e.g. wikipedia (x)
            if (found.endsWith(")") && found.indexOf('(') >= 0) {
                break;
            }
            found = found.substring(0, found.length() - 1);
        }
        return found.isEmpty() ? null : found;
    }

    static String host(String url) {
        try {
            String h = new URI(url).getHost();
            return h == null ? "" : h.toLowerCase(Locale.ROOT);
        } catch (URISyntaxException e) {
            Matcher m = Pattern.compile("(?i)^https?://([^/?#:]+)").matcher(url);
            return m.find() ? m.group(1).toLowerCase(Locale.ROOT) : "";
        }
    }

    private static boolean is(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    public static Platform detect(String url) {
        if (url == null) {
            return Platform.OTHER;
        }
        String h = host(url);
        if (is(h, "youtube.com") || is(h, "youtu.be") || is(h, "youtube-nocookie.com")) {
            return Platform.YOUTUBE;
        }
        if (is(h, "instagram.com") || is(h, "instagr.am")) {
            return Platform.INSTAGRAM;
        }
        if (is(h, "tiktok.com")) {
            return Platform.TIKTOK;
        }
        if (is(h, "twitter.com") || is(h, "x.com") || is(h, "t.co")) {
            return Platform.TWITTER;
        }
        if (is(h, "facebook.com") || is(h, "fb.watch") || is(h, "fb.com")) {
            return Platform.FACEBOOK;
        }
        if (is(h, "vimeo.com")) {
            return Platform.VIMEO;
        }
        if (is(h, "reddit.com") || is(h, "redd.it")) {
            return Platform.REDDIT;
        }
        if (is(h, "soundcloud.com")) {
            return Platform.SOUNDCLOUD;
        }
        if (is(h, "pinterest.com") || is(h, "pin.it") || h.matches("(.+\\.)?pinterest\\.[a-z.]+")) {
            return Platform.PINTEREST;
        }
        if (is(h, "twitch.tv")) {
            return Platform.TWITCH;
        }
        if (is(h, "dailymotion.com") || is(h, "dai.ly")) {
            return Platform.DAILYMOTION;
        }
        return Platform.OTHER;
    }

    /** Removes share/tracking parameters (igsh, si, utm_*, ...) that are not needed to download. */
    public static String clean(String url) {
        if (url == null) {
            return null;
        }
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        int hash = url.indexOf('#', q);
        String base = url.substring(0, q);
        String query = hash >= 0 ? url.substring(q + 1, hash) : url.substring(q + 1);
        String fragment = hash >= 0 ? url.substring(hash) : "";
        Platform p = detect(url);
        StringBuilder kept = new StringBuilder();
        for (String part : query.split("&")) {
            if (part.isEmpty()) {
                continue;
            }
            String key = part.split("=", 2)[0].toLowerCase(Locale.ROOT);
            boolean tracking = isTracking(p, key);
            if (!tracking) {
                kept.append(kept.length() == 0 ? "" : "&").append(part);
            }
        }
        return base + (kept.length() == 0 ? "" : "?" + kept) + fragment;
    }

    static boolean isTracking(Platform p, String key) {
        if (key.startsWith("utm_")) {
            return true;
        }
        switch (key) {
            case "igsh":
            case "igshid":
            case "si":
            case "fbclid":
            case "gclid":
            case "feature":
            case "pp":
            case "ref":
            case "ref_src":
                return true;
            default:
                break;
        }
        switch (p) {
            case INSTAGRAM:
                // reel/post links need no parameters except a carousel position
                return !key.equals("img_index");
            case TIKTOK:
                return key.equals("_r") || key.equals("_t") || key.equals("is_from_webapp")
                        || key.equals("sender_device") || key.equals("web_id") || key.equals("u_code");
            case TWITTER:
                return key.equals("s") || key.equals("t");
            default:
                return false;
        }
    }

    /** Extracted, cleaned link or null when the text holds no link. */
    public static String normalize(String text) {
        String u = extractUrl(text);
        return u == null ? null : clean(u);
    }
}
