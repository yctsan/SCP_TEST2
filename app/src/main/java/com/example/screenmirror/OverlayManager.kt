package com.example.screenmirror

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.graphics.SurfaceTexture
import android.view.View
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
    private var surfaceCallback: ((Surface) -> Unit)? = null

    fun show(onSurfaceReady: (Surface) -> Unit) {
        surfaceCallback = onSurfaceReady

        // Create a container with a visible red border so the overlay is clearly visible
        val container = FrameLayout(context)
        container.setBackgroundColor(Color.TRANSPARENT)
        container.setPadding(3, 3, 3, 3)
        container.setBackgroundColor(Color.argb(180, 255, 0, 0))
        containerView = container

        val view = TextureView(context)
        // Exclude overlay from screen capture to avoid recursive feedback loop (API 34+)
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val method = view.javaClass.getMethod("setExcludeFromScreenCapture", Boolean::class.javaPrimitiveType)
                method.invoke(view, true)
            }
        } catch (_: Exception) {}

        // Also exclude the container
        try {
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                val method = container.javaClass.getMethod("setExcludeFromScreenCapture", Boolean::class.javaPrimitiveType)
                method.invoke(container, true)
            }
        } catch (_: Exception) {}

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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 100
        }

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                surface = Surface(surfaceTexture)
                surfaceCallback?.invoke(surface!!)
            }

            override fun onSurfaceTextureSizeChanged(
                surfaceTexture: SurfaceTexture,
                width: Int,
                height: Int
            ) {}

            override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {}
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
        containerView?.let {
            windowManager.removeView(it)
        }
        containerView = null
        textureView = null
        surface?.release()
        surface = null
    }
}
