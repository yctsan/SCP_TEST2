package com.example.screenmirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

class FrameProcessor(
    private val imageReader: ImageReader,
    private val overlaySurface: Surface,
    private val captureWidth: Int,
    private val captureHeight: Int,
    private val overlayWidth: Int,
    private val overlayHeight: Int
) {

    private val handlerThread = HandlerThread("FrameProcessor").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val flipMatrix = Matrix().apply {
        preScale(-1f, 1f, overlayWidth / 2f, overlayHeight / 2f)
    }
    private val destRect = Rect(0, 0, overlayWidth, overlayHeight)

    private var reusableBitmap: Bitmap? = null

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

                if (reusableBitmap == null ||
                    reusableBitmap!!.width != bitmapWidth ||
                    reusableBitmap!!.height != captureHeight
                ) {
                    reusableBitmap?.recycle()
                    reusableBitmap = Bitmap.createBitmap(
                        bitmapWidth, captureHeight, Bitmap.Config.ARGB_8888
                    )
                }

                reusableBitmap!!.copyPixelsFromBuffer(buffer)

                if (!overlaySurface.isValid) return@setOnImageAvailableListener

                val canvas: Canvas = overlaySurface.lockCanvas(null) ?: return@setOnImageAvailableListener

                // Apply horizontal flip and scale to overlay size
                canvas.setMatrix(flipMatrix)
                canvas.drawBitmap(
                    reusableBitmap!!,
                    Rect(0, 0, captureWidth, captureHeight),
                    destRect,
                    paint
                )

                overlaySurface.unlockCanvasAndPost(canvas)
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
