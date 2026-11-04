package com.ahmadman.pdfconverter.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.ahmadman.pdfconverter.R;
import com.ahmadman.pdfconverter.convert.Job;
import com.ahmadman.pdfconverter.convert.Sources;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one conversion at a time on a background thread and keeps its state, so the screen can
 * be closed and reopened (or rotated) while it works.
 */
public final class JobRunner {

    public static final int IDLE = 0;
    public static final int RUNNING = 1;
    public static final int DONE = 2;
    public static final int FAILED = 3;
    public static final int NEED_PASSWORD = 4;
    public static final int CANCELLED = 5;

    public interface Listener {
        void onJobChanged();
    }

    private static final JobRunner INSTANCE = new JobRunner();

    public static JobRunner get() {
        return INSTANCE;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "converter");
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile int state = IDLE;
    private volatile String toolId;
    private volatile int done;
    private volatile int total;
    private volatile String message = "";
    private volatile String error;
    private volatile List<File> outputs = Collections.emptyList();
    private volatile long inputBytes;
    private volatile String passwordFile;
    private Job job;
    private List<Uri> lastInputs = new ArrayList<>();
    private Map<String, String> lastOptions = new HashMap<>();

    private JobRunner() {
    }

    public void addListener(Listener l) {
        listeners.addIfAbsent(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener l : listeners) {
                l.onJobChanged();
            }
        });
    }

    public int state() {
        return state;
    }

    public String toolId() {
        return toolId;
    }

    public int done() {
        return done;
    }

    public int total() {
        return total;
    }

    public String message() {
        return message;
    }

    public String error() {
        return error;
    }

    public List<File> outputs() {
        return outputs;
    }

    public long inputBytes() {
        return inputBytes;
    }

    public String passwordFile() {
        return passwordFile;
    }

    public List<Uri> lastInputs() {
        return lastInputs;
    }

    public Map<String, String> lastOptions() {
        return lastOptions;
    }

    static File jobsDir(Context c) {
        return new File(c.getCacheDir(), "jobs");
    }

    /** Forgets the finished job and deletes its files. */
    public synchronized void reset(Context c) {
        if (state == RUNNING) {
            return;
        }
        state = IDLE;
        outputs = Collections.emptyList();
        error = null;
        Sources.deleteRecursive(jobsDir(c));
        notifyChanged();
    }

    public synchronized void start(Context context, Tool tool, List<Uri> inputs, Map<String, String> options,
                                   String password) {
        if (state == RUNNING) {
            return;
        }
        final Context app = context.getApplicationContext();
        Sources.deleteRecursive(jobsDir(app));
        File root = new File(jobsDir(app), String.valueOf(System.currentTimeMillis()));
        File out = new File(root, "out");
        File work = new File(root, "work");
        //noinspection ResultOfMethodCallIgnored
        out.mkdirs();
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();

        String first = Sources.displayName(app, inputs.get(0));
        String base = Sources.stem(first);
        String pw = password;
        if (options.containsKey("currentPassword") && !options.get("currentPassword").isEmpty()) {
            pw = options.get("currentPassword");
        }
        toolId = tool.id;
        lastInputs = new ArrayList<>(inputs);
        lastOptions = new HashMap<>(options);
        state = RUNNING;
        done = 0;
        total = 0;
        error = null;
        passwordFile = null;
        message = app.getString(R.string.progress_preparing);
        outputs = Collections.emptyList();
        long bytes = 0;
        for (Uri u : inputs) {
            bytes += Math.max(0, Sources.size(app, u));
        }
        inputBytes = bytes;
        final Job j = new Job(app, inputs, options, out, work, base, pw, (d, t, m) -> {
            done = d;
            total = t;
            message = m;
            notifyChanged();
            ConvertService.update(app);
        });
        job = j;
        ConvertService.start(app);
        notifyChanged();
        executor.execute(() -> run(app, tool, j, work, out));
    }

    private void run(Context app, Tool tool, Job j, File work, File out) {
        try {
            if (!PDFBoxResourceLoader.isReady()) {
                PDFBoxResourceLoader.init(app);
            }
            List<File> result = tool.runner.run(j);
            j.check();
            outputs = Collections.unmodifiableList(new ArrayList<>(result));
            state = DONE;
        } catch (Job.CancelledException e) {
            Sources.deleteRecursive(out);
            state = CANCELLED;
        } catch (Job.PasswordException e) {
            passwordFile = e.fileName;
            state = NEED_PASSWORD;
        } catch (Job.UserException e) {
            error = e.getMessage();
            state = FAILED;
        } catch (OutOfMemoryError e) {
            error = app.getString(R.string.err_memory);
            state = FAILED;
        } catch (Throwable e) {
            android.util.Log.e("PdfConverter", "conversion failed", e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            error = app.getString(R.string.err_generic, detail);
            state = FAILED;
        } finally {
            Sources.deleteRecursive(work);
            ConvertService.finished(app);
            notifyChanged();
        }
    }

    public void cancel() {
        Job j = job;
        if (j != null && state == RUNNING) {
            j.cancel();
            message = "…";
            notifyChanged();
        }
    }
}
