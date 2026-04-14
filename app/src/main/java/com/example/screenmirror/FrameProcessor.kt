package com.example.screenmirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

class FrameProcessor(
    private val imageReader: ImageReader,
    private val overlaySurface: Surface,
    private val width: Int,
    private val height: Int
) {

    private val handlerThread = HandlerThread("FrameProcessor").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val paint = Paint()
    private val flipMatrix = Matrix().apply {
        preScale(-1f, 1f, width / 2f, height / 2f)
    }

    private var reusableBitmap: Bitmap? = null
    @Volatile private var frameCount = 0

    fun start() {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width

                val bitmapWidth = width + rowPadding / pixelStride

                if (reusableBitmap == null ||
                    reusableBitmap!!.width != bitmapWidth ||
                    reusableBitmap!!.height != height
                ) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(
                        bitmapWidth, height, Bitmap.Config.ARGB_8888
                    )
                }

                reusableBitmap!!.copyPixelsFromBuffer(buffer)

                if (!overlaySurface.isValid) return@setOnImageAvailableListener

                // lockHardwareCanvas is the correct path for a Surface backed by a
                // TextureView's SurfaceTexture — software lockCanvas() silently
                // produces no output on some devices.
                val canvas: Canvas = overlaySurface.lockHardwareCanvas() ?: return@setOnImageAvailableListener

                try {
                    // Clear any previous frame so we don't composite over stale pixels
                    canvas.drawColor(android.graphics.Color.BLACK)
                    // Apply horizontal flip (1:1, no scaling)
                    canvas.setMatrix(flipMatrix)
                    canvas.drawBitmap(reusableBitmap!!, 0f, 0f, paint)
                } finally {
                    overlaySurface.unlockCanvasAndPost(canvas)
                }
                frameCount++
            } catch (e: Exception) {
                // Silently skip bad frames
            } finally {
                image.close()
            }
        }, handler)
    }

    fun stop() {
        imageReader.setOnImageAvailableListener(null, null)
        handlerThread.quitSafely()
        reusableBitmap?.recycle()
        reusableBitmap = null
    }
}
