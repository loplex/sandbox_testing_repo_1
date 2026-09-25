package cz.loplex.dogvision.render

import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_MAX_TEXTURE_SIZE
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_RGBA8
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glGetIntegerv
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glViewport
import android.opengl.GLSurfaceView
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.ScreenLayout
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.layOut
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/** Is handed the images of a frame drawn and the layout they were drawn in, on the GL thread. */
typealias OnImages = (List<Image>, ScreenLayout) -> Unit

/** Asks for the images of the next frame drawn. */
typealias Capture = (OnImages) -> Unit

/** What the renderer last drew: how the images were laid out, and the share of pixels that differ, if mapped. */
data class Drawn(val layout: ScreenLayout, val differenceShare: Double?)

/**
 * Draws the newest frame of [frames] as [view] asks, on the GL thread of a GLSurfaceView: the view's
 * images, rendered by [ViewPasses], scaled to the layout [layOut] gives.
 */
class ViewRenderer(private val frames: FrameExchange, private val onDrawn: (Drawn) -> Unit) : GLSurfaceView.Renderer {
    /** What to show; set from any thread, and followed by GLSurfaceView.requestRender(). */
    @Volatile
    var view = View()

    /** The height of a caption under each image, and the gap between images, in screen pixels. */
    @Volatile
    var captionHeight = 0

    @Volatile
    var gap = 0

    private val passes = ViewPasses()
    private var raw = 0
    private var screenWidth = 0
    private var screenHeight = 0
    private var shown: Frame? = null
    private var lastDrawn: Drawn? = null
    private val captures = ConcurrentLinkedQueue<OnImages>()

    /**
     * Hands [onImages] the images of the next frame drawn, and the layout they were drawn in, on the
     * GL thread; follow it with GLSurfaceView.requestRender(). It is not called before a first frame.
     */
    fun capture(onImages: OnImages) {
        captures.add(onImages)
    }

    /** The longest side a texture may have on this GPU. */
    var maxTextureSize = 0
        private set

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        passes.lose()
        shown = null
        passes.create()
        raw = texture()
        val max = IntArray(1)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, max, 0)
        maxTextureSize = max[0]
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
    }

    override fun onDrawFrame(unused: GL10?) {
        val newest = frames.take() ?: frames.current().takeIf { shown == null }
        if (newest != null) {
            glBindTexture(GL_TEXTURE_2D, raw)
            glTexImage2D(
                GL_TEXTURE_2D, 0, GL_RGBA8, newest.width, newest.height, 0, GL_RGBA, GL_UNSIGNED_BYTE,
                newest.pixels.rewind(),
            )
            shown = newest
            passes.turnUpright(raw, newest.width, newest.height, newest.rotation, newest.mirrored)
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenWidth, screenHeight)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (shown == null) return
        val view = view
        val upright = passes.frame
        val layout = layOut(screenWidth, screenHeight, upright.width, upright.height, view.images, captionHeight, gap)
        val share = passes.compose(view)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        passes.draw(layout.images, screenWidth, screenHeight)
        while (true) {
            val capture = captures.poll() ?: break
            capture(readImages(), layout)
        }
        val drawn = Drawn(layout, share)
        if (drawn != lastDrawn) {
            lastDrawn = drawn
            onDrawn(drawn)
        }
    }

    /**
     * The images of the view drawn last, read back from the GPU, left to right or top to bottom; call
     * it on the GL thread. The map's alpha, which marked what COUNT counts, is made opaque.
     */
    fun readImages(): List<Image> = passes.readImages(view.images)
}
