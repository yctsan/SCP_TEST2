package com.example.screenmirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Reads frames from [imageReader] (which captures the whole screen) and emits
 * a cropped sub-region via [onFrame]. The crop region is `(0, cropTop) →
 * (captureWidth, cropTop + cropHeight)` — i.e. a horizontal strip. This is used
 * to exclude the overlay's own black-out area (the overlay uses FLAG_SECURE and
 * sits at the top of the screen) so the mirror only shows the portion of the
 * physical display that is NOT covered by the overlay.
 */
class FrameProcessor(
    private val imageReader: ImageReader,
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val cropTop: Int,
    private val cropHeight: Int,
    private val onFrame: (Bitmap) -> Unit
) {

    private val handlerThread = HandlerThread("FrameProcessor").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Scratch bitmap at the full capture resolution (includes row-stride padding)
    private var scratchBitmap: Bitmap? = null

    // Ping-pong output buffers at the cropped size so the worker can fill one
    // while the main thread renders the other.
    private var outputA: Bitmap? = null
    private var outputB: Bitmap? = null
    private var writeToA = true

    fun start() {
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * captureWidth
                val bitmapWidth = captureWidth + rowPadding / pixelStride

                if (scratchBitmap == null ||
                    scratchBitmap!!.width != bitmapWidth ||
                    scratchBitmap!!.height != captureHeight
                ) {
                    scratchBitmap?.recycle()
                    scratchBitmap = Bitmap.createBitmap(
                        bitmapWidth, captureHeight, Bitmap.Config.ARGB_8888
                    )
                }
                scratchBitmap!!.copyPixelsFromBuffer(buffer)

                val target: Bitmap = if (writeToA) {
                    if (outputA == null ||
                        outputA!!.width != captureWidth ||
                        outputA!!.height != cropHeight
                    ) {
                        outputA?.recycle()
                        outputA = Bitmap.createBitmap(
                            captureWidth, cropHeight, Bitmap.Config.ARGB_8888
                        )
                    }
                    outputA!!
                } else {
                    if (outputB == null ||
                        outputB!!.width != captureWidth ||
                        outputB!!.height != cropHeight
                    ) {
                        outputB?.recycle()
                        outputB = Bitmap.createBitmap(
                            captureWidth, cropHeight, Bitmap.Config.ARGB_8888
                        )
                    }
                    outputB!!
                }

                // Copy the crop strip from scratch -> target (strips row padding
                // and restricts to the non-overlay region in one blit).
                val canvas = Canvas(target)
                val srcRect = Rect(0, cropTop, captureWidth, cropTop + cropHeight)
                val dstRect = Rect(0, 0, captureWidth, cropHeight)
                canvas.drawBitmap(scratchBitmap!!, srcRect, dstRect, null)

                writeToA = !writeToA

                mainHandler.post { onFrame(target) }
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
        scratchBitmap?.recycle()
        scratchBitmap = null
        outputA?.recycle()
        outputA = null
        outputB?.recycle()
        outputB = null
    }
}
