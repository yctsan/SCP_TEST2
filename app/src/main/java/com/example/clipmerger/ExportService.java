package com.example.clipmerger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

/**
 * Foreground service that keeps an export running while the app is in the
 * background, and shows its progress and the estimated time left in a
 * notification.
 */
public class ExportService extends Service implements ExportJob.Listener {

    static final String ACTION_CANCEL = "com.example.clipmerger.CANCEL_EXPORT";
    static final int ID_PROGRESS = 1;
    static final int ID_DONE = 2;
    private static final String CHANNEL_PROGRESS = "export";
    private static final String CHANNEL_DONE = "export_done";

    private ExportJob job;
    private PowerManager.WakeLock wakeLock;
    private long lastNotified;

    /** Starts the export of {@link ExportJob#current()}. Call while the app is in the foreground. */
    static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, ExportService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            if (job != null) job.cancel.set(true);
            return START_NOT_STICKY;
        }
        if (job != null) return START_NOT_STICKY; // already running
        createChannels();
        final ExportJob j = ExportJob.current();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID_PROGRESS, progressNotification(j), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(ID_PROGRESS, progressNotification(j));
        }
        if (j == null || j.started) {
            stopSelf();
            return START_NOT_STICKY;
        }
        job = j;
        job.started = true;
        job.addListener(this);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ClipMerger:export");
        wakeLock.acquire();
        new Thread(new Runnable() {
            @Override
            public void run() {
                j.run(getApplicationContext());
            }
        }, "export").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onExportChanged(ExportJob j) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (j.running) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastNotified < 1000) return;
            lastNotified = now;
            nm.notify(ID_PROGRESS, progressNotification(j));
            return;
        }
        j.removeListener(this);
        if (!(j.error instanceof Export.Cancelled)) nm.notify(ID_DONE, doneNotification(j));
        stopForeground(STOP_FOREGROUND_REMOVE);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        job = null;
        stopSelf();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (job != null) {
            job.removeListener(this);
            job.cancel.set(true);
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification progressNotification(ExportJob j) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Exporting video")
                .setContentIntent(openApp())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS);
        if (j != null) {
            b.setContentText(j.progressText());
            b.setProgress(1000, (int) (j.fraction() * 1000), false);
            Intent cancel = new Intent(this, ExportService.class).setAction(ACTION_CANCEL);
            b.addAction(new Notification.Action.Builder(null, "Cancel",
                    PendingIntent.getService(this, 1, cancel, PendingIntent.FLAG_IMMUTABLE)).build());
        } else {
            b.setProgress(0, 0, true);
        }
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        return b.build();
    }

    private Notification doneNotification(ExportJob j) {
        boolean ok = j.error == null;
        return new Notification.Builder(this, CHANNEL_DONE)
                .setSmallIcon(ok ? android.R.drawable.stat_sys_download_done : android.R.drawable.stat_notify_error)
                .setContentTitle(ok ? "Export finished" : "Export failed")
                .setContentText(ok ? "Took " + ExportJob.formatDuration(j.elapsedMs()) + ". Tap to open."
                        : "Tap to see what went wrong.")
                .setContentIntent(openApp())
                .setAutoCancel(true)
                .build();
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void createChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS, "Export progress",
                NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(progress);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_DONE, "Export finished",
                NotificationManager.IMPORTANCE_DEFAULT));
    }
}
