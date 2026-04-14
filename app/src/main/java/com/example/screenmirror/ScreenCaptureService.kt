package com.example.screenmirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

class ScreenCaptureService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenmirror.ACTION_START"
        const val ACTION_STOP = "com.example.screenmirror.ACTION_STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "screen_capture_channel"
        private const val NOTIFICATION_ID = 1

        // Fraction of the screen that the floating overlay covers.
        // Must stay well below 1.0 so the rest of the screen can actually be
        // captured (FLAG_SECURE blacks out the overlay region in the capture).
        private const val OVERLAY_SCALE = 0.4f
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayManager: OverlayManager? = null
    private var frameProcessor: FrameProcessor? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastWidth = 0
    private var lastHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
                if (data != null) {
                    startCapture(resultCode, data)
                }
            }
            ACTION_STOP -> {
                stopCapture()
            }
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        try {
            createNotificationChannel()
            val notification = buildNotification()

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )

            val projectionManager =
                getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                android.widget.Toast.makeText(this, "Failed to get media projection", android.widget.Toast.LENGTH_LONG).show()
                stopSelf()
                return
            }

            // Android 14+ requires the callback to be registered BEFORE createVirtualDisplay
            mediaProjection!!.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    stopCapture()
                }
            }, null)

            val metrics = currentMetrics()
            setupPipeline(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "Capture error: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            stopCapture()
        }
    }

    private fun currentMetrics(): DisplayMetrics {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun setupPipeline(w: Int, h: Int, density: Int) {
        lastWidth = w
        lastHeight = h

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        // Floating overlay sized to a fraction of the screen. It must NOT cover
        // the whole display: the overlay uses FLAG_SECURE to stay out of the
        // capture loop, and FLAG_SECURE windows show up as solid black in the
        // captured frames. A smaller overlay means only a small rectangle of
        // the mirrored image is blacked out where the overlay itself lives.
        val overlayW = (w * OVERLAY_SCALE).toInt()
        val overlayH = (h * OVERLAY_SCALE).toInt()
        overlayManager = OverlayManager(this, overlayW, overlayH)
        overlayManager!!.show()

        frameProcessor = FrameProcessor(imageReader!!, w, h) { bitmap ->
            overlayManager?.submitFrame(bitmap)
        }
        frameProcessor!!.start()

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenMirror",
            w,
            h,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface,
            null, null
        )
    }

    /**
     * Rebuild the downstream pipeline (ImageReader + overlay + FrameProcessor) at
     * new dimensions, while keeping the existing VirtualDisplay alive. Android 14+
     * treats releasing + recreating a VirtualDisplay from the same MediaProjection
     * as a token-reuse error, so we must resize the existing display in place.
     */
    private fun resizePipeline(w: Int, h: Int, density: Int) {
        frameProcessor?.stop()
        frameProcessor = null

        imageReader?.close()
        imageReader = null

        overlayManager?.dismiss()
        overlayManager = null

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        virtualDisplay?.resize(w, h, density)
        virtualDisplay?.surface = imageReader!!.surface

        val overlayW = (w * OVERLAY_SCALE).toInt()
        val overlayH = (h * OVERLAY_SCALE).toInt()
        overlayManager = OverlayManager(this, overlayW, overlayH)
        overlayManager!!.show()

        frameProcessor = FrameProcessor(imageReader!!, w, h) { bitmap ->
            overlayManager?.submitFrame(bitmap)
        }
        frameProcessor!!.start()

        lastWidth = w
        lastHeight = h
    }

    private fun teardownPipeline() {
        frameProcessor?.stop()
        frameProcessor = null

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        overlayManager?.dismiss()
        overlayManager = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (mediaProjection == null || virtualDisplay == null) return

        // Post so the WindowManager has already applied the new rotation
        mainHandler.post {
            if (mediaProjection == null || virtualDisplay == null) return@post
            val metrics = currentMetrics()
            if (metrics.widthPixels == lastWidth && metrics.heightPixels == lastHeight) {
                return@post
            }
            try {
                resizePipeline(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this, "Rotation error: ${e.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun stopCapture() {
        teardownPipeline()

        mediaProjection?.stop()
        mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_notification)
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(R.string.stop),
                    stopPendingIntent
                ).build()
            )
            .build()
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
