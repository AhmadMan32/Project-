package com.ahmadman.kenarpanel;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.List;

/**
 * What is playing (YouTube Music, Spotify, ...) and the buttons to control it, through Android's
 * media sessions: the same thing the system's own music controls use.
 */
final class Music {

    static final String YT_MUSIC = "com.google.android.apps.youtube.music";

    enum State { NO_ACCESS, IDLE, ACTIVE }

    interface Listener {
        void onMusic();
    }

    /** One moment of the current song. */
    static final class Track {
        String pkg;
        String title;
        String artist;
        Bitmap art;
        long duration;
        boolean playing;
        long position;
        long updatedAt;
        float speed = 1f;
        boolean canSeek;
    }

    /** The player being controlled (a media session; tests use a fake). */
    interface Source {
        Track current();

        void play();

        void pause();

        void next();

        void previous();

        void seek(long ms);
    }

    /** Tests. */
    static Source testSource;
    static Boolean testAccess;

    private final Context app;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController controller;
    private boolean started;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            listener.onMusic();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            listener.onMusic();
        }

        @Override
        public void onSessionDestroyed() {
            refreshSessions();
        }
    };

    Music(Context c, Listener listener) {
        this.app = c.getApplicationContext();
        this.listener = listener;
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, MusicAccess.class);
    }

    /** Has the user switched on "Notification access" for this app? */
    static boolean hasAccess(Context c) {
        if (testAccess != null) {
            return testAccess;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            android.app.NotificationManager nm = c.getSystemService(android.app.NotificationManager.class);
            if (nm != null) {
                return nm.isNotificationListenerAccessGranted(component(c));
            }
        }
        String flat = android.provider.Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(component(c).flattenToString());
    }

    State state() {
        if (testSource != null) {
            return testSource.current() == null ? State.IDLE : State.ACTIVE;
        }
        if (!hasAccess(app)) {
            return State.NO_ACCESS;
        }
        return controller == null ? State.IDLE : State.ACTIVE;
    }

    /** Starts following the music (while the panel is shown). */
    void start() {
        if (started) {
            return;
        }
        started = true;
        if (testSource != null || !hasAccess(app)) {
            listener.onMusic();
            return;
        }
        try {
            msm = app.getSystemService(MediaSessionManager.class);
            msm.addOnActiveSessionsChangedListener(sessions, component(app), main);
            pick(msm.getActiveSessions(component(app)));
        } catch (RuntimeException e) {
            msm = null; // access was switched off meanwhile
            listener.onMusic();
        }
    }

    void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (msm != null) {
            try {
                msm.removeOnActiveSessionsChangedListener(sessions);
            } catch (RuntimeException ignored) {
                // already gone
            }
            msm = null;
        }
        use(null);
    }

    private void refreshSessions() {
        if (msm == null) {
            use(null);
            listener.onMusic();
            return;
        }
        try {
            pick(msm.getActiveSessions(component(app)));
        } catch (RuntimeException e) {
            use(null);
            listener.onMusic();
        }
    }

    /** The playing one; otherwise YouTube Music; otherwise the most recent player. */
    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                if (playing(c)) {
                    best = c;
                    break;
                }
            }
            if (best == null) {
                for (MediaController c : list) {
                    if (YT_MUSIC.equals(c.getPackageName())) {
                        best = c;
                        break;
                    }
                }
            }
            if (best == null && !list.isEmpty()) {
                best = list.get(0);
            }
        }
        use(best);
        listener.onMusic();
    }

    private static boolean playing(MediaController c) {
        PlaybackState s = c.getPlaybackState();
        return s != null && (s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING);
    }

    private void use(MediaController c) {
        if (controller != null && (c == null || !controller.getSessionToken().equals(c.getSessionToken()))) {
            controller.unregisterCallback(callback);
        }
        if (c != null && (controller == null || !controller.getSessionToken().equals(c.getSessionToken()))) {
            c.registerCallback(callback, main);
        }
        controller = c;
    }

    Track track() {
        if (testSource != null) {
            return testSource.current();
        }
        MediaController c = controller;
        if (c == null) {
            return null;
        }
        Track t = new Track();
        t.pkg = c.getPackageName();
        MediaMetadata m = c.getMetadata();
        if (m != null) {
            t.title = text(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            t.artist = text(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            t.duration = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            t.art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (t.art == null) {
                t.art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
            if (t.art == null) {
                t.art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            }
        }
        PlaybackState s = c.getPlaybackState();
        if (s != null) {
            t.playing = s.getState() == PlaybackState.STATE_PLAYING || s.getState() == PlaybackState.STATE_BUFFERING;
            t.position = s.getPosition();
            t.updatedAt = s.getLastPositionUpdateTime();
            t.speed = s.getPlaybackSpeed();
            t.canSeek = (s.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
        }
        return t;
    }

    private static String text(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) {
                return v.toString();
            }
        }
        return null;
    }

    static long now() {
        return SystemClock.elapsedRealtime();
    }

    void playPause() {
        Track t = track();
        if (t == null) {
            return;
        }
        if (testSource != null) {
            if (t.playing) {
                testSource.pause();
            } else {
                testSource.play();
            }
            listener.onMusic();
            return;
        }
        if (controller == null) {
            return;
        }
        if (t.playing) {
            controller.getTransportControls().pause();
        } else {
            controller.getTransportControls().play();
        }
    }

    void next() {
        if (testSource != null) {
            testSource.next();
            listener.onMusic();
        } else if (controller != null) {
            controller.getTransportControls().skipToNext();
        }
    }

    void previous() {
        if (testSource != null) {
            testSource.previous();
            listener.onMusic();
        } else if (controller != null) {
            controller.getTransportControls().skipToPrevious();
        }
    }

    void seek(long ms) {
        if (testSource != null) {
            testSource.seek(ms);
            listener.onMusic();
        } else if (controller != null) {
            controller.getTransportControls().seekTo(ms);
        }
    }
}
