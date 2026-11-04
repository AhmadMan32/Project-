package com.ahmadman.linkdownloader;

import org.json.JSONException;
import org.json.JSONObject;

/** One download (video or audio) in the list. */
public final class Item {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    public String id;
    public String url;
    public String platform;
    public String title;
    public String uploader;
    public String thumbnail;
    /** {@link com.ahmadman.linkdownloader.core.Commands#VIDEO} or AUDIO. */
    public String kind;
    public int height;
    public String audioFormat;
    public String status = QUEUED;
    public float percent;
    /** Live line: speed, size, step. */
    public String detail = "";
    public String reason;
    public String error;
    public String outputUri;
    public String fileName;
    public long size;
    /** e.g. "1080p" or "320 kbps", read from the saved file. */
    public String quality;
    public long created;
    /** Set when the user cancels a running download (not saved). */
    public transient volatile boolean cancelRequested;

    public boolean isVideo() {
        return com.ahmadman.linkdownloader.core.Commands.VIDEO.equals(kind);
    }

    public boolean isActive() {
        return QUEUED.equals(status) || RUNNING.equals(status);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id).put("url", url).put("platform", platform).put("title", title).put("uploader", uploader)
                .put("thumbnail", thumbnail).put("kind", kind).put("height", height).put("audioFormat", audioFormat)
                .put("status", status).put("percent", (double) percent).put("reason", reason).put("error", error)
                .put("outputUri", outputUri).put("fileName", fileName).put("size", size).put("quality", quality)
                .put("created", created);
        return o;
    }

    static Item fromJson(JSONObject o) {
        Item i = new Item();
        i.id = o.optString("id");
        i.url = o.optString("url");
        i.platform = o.optString("platform", "OTHER");
        i.title = o.isNull("title") ? null : o.optString("title");
        i.uploader = o.isNull("uploader") ? null : o.optString("uploader");
        i.thumbnail = o.isNull("thumbnail") ? null : o.optString("thumbnail");
        i.kind = o.optString("kind");
        i.height = o.optInt("height");
        i.audioFormat = o.isNull("audioFormat") ? null : o.optString("audioFormat");
        i.status = o.optString("status", FAILED);
        i.percent = (float) o.optDouble("percent", 0);
        i.reason = o.isNull("reason") ? null : o.optString("reason");
        i.error = o.isNull("error") ? null : o.optString("error");
        i.outputUri = o.isNull("outputUri") ? null : o.optString("outputUri");
        i.fileName = o.isNull("fileName") ? null : o.optString("fileName");
        i.size = o.optLong("size");
        i.quality = o.isNull("quality") ? null : o.optString("quality");
        i.created = o.optLong("created");
        return i;
    }
}
