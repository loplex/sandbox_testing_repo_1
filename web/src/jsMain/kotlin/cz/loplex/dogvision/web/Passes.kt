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

/**
 * The page's rendering of a photo: gl's [ViewPasses], as the Android app runs them, over WebGL 2. The passes compile
 * when this is made, and throw IllegalStateException if the GPU's driver cannot compile or link one.
 *
 * A photo comes upright, as the browser decodes it, and is turned upright once all the same, as the app turns every
 * frame. It does not change while it is shown, so the share of pixels its map of differences marks is counted at once
 * instead of a frame later.
 */
internal class Passes(private val gl: WebGL2RenderingContext) {
    private val webGl = WebGl(gl)
    private val passes = ViewPasses(webGl).apply { create() }

    /** The photo as uploaded, before it is turned upright. */
    private val raw = texture(webGl)

    /** Makes [pixels], tightly packed RGBA of [width] x [height] with the first row first, the photo to render. */
    fun upload(width: Int, height: Int, pixels: Uint8Array) {
        webGl.bindTexture(GL_TEXTURE_2D, raw)
        val bytes = Int8Array(pixels.buffer, pixels.byteOffset, pixels.length).unsafeCast<ByteArray>()
        webGl.texImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, GL_RGBA, GL_UNSIGNED_BYTE, bytes)
        passes.turnUpright(raw, width, height, rotation = 0, mirrored = false)
    }

    /**
     * Renders every image of [view] of the photo, and returns the share of pixels its map of differences marks if it
     * shows one.
     */
    fun compose(view: View): Double? {
        passes.compose(view)
        return if (view.sideBySide && view.difference) passes.differenceShare() else null
    }

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
