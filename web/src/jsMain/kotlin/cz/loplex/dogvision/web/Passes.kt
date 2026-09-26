package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.gl.GL_RGBA
import cz.loplex.dogvision.gl.GL_RGBA8
import cz.loplex.dogvision.gl.GL_TEXTURE_2D
import cz.loplex.dogvision.gl.GL_UNSIGNED_BYTE
import cz.loplex.dogvision.gl.ViewPasses
import cz.loplex.dogvision.gl.texture
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_BUFFER_BIT
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER
import org.w3c.dom.HTMLVideoElement

/**
 * The page's rendering of a photo or a camera's frames: gl's [ViewPasses], as the Android app runs them, over WebGL 2.
 * The passes compile when this is made, and throw IllegalStateException if the GPU's driver cannot compile or link one.
 *
 * A photo comes upright, as the browser decodes it, and is turned upright once all the same, as the app turns every
 * frame. It does not change while it is shown, so the share of pixels its map of differences marks is counted at once.
 * A camera's frame comes upright too, the browser having turned it as the display is, and is mirrored if need be; its
 * share is counted without waiting for the GPU, and handed over a frame or two later, as in the app.
 */
internal class Passes(private val gl: WebGL2RenderingContext) {
    private val webGl = WebGl(gl)
    private val passes = ViewPasses(webGl).apply { create() }

    /** The photo or the camera's frame as uploaded, before it is turned upright. */
    private val raw = texture(webGl)

    /** Makes [pixels], tightly packed RGBA of [width] x [height] with the first row first, the photo to render. */
    fun upload(width: Int, height: Int, pixels: Uint8Array) {
        webGl.bindTexture(GL_TEXTURE_2D, raw)
        val bytes = Int8Array(pixels.buffer, pixels.byteOffset, pixels.length).unsafeCast<ByteArray>()
        webGl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        passes.turnUpright(raw, width, height, rotation = 0, mirrored = false)
    }

    /**
     * Makes the frame [video] shows the one to render, mirrored if [mirrored]; false, with nothing changed, while the
     * video has no frame yet.
     */
    fun upload(video: HTMLVideoElement, mirrored: Boolean): Boolean {
        val width = video.videoWidth
        val height = video.videoHeight
        if (width == 0 || height == 0) return false
        webGl.bindTexture(GL_TEXTURE_2D, raw)
        gl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, video)
        passes.turnUpright(raw, width, height, rotation = 0, mirrored = mirrored)
        return true
    }

    /** The size of the frame uploaded last, upright. */
    val frameWidth: Int get() = passes.frame.width
    val frameHeight: Int get() = passes.frame.height

    /**
     * Renders every image of [view] of the photo, and returns the share of pixels its map of differences marks if it
     * shows one.
     */
    fun compose(view: View): Double? {
        passes.compose(view)
        return if (view.sideBySide && view.difference) passes.differenceShare() else null
    }

    /**
     * Renders every image of [view] of a camera's frame, and asks for the share of pixels its map of differences marks
     * if it shows one, which [takeDifferenceShare] hands over.
     */
    fun composeLive(view: View) {
        passes.compose(view)
        if (view.sideBySide && view.difference) passes.countDifferences()
    }

    /** Whether a share asked for by [composeLive] has not been handed over yet. */
    val counting: Boolean get() = passes.counting

    /** The share asked for by [composeLive] once the GPU has counted it, else null; call it once a frame. */
    fun takeDifferenceShare(): Double? = passes.takeDifferenceShare()

    /**
     * Clears the canvas, [width] x [height], and draws the first images composed into [boxes], one each, measured from
     * its top left corner.
     */
    fun draw(boxes: List<Box>, width: Int, height: Int) {
        gl.bindFramebuffer(FRAMEBUFFER, null)
        gl.viewport(0, 0, width, height)
        gl.clearColor(0f, 0f, 0f, 0f)
        gl.clear(COLOR_BUFFER_BIT)
        passes.draw(boxes, width, height)
    }

    /** The first [count] images composed, read back from the GPU, left to right or top to bottom. */
    fun readImages(count: Int): List<Image> = passes.readImages(count)
}
