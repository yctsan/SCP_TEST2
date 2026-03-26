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

                val canvas: Canvas = overlaySurface.lockCanvas(null) ?: return@setOnImageAvailableListener

                // Apply horizontal flip (1:1, no scaling)
                canvas.setMatrix(flipMatrix)
                canvas.drawBitmap(reusableBitmap!!, 0f, 0f, paint)

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
