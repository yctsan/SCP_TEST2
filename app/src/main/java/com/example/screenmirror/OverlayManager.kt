package com.example.screenmirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView

class OverlayManager(
    private val context: Context,
    private val overlayWidth: Int,
    private val overlayHeight: Int
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var containerView: FrameLayout? = null
    private var imageView: ImageView? = null

    fun show() {
        val container = FrameLayout(context)
        container.setBackgroundColor(Color.TRANSPARENT)
        containerView = container

        // Plain ImageView — we push bitmaps to it each frame via setImageBitmap.
        // scaleX = -1f gives us a free horizontal flip, no canvas matrix needed.
        // FIT_XY stretches the (smaller) captured strip to fill the full overlay.
        val img = ImageView(context)
        img.scaleType = ImageView.ScaleType.FIT_XY
        img.scaleX = -1f
        imageView = img
        container.addView(img, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    // FLAG_SECURE excludes the overlay from MediaProjection captures
                    // so we don't get a feedback loop. The captured frame contains
                    // a solid-black rectangle wherever the overlay sits; the service
                    // is responsible for cropping the frame to only the non-overlay
                    // region before passing it to submitFrame().
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        windowManager.addView(container, params)
    }

    /** Must be called on the main thread. */
    fun submitFrame(bitmap: Bitmap) {
        imageView?.setImageBitmap(bitmap)
    }

    fun dismiss() {
        containerView?.let {
            windowManager.removeView(it)
        }
        containerView = null
        imageView = null
    }
}
