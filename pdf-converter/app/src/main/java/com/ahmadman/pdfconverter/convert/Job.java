package com.ahmadman.pdfconverter.convert;

import android.content.Context;
import android.net.Uri;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Everything a conversion needs: inputs, options, an output folder and progress reporting. */
public final class Job {

    /** Thrown when the user cancels; partial outputs are discarded. */
    public static final class CancelledException extends Exception {
        public CancelledException() {
            super("cancelled");
        }
    }

    /** Thrown when a PDF needs a password (or the given one is wrong). */
    public static final class PasswordException extends Exception {
        public final String fileName;

        public PasswordException(String fileName) {
            super("password required");
            this.fileName = fileName;
        }
    }

    /** A user-facing problem (bad page range, no images found ...). */
    public static final class UserException extends Exception {
        public UserException(String message) {
            super(message);
        }
    }

    public interface Listener {
        void onProgress(int done, int total, String message);
    }

    public final Context context;
    public final List<Uri> inputs;
    public final Map<String, String> options;
    public final File outDir;
    public final File workDir;
    /** Base name for outputs, from the first input without extension. */
    public final String baseName;
    /** Password for encrypted PDFs (may be empty). */
    public final String password;
    private final Listener listener;
    private volatile boolean cancelled;

    public Job(Context context, List<Uri> inputs, Map<String, String> options, File outDir, File workDir,
               String baseName, String password, Listener listener) {
        this.context = context.getApplicationContext();
        this.inputs = inputs;
        this.options = options;
        this.outDir = outDir;
        this.workDir = workDir;
        this.baseName = baseName;
        this.password = password == null ? "" : password;
        this.listener = listener;
    }

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void check() throws CancelledException {
        if (cancelled) {
            throw new CancelledException();
        }
    }

    public void progress(int done, int total, String message) throws CancelledException {
        check();
        if (listener != null) {
            listener.onProgress(done, total, message);
        }
    }

    public String opt(String key, String def) {
        String v = options.get(key);
        return v == null || v.isEmpty() ? def : v;
    }

    public int optInt(String key, int def) {
        try {
            return Integer.parseInt(opt(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean optBool(String key, boolean def) {
        String v = options.get(key);
        return v == null ? def : v.equals("true");
    }

    public String string(int res, Object... args) {
        return context.getString(res, args);
    }

    /** Output file in the job folder, with a unique name. */
    public File out(String name) {
        String safe = Sources.safeName(name);
        File f = new File(outDir, safe);
        int i = 2;
        int dot = safe.lastIndexOf('.');
        String stem = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        while (f.exists()) {
            f = new File(outDir, stem + " (" + i++ + ")" + ext);
        }
        return f;
    }
}
