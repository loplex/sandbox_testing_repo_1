package cz.loplex.dogvision.render

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface

/**
 * An OpenGL ES 3.0 context of its own, made current on the thread that makes it, drawing into
 * framebuffers of its own: its surface is a placeholder of 1 x 1 pixels.
 */
internal class OffscreenContext {
    private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val context: EGLContext
    private val surface: EGLSurface

    init {
        check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) { "Cannot initialise EGL" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val found = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, found, 0) && found[0] > 0) {
            "No EGL configuration for OpenGL ES 3.0"
        }
        val version = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, version, 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "Cannot make an OpenGL ES 3.0 context" }
        val size = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
        surface = EGL14.eglCreatePbufferSurface(display, configs[0], size, 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "Cannot make the context current" }
    }

    /** Releases the context, which must be current on this thread. */
    fun release() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(display, surface)
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
    }
}
