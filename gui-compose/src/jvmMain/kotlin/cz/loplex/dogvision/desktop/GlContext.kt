package cz.loplex.dogvision.desktop

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
         * The context this system draws in, current on the calling thread. Throws IllegalStateException, or
         * UnsatisfiedLinkError where a library is missing, if none can be made.
         */
        fun open(): GlContext = EglContext.onDevice()
    }
}
