package cz.loplex.dogvision.desktop

import org.lwjgl.egl.EGL10.EGL_NONE
import org.lwjgl.egl.EGL10.EGL_NO_CONTEXT
import org.lwjgl.egl.EGL10.EGL_NO_DISPLAY
import org.lwjgl.egl.EGL10.EGL_NO_SURFACE
import org.lwjgl.egl.EGL10.EGL_SURFACE_TYPE
import org.lwjgl.egl.EGL10.eglChooseConfig
import org.lwjgl.egl.EGL10.eglCreateContext
import org.lwjgl.egl.EGL10.eglDestroyContext
import org.lwjgl.egl.EGL10.eglGetError
import org.lwjgl.egl.EGL10.eglInitialize
import org.lwjgl.egl.EGL10.eglMakeCurrent
import org.lwjgl.egl.EGL10.eglTerminate
import org.lwjgl.egl.EGL12.EGL_OPENGL_ES_API
import org.lwjgl.egl.EGL12.EGL_RENDERABLE_TYPE
import org.lwjgl.egl.EGL12.eglBindAPI
import org.lwjgl.egl.EGL15.EGL_CONTEXT_MAJOR_VERSION
import org.lwjgl.egl.EGL15.EGL_CONTEXT_MINOR_VERSION
import org.lwjgl.egl.EGL15.EGL_OPENGL_ES3_BIT
import org.lwjgl.egl.EXTDeviceDRMRenderNode.EGL_DRM_RENDER_NODE_FILE_EXT
import org.lwjgl.egl.EXTDeviceEnumeration.eglQueryDevicesEXT
import org.lwjgl.egl.EXTDeviceQuery.eglQueryDeviceStringEXT
import org.lwjgl.egl.EXTPlatformBase.eglGetPlatformDisplayEXT
import org.lwjgl.egl.EXTPlatformDevice.EGL_PLATFORM_DEVICE_EXT
import org.lwjgl.opengles.GLES
import org.lwjgl.opengles.GLES20.GL_RENDERER
import org.lwjgl.opengles.GLES20.GL_VERSION
import org.lwjgl.opengles.GLES20.glGetString
import org.lwjgl.system.MemoryStack
import java.nio.IntBuffer

/**
 * An OpenGL ES 3.0 context with no window and no surface, current on the thread that makes it, on a GPU that EGL's
 * device platform names: GL draws into framebuffers of its own, which are read back to be shown.
 *
 * The device is chosen, the first with a DRM render node, which a GPU has and Mesa's software renderer has not; the
 * display EGL gives by default can be the software renderer's, as its GBM platform's is on a machine with a Radeon
 * 680M. Mesa's surfaceless platform would pick the GPU as well, but LWJGL 3.4.3 cannot ask for it: the platform takes
 * a null native display, which LWJGL's binding refuses. Where no device has a render node, the context is the first
 * device's, and [software] says so.
 *
 * Throws IllegalStateException where EGL, the platform or an ES 3 context is missing.
 */
class EglContext : AutoCloseable {
    private val display: Long
    private val context: Long

    /** What GL says it is running on, as `GL_RENDERER` and `GL_VERSION` name it. */
    val renderer: String
    val version: String

    init {
        display = eglGetPlatformDisplayEXT(EGL_PLATFORM_DEVICE_EXT, device(), null as IntBuffer?)
        check(display != EGL_NO_DISPLAY) { "EGL cannot open the device (error 0x${eglError()})" }
        MemoryStack.stackPush().use { stack ->
            val major = stack.mallocInt(1)
            val minor = stack.mallocInt(1)
            check(eglInitialize(display, major, minor)) { "Cannot initialise EGL (error 0x${eglError()})" }
            check(eglBindAPI(EGL_OPENGL_ES_API)) { "EGL has no OpenGL ES (error 0x${eglError()})" }
            // No surface of any kind is asked for, as none is drawn into.
            val attributes = stack.ints(EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, 0, EGL_NONE)
            val configs = stack.mallocPointer(1)
            val count = stack.mallocInt(1)
            check(eglChooseConfig(display, attributes, configs, count) && count[0] > 0) {
                "EGL has no configuration for OpenGL ES 3 (error 0x${eglError()})"
            }
            val version = stack.ints(EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE)
            context = eglCreateContext(display, configs[0], EGL_NO_CONTEXT, version)
            check(context != EGL_NO_CONTEXT) { "Cannot make an OpenGL ES 3.0 context (error 0x${eglError()})" }
        }
        check(eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, context)) {
            "Cannot make the OpenGL ES context current (error 0x${eglError()})"
        }
        GLES.createCapabilities()
        renderer = glGetString(GL_RENDERER).orEmpty()
        version = glGetString(GL_VERSION).orEmpty()
    }

    /** Whether GL renders in software, on the CPU, as Mesa's llvmpipe and softpipe do. */
    val software: Boolean get() = "llvmpipe" in renderer || "softpipe" in renderer

    override fun close() {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT)
        GLES.setCapabilities(null)
        eglDestroyContext(display, context)
        eglTerminate(display)
    }

    private fun eglError() = eglGetError().toString(16)

    /** The first EGL device with a DRM render node, else the first of all. */
    private fun device(): Long = MemoryStack.stackPush().use { stack ->
        val devices = stack.mallocPointer(MAX_DEVICES)
        val count = stack.mallocInt(1)
        check(eglQueryDevicesEXT(devices, count) && count[0] > 0) { "EGL finds no device (error 0x${eglError()})" }
        val all = List(count[0]) { devices[it] }
        all.firstOrNull { eglQueryDeviceStringEXT(it, EGL_DRM_RENDER_NODE_FILE_EXT) != null } ?: all.first()
    }

    private companion object {
        const val MAX_DEVICES = 16
    }
}
