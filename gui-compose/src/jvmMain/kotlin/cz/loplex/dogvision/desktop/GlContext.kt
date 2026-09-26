package cz.loplex.dogvision.desktop

import cz.loplex.dogvision.cli.WindowsGl
import cz.loplex.dogvision.gl.Gl
import java.nio.ByteBuffer

/**
 * gl's [Gl] with what the window's [Passes] need besides: a frame uploaded from a direct buffer, a buffer mapped to be
 * read, and the framebuffer bound cleared.
 */
interface DesktopGl : Gl {
    /** Allocates the texture bound, filled from the direct buffer [pixels], from its position to its limit. */
    fun texImage2D(
        target: Int,
        level: Int,
        internalFormat: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: ByteBuffer,
    )

    /**
     * What [read] makes of the first [size] bytes of the buffer bound to [target], mapped for reading while it runs;
     * mapping it waits for the GPU to finish writing it.
     */
    fun <T> readMapped(target: Int, size: Int, read: (ByteBuffer) -> T): T

    /** Clears the framebuffer bound to transparent black. */
    fun clear()
}

/**
 * An OpenGL context with no window of its own to draw into, current on the thread that makes it, and [gl] to draw in
 * it: GL draws into framebuffers of its own, which are read back to be shown.
 */
interface GlContext : AutoCloseable {
    val gl: DesktopGl

    /** What GL says it is running on, as `GL_RENDERER` and `GL_VERSION` name it. */
    val renderer: String
    val version: String

    /** Whether GL renders in software, on the CPU. */
    val software: Boolean

    companion object {
        /**
         * The context this system draws in, current on the calling thread: on Windows, ANGLE's, and WGL's where
         * ANGLE's cannot be made, unless [windowsGl] names one of them; elsewhere, as on Linux, one on a GPU that
         * EGL's device platform names. Throws IllegalStateException, or UnsatisfiedLinkError where a library is
         * missing, if none can be made.
         */
        fun open(windowsGl: WindowsGl? = null): GlContext {
            if (!onWindows) {
                check(windowsGl == null) { "--gl chooses how to draw on Windows, and this is not Windows" }
                return EglContext.onDevice()
            }
            return when (windowsGl) {
                WindowsGl.ANGLE -> EglContext.angle()

                WindowsGl.WGL -> WglContext()

                null -> try {
                    EglContext.angle()
                } catch (angle: IllegalStateException) {
                    wgl(angle)
                } catch (angle: UnsatisfiedLinkError) {
                    wgl(angle)
                }
            }
        }

        /** WGL's context, where ANGLE's could not be made for the reason [angle] gives. */
        private fun wgl(angle: Throwable): GlContext {
            System.err.println("ANGLE cannot draw here, so WGL draws: ${angle.message}")
            return try {
                WglContext()
            } catch (wgl: IllegalStateException) {
                throw IllegalStateException("${angle.message}; ${wgl.message}", wgl)
            }
        }
    }
}
