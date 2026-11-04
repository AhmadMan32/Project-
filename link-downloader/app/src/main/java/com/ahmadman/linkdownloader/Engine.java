package com.ahmadman.linkdownloader;

import android.content.Context;
import android.content.SharedPreferences;

import com.ahmadman.linkdownloader.core.Commands;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLException;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.youtubedl_android.mapper.VideoFormat;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import java.io.File;
import java.util.List;

import kotlin.Unit;

/** The bundled yt-dlp (Python + QuickJS) and ffmpeg. All calls block: use a background thread. */
public final class Engine {

    /** What the preview card shows. */
    public static final class Info {
        public String title;
        public String uploader;
        public int duration;
        public String thumbnail;
        public int maxHeight;
        public boolean hasVideo;
        public String extractor;
    }

    public interface LineListener {
        void onLine(float percent, String line);
    }

    private static final long UPDATE_EVERY_MS = 3L * 24 * 60 * 60 * 1000;
    private static volatile boolean ready;

    private Engine() {
    }

    /** Unpacks Python/yt-dlp/ffmpeg on first use (a few seconds), then returns immediately. */
    public static synchronized void ensureReady(Context c) throws YoutubeDLException {
        if (ready) {
            return;
        }
        Context app = c.getApplicationContext();
        YoutubeDL.getInstance().init(app);
        FFmpeg.getInstance().init(app);
        ready = true;
    }

    static File instagramCookies(Context c) {
        return new File(new File(c.getFilesDir(), "cookies"), "instagram.txt");
    }

    /** Cookies file to pass for this link, or null. */
    static String cookiesFor(Context c, com.ahmadman.linkdownloader.core.Platform p) {
        if (!p.usesLoginCookies()) {
            return null;
        }
        File f = instagramCookies(c);
        return f.isFile() && f.length() > 40 ? f.getAbsolutePath() : null;
    }

    public static Info info(Context c, String url, String cookies) throws Exception {
        ensureReady(c);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addCommands(Commands.info(cookies));
        VideoInfo v = YoutubeDL.getInstance().getInfo(r);
        Info i = new Info();
        i.title = v.getTitle() != null ? v.getTitle() : v.getFulltitle();
        i.uploader = v.getUploader();
        i.duration = v.getDuration();
        i.thumbnail = v.getThumbnail();
        i.extractor = v.getExtractorKey();
        int max = v.getHeight();
        boolean video = max > 0;
        List<VideoFormat> formats = v.getFormats();
        if (formats != null) {
            for (VideoFormat f : formats) {
                String vc = f.getVcodec();
                boolean hasVideo = f.getHeight() > 0 && (vc == null || !vc.equals("none"));
                if (hasVideo) {
                    video = true;
                    max = Math.max(max, f.getHeight());
                }
            }
        }
        i.maxHeight = max;
        i.hasVideo = video;
        return i;
    }

    /** Runs yt-dlp; progress lines go to the listener. Throws CanceledException when cancelled. */
    public static YoutubeDLResponse run(Context c, String url, List<String> args, String processId,
                                        LineListener listener) throws Exception {
        ensureReady(c);
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addCommands(args);
        return YoutubeDL.getInstance().execute(r, processId, (percent, eta, line) -> {
            if (listener != null) {
                listener.onLine(percent == null ? -1f : percent, line);
            }
            return Unit.INSTANCE;
        });
    }

    public static void cancel(String processId) {
        try {
            YoutubeDL.getInstance().destroyProcessById(processId);
        } catch (RuntimeException ignored) {
            // not running
        }
    }

    public static String version(Context c) {
        try {
            String v = YoutubeDL.getInstance().versionName(c.getApplicationContext());
            if (v == null || v.isEmpty()) {
                v = YoutubeDL.getInstance().version(c.getApplicationContext());
            }
            return v == null ? "" : v;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** Downloads the newest stable yt-dlp. Returns true when a new version was installed. */
    public static boolean update(Context c) throws Exception {
        ensureReady(c);
        YoutubeDL.UpdateStatus s = YoutubeDL.getInstance().updateYoutubeDL(c.getApplicationContext(),
                YoutubeDL.UpdateChannel._STABLE);
        prefs(c).edit().putLong("last_update", System.currentTimeMillis()).apply();
        return s == YoutubeDL.UpdateStatus.DONE;
    }

    /** Sites change often: refresh yt-dlp on first start and then every few days. */
    public static boolean updateDue(Context c) {
        return System.currentTimeMillis() - prefs(c).getLong("last_update", 0) > UPDATE_EVERY_MS;
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
}
