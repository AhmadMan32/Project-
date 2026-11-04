package com.ahmadman.linkdownloader.core;

/** Sites recognised from the link. Anything else still goes to yt-dlp (1000+ sites). */
public enum Platform {
    YOUTUBE("YouTube", 0xFFFF0033),
    INSTAGRAM("Instagram", 0xFFD62976),
    TIKTOK("TikTok", 0xFF111111),
    TWITTER("X / Twitter", 0xFF0F1419),
    FACEBOOK("Facebook", 0xFF1877F2),
    VIMEO("Vimeo", 0xFF1AB7EA),
    REDDIT("Reddit", 0xFFFF4500),
    SOUNDCLOUD("SoundCloud", 0xFFFF5500),
    PINTEREST("Pinterest", 0xFFE60023),
    TWITCH("Twitch", 0xFF9146FF),
    DAILYMOTION("Dailymotion", 0xFF0066DC),
    OTHER("Web", 0xFF5B6470);

    public final String label;
    public final int color;

    Platform(String label, int color) {
        this.label = label;
        this.color = color;
    }

    /** Instagram often needs a logged-in session (cookies) for reels. */
    public boolean usesLoginCookies() {
        return this == INSTAGRAM;
    }

    /** Mostly-audio sites: MP3 is the natural default. */
    public boolean audioFirst() {
        return this == SOUNDCLOUD;
    }
}
