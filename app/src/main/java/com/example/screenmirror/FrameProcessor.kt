package com.example.screenmirror

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

class FrameProcessor(
    private val imageReader: ImageReader,
    private val width: Int,
    private val height: Int,
    private val onFrame: (Bitmap) -> Unit
) {

    private val handlerThread = HandlerThread("FrameProcessor").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Ping-pong buffers so the worker thread can populate the next frame while
    // the main thread is still rendering the previous one.
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var writeToA = true

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

                val target: Bitmap = if (writeToA) {
                    if (bufferA == null ||
                        bufferA!!.width != bitmapWidth ||
                        bufferA!!.height != height
                    ) {
                        bufferA?.recycle()
                        bufferA = Bitmap.createBitmap(
                            bitmapWidth, height, Bitmap.Config.ARGB_8888
                        )
                    }
                    bufferA!!
                } else {
                    if (bufferB == null ||
                        bufferB!!.width != bitmapWidth ||
                        bufferB!!.height != height
                    ) {
                        bufferB?.recycle()
                        bufferB = Bitmap.createBitmap(
                            bitmapWidth, height, Bitmap.Config.ARGB_8888
                        )
                    }
                    bufferB!!
                }

                target.copyPixelsFromBuffer(buffer)
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
        bufferA?.recycle()
        bufferA = null
        bufferB?.recycle()
        bufferB = null
    }
}
