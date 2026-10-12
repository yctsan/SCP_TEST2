package com.example.screenmirror

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

class OverlayManager(
    private val context: Context,
    private val overlayWidth: Int,
    private val overlayHeight: Int
) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var containerView: FrameLayout? = null
    private var textureView: TextureView? = null
    private var surface: Surface? = null

    fun show(onSurfaceReady: (Surface) -> Unit) {
        val container = FrameLayout(context)
        container.setBackgroundColor(Color.BLACK)
        containerView = container

        val view = TextureView(context)
        textureView = view
        container.addView(view, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val params = WindowManager.LayoutParams(
            overlayWidth,
            overlayHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            // A visible overlay window with an orientation request holds the whole display at that rotation.
            screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        }

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                st: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                applyFlip(view, width, height)
                surface = Surface(st)
                onSurfaceReady(surface!!)
            }

            override fun onSurfaceTextureSizeChanged(
                st: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                applyFlip(view, width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
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

    fun dismiss() {
        containerView?.let { windowManager.removeView(it) }
        containerView = null
        textureView = null
        surface?.release()
        surface = null
    }

    private fun applyFlip(view: TextureView, width: Int, height: Int) {
        val m = Matrix()
        m.setScale(-1f, 1f, width / 2f, height / 2f)
        view.setTransform(m)
    }
}
