package com.example.clipmerger;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Copies decoded frames to the encoder through OpenGL ES: the decoder renders
 * into a SurfaceTexture, which is drawn (scaled / letterboxed) onto an EGL
 * window surface backed by the encoder's input Surface.
 */
final class GlFrameCopier {

    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTexCoord;\n" +
            "uniform mat4 uSTMatrix;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTexCoord = (uSTMatrix * aTexCoord).xy;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTexture, vTexCoord);\n" +
            "}\n";

    private static final float[] QUAD = {
            // x, y, u, v
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
    };

    private final Surface encoderInput;
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private final int width, height;
    private int program, aPosition, aTexCoord, uSTMatrix;
    private final FloatBuffer quad;

    GlFrameCopier(Surface encoderInput, int width, int height) throws IOException {
        this.encoderInput = encoderInput;
        this.width = width;
        this.height = height;
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new IOException("No EGL display");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IOException("eglInitialize failed");
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE};
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0) || num[0] == 0) {
            throw new IOException("No suitable EGL config");
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) throw new IOException("eglCreateContext failed");
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderInput, new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) throw new IOException("eglCreateWindowSurface failed");
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) throw new IOException("eglMakeCurrent failed");

        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        program = createProgram();
        aPosition = GLES20.glGetAttribLocation(program, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord");
        uSTMatrix = GLES20.glGetUniformLocation(program, "uSTMatrix");
    }

    /** Creates the surface a decoder renders into. Must be called on the thread that owns this copier. */
    Input createInput(Handler callbackHandler) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return new Input(tex[0], callbackHandler);
    }

    /** Draws the latest frame of {@code in}, fitted into the output with black bars if needed. */
    void draw(Input in, int srcWidth, int srcHeight) throws IOException {
        int vw = width, vh = height;
        if ((long) srcWidth * height > (long) width * srcHeight) {
            vh = (int) ((long) width * srcHeight / srcWidth);
        } else {
            vw = (int) ((long) height * srcWidth / srcHeight);
        }
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport((width - vw) / 2, (height - vh) / 2, vw, vh);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, in.texture);
        quad.position(0);
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPosition);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTexCoord);
        GLES20.glUniformMatrix4fv(uSTMatrix, 1, false, in.matrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) throw new IOException("OpenGL error 0x" + Integer.toHexString(err));
    }

    void setPresentationTime(long ns) {
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, ns);
    }

    void swapBuffers() throws IOException {
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new IOException("eglSwapBuffers failed");
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (program != 0) GLES20.glDeleteProgram(program);
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
        }
        display = EGL14.EGL_NO_DISPLAY;
        context = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
        encoderInput.release();
    }

    private static int createProgram() throws IOException {
        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IOException("Shader link failed: " + log);
        }
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return p;
    }

    private static int compile(int type, String src) throws IOException {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new IOException("Shader compile failed: " + log);
        }
        return s;
    }

    /** A SurfaceTexture-backed Surface that a decoder renders into. */
    static final class Input implements SurfaceTexture.OnFrameAvailableListener {
        final int texture;
        final float[] matrix = new float[16];
        private final SurfaceTexture surfaceTexture;
        private final Surface surface;
        private final Object lock = new Object();
        private boolean frameAvailable;

        private Input(int texture, Handler handler) {
            this.texture = texture;
            surfaceTexture = new SurfaceTexture(texture);
            surfaceTexture.setOnFrameAvailableListener(this, handler);
            surface = new Surface(surfaceTexture);
        }

        Surface surface() {
            return surface;
        }

        @Override
        public void onFrameAvailable(SurfaceTexture st) {
            synchronized (lock) {
                frameAvailable = true;
                lock.notifyAll();
            }
        }

        /** Waits for the frame just released by the decoder and latches it into the texture. */
        void awaitFrame() throws IOException {
            synchronized (lock) {
                long deadline = System.currentTimeMillis() + 5000;
                while (!frameAvailable) {
                    long left = deadline - System.currentTimeMillis();
                    if (left <= 0) throw new IOException("Timed out waiting for a decoded frame");
                    try {
                        lock.wait(left);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted");
                    }
                }
                frameAvailable = false;
            }
            surfaceTexture.updateTexImage();
            surfaceTexture.getTransformMatrix(matrix);
        }

        void release() {
            surface.release();
            surfaceTexture.release();
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        }
    }
}
