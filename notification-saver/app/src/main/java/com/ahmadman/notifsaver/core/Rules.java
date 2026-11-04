package com.ahmadman.notifsaver.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Decisions about what to save and how to recognise duplicates. */
public final class Rules {

    /** A normal notification re-posted with the same content within this time is the same one. */
    public static final long DUPLICATE_WINDOW_MS = 10 * 60 * 1000L;

    private static final Pattern DELETED = Pattern.compile(
            "(?iu)^\\s*(🚫\\s*)?(this message was deleted|you deleted this message|bu mesaj silindi|"
                    + "bu mesajı sildiniz|mesaj silindi|this message was removed|message deleted)\\.?\\s*$");
    /** "Ahmet (3 messages)" / "Ahmet (3 mesaj)" / "Ahmet: 2 new messages" -> "Ahmet". */
    private static final Pattern COUNT_SUFFIX = Pattern.compile(
            "(?iu)\\s*(\\(\\d+\\s*(new\\s+)?(messages?|mesaj|yeni mesaj)\\)|[:·]\\s*\\d+\\s*(new\\s+)?(messages?|yeni mesaj|mesaj))\\s*$");

    private Rules() {
    }

    public static boolean isDeletedMarker(String text) {
        return text != null && DELETED.matcher(text).matches();
    }

    public static String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        String t = COUNT_SUFFIX.matcher(title).replaceAll("").trim();
        return t.isEmpty() ? title.trim() : t;
    }

    /** Which apps to record. */
    public static boolean shouldRecord(String pkg, boolean onlySelected, Set<String> selected, Set<String> excluded,
                                       String ownPackage) {
        if (pkg == null || pkg.equals(ownPackage)) {
            return false;
        }
        if (onlySelected) {
            return selected.contains(pkg);
        }
        return !excluded.contains(pkg);
    }

    public static String hash(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            sb.append(p == null ? "\u0000" : p.toString()).append('\u0001');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : d) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(sb.toString().hashCode());
        }
    }

    /**
     * Search folding: lower case and Turkish letters to ASCII so "sukru" finds "Şükrü" and
     * "ISIK" finds "ışık".
     */
    public static String fold(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'İ': case 'I': case 'ı': case 'i': case 'Î': case 'î':
                    sb.append('i');
                    break;
                case 'Ş': case 'ş':
                    sb.append('s');
                    break;
                case 'Ğ': case 'ğ':
                    sb.append('g');
                    break;
                case 'Ü': case 'ü': case 'Û': case 'û':
                    sb.append('u');
                    break;
                case 'Ö': case 'ö':
                    sb.append('o');
                    break;
                case 'Ç': case 'ç':
                    sb.append('c');
                    break;
                case 'Â': case 'â': case 'Á': case 'á': case 'À': case 'à': case 'Ä': case 'ä':
                    sb.append('a');
                    break;
                case 'É': case 'é': case 'È': case 'è': case 'Ê': case 'ê':
                    sb.append('e');
                    break;
                default:
                    sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** SQL LIKE pattern for a folded search term (wildcards in the input are escaped). */
    public static String likePattern(String query) {
        String f = fold(query.trim()).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + f + "%";
    }
}
