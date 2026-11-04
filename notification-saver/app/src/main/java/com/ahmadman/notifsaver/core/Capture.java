package com.ahmadman.notifsaver.core;

/** One saved notification (or one chat message out of a messaging notification). */
public final class Capture {
    public long id;
    public String pkg;
    public String app;
    public String title;
    public String text;
    /** Expanded text (big text / inbox lines), if different from {@link #text}. */
    public String big;
    public String sub;
    /** Chat or thread name used for grouping; usually the title. */
    public String conversation;
    /** Who wrote a chat message (null for normal notifications). */
    public String sender;
    /** Milliseconds since 1970; message time for chats, post time otherwise. */
    public long time;
    public boolean message;
    /** "This message was deleted" placeholder. */
    public boolean deleted;
    /** The original text of a chat message that its sender deleted later. */
    public boolean recovered;
    public String key;
    public String hash;

    /** The text worth showing: the expanded version when there is one. */
    public String body() {
        if (big != null && !big.isEmpty() && (text == null || big.length() > text.length())) {
            return big;
        }
        return text == null ? "" : text;
    }
}
