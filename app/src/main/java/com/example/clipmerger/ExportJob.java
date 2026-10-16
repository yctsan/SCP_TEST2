package com.example.clipmerger;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.DocumentsContract;

import java.io.FileOutputStream;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One export run. The work runs on a background thread owned by
 * {@link ExportService}; all fields other than {@link #cancel} are only read
 * and written on the main thread.
 */
final class ExportJob {

    interface Listener {
        void onExportChanged(ExportJob job);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static ExportJob current;

    /** The running job, or a finished one whose result the user has not seen yet. */
    static ExportJob current() {
        return current;
    }

    static ExportJob create(List<Clip> clips, Export.Options options, Uri output) {
        current = new ExportJob(new ArrayList<>(clips), options, output);
        return current;
    }

    /** Forgets a finished job once its result has been shown. */
    static void clearFinished() {
        if (current != null && !current.running) current = null;
    }

    final List<Clip> clips;
    final Export.Options options;
    final Uri output;
    final AtomicBoolean cancel = new AtomicBoolean();

    boolean started;
    boolean running = true;
    long doneUs, totalUs;
    String warning;
    Throwable error;
    private long startedAt;
    private long finishedAt;
    private final ArrayList<Listener> listeners = new ArrayList<>();

    private ExportJob(List<Clip> clips, Export.Options options, Uri output) {
        this.clips = clips;
        this.options = options;
        this.output = output;
        for (Clip c : clips) totalUs += c.keptUs();
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    float fraction() {
        return totalUs > 0 ? Math.min(1f, doneUs / (float) totalUs) : 0f;
    }

    long elapsedMs() {
        if (startedAt == 0) return 0;
        return (running ? SystemClock.elapsedRealtime() : finishedAt) - startedAt;
    }

    /** Estimated milliseconds left, or -1 while there is not enough progress to estimate. */
    long remainingMs() {
        float f = fraction();
        long elapsed = elapsedMs();
        if (!running || f < 0.01f || elapsed < 3000) return -1;
        return (long) (elapsed * (1 - f) / f);
    }

    /** "about 3 min 20 s left", or a placeholder before an estimate exists. */
    String remainingText() {
        long ms = remainingMs();
        if (ms < 0) return "estimating time left…";
        return "about " + formatDuration(ms) + " left";
    }

    String progressText() {
        return String.format(Locale.US, "%.1f%% · %s", fraction() * 100, remainingText());
    }

    static String formatDuration(long ms) {
        long s = (ms + 999) / 1000;
        if (s >= 3600) return String.format(Locale.US, "%d h %02d min", s / 3600, (s / 60) % 60);
        if (s >= 60) return String.format(Locale.US, "%d min %02d s", s / 60, s % 60);
        return s + " s";
    }

    /** Runs the export on the calling (background) thread. */
    void run(final Context ctx) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                startedAt = SystemClock.elapsedRealtime();
                notifyListeners();
            }
        });
        Export.Progress progress = new Export.Progress() {
            private long lastPost;

            @Override
            public void update(final long done, long total) throws Export.Cancelled {
                if (cancel.get()) throw new Export.Cancelled();
                long now = SystemClock.elapsedRealtime();
                if (now - lastPost < 250) return;
                lastPost = now;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        doneUs = done;
                        notifyListeners();
                    }
                });
            }
        };
        String w = null;
        Throwable err = null;
        ParcelFileDescriptor pfd = null;
        try {
            pfd = ctx.getContentResolver().openFileDescriptor(output, "rw");
            if (pfd == null) throw new Export.Failure("Cannot open the output file.");
            FileChannel ch = new FileOutputStream(pfd.getFileDescriptor()).getChannel();
            w = options.reencode
                    ? Transcoder.run(ctx, clips, options, ch, progress)
                    : LosslessMerger.run(ctx, clips, ch, progress);
            ch.close();
        } catch (Throwable t) {
            err = t;
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (Exception ignored) {
                }
            }
        }
        if (err != null) {
            try {
                DocumentsContract.deleteDocument(ctx.getContentResolver(), output);
            } catch (Exception ignored) {
            }
        }
        final String fw = w;
        final Throwable fe = err;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                warning = fw;
                error = fe;
                if (fe == null) doneUs = totalUs;
                running = false;
                finishedAt = SystemClock.elapsedRealtime();
                notifyListeners();
            }
        });
    }

    private void notifyListeners() {
        for (Listener l : new ArrayList<>(listeners)) l.onExportChanged(this);
    }
}
