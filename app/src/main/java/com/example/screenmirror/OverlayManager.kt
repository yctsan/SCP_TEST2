package com.example.screenmirror

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            // NOTE: we intentionally do NOT use FLAG_SECURE here. FLAG_SECURE
            // would exclude this window from MediaProjection captures, but the
            // excluded area is rendered as solid black in the capture. With a
            // full-screen overlay, that means the captured frame is entirely
            // black and nothing mirrors. The tradeoff: when projecting the
            // whole screen, the capture includes this overlay and the horizontal
            // flip produces feedback flicker. The app is intended to be used
            // with single-app projection, where the selected app is captured
            // independently of this overlay — that mode works cleanly.
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        // Drag to move the overlay
        container.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var touchX = 0f
            private var touchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(container, params)
                        return true
                    }
                }
                return false
            }
        })

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
