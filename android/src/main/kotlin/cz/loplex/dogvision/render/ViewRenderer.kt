package cz.loplex.dogvision.render

import android.opengl.GLES30.GL_BLEND
import android.opengl.GLES30.GL_COLOR_BUFFER_BIT
import android.opengl.GLES30.GL_DEPTH_TEST
import android.opengl.GLES30.GL_FLOAT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_MAX_TEXTURE_SIZE
import android.opengl.GLES30.GL_R32F
import android.opengl.GLES30.GL_R8
import android.opengl.GLES30.GL_RED
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_RGBA8
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_TRIANGLES
import android.opengl.GLES30.GL_TRIANGLE_STRIP
import android.opengl.GLES30.GL_UNPACK_ALIGNMENT
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glClear
import android.opengl.GLES30.glClearColor
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glDrawArrays
import android.opengl.GLES30.glGetIntegerv
import android.opengl.GLES30.glPixelStorei
import android.opengl.GLES30.glReadPixels
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glUniform1f
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUniform2i
import android.opengl.GLES30.glUniform4f
import android.opengl.GLES30.glUniformMatrix3fv
import android.opengl.GLES30.glViewport
import android.opengl.GLSurfaceView
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Matrix
import cz.loplex.dogvision.core.ScreenLayout
import cz.loplex.dogvision.core.Srgb
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.gaussianKernel
import cz.loplex.dogvision.core.layOut
import cz.loplex.dogvision.core.meanLinearRgb
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.core.simulationOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp

/** What the renderer last drew: how the images were laid out, and the share of pixels that differ, if mapped. */
data class Drawn(val layout: ScreenLayout, val differenceShare: Double?)

/**
 * Draws the newest frame of [frames] as [view] asks, on the GL thread of a GLSurfaceView.
 *
 * The frame is turned upright, each image of the view is rendered into a texture of its own, the
 * size the frame has, and the images are drawn to the screen, scaled to the layout [layOut] gives.
 * Rendering the map of differences into a texture of its own keeps it from reading the texture it
 * draws into, which GL leaves undefined.
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

    private lateinit var upright: Program
    private lateinit var copy: Program
    private lateinit var colour: Program
    private lateinit var blurAcross: Program
    private lateinit var blurDown: Program
    private lateinit var difference: Program
    private lateinit var count: Program
    private lateinit var screen: Program
    private var decodeTable = 0
    private var encodeTable = 0
    private var raw = 0
    private val frame = Target()
    private val across = Target()
    /** The view's images, left to right or top to bottom; drawn to the screen scaled, so filtered. */
    private val images = List(3) { Target(filtered = true) }
    private val counts = Target()
    private var screenWidth = 0
    private var screenHeight = 0
    private var shown: Frame? = null
    private var lastDrawn: Drawn? = null

    /** The longest side a texture may have on this GPU. */
    var maxTextureSize = 0
        private set

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        (listOf(frame, across, counts) + images).forEach(Target::lose)
        shown = null
        upright = Program(Shaders.FULL_VIEWPORT, Shaders.UPRIGHT)
        copy = Program(Shaders.FULL_VIEWPORT, Shaders.COPY)
        colour = Program(Shaders.FULL_VIEWPORT, Shaders.COLOUR)
        blurAcross = Program(Shaders.FULL_VIEWPORT, Shaders.BLUR_ACROSS)
        blurDown = Program(Shaders.FULL_VIEWPORT, Shaders.BLUR_DOWN_AND_COLOUR)
        difference = Program(Shaders.FULL_VIEWPORT, Shaders.DIFFERENCE)
        count = Program(Shaders.FULL_VIEWPORT, Shaders.COUNT)
        screen = Program(Shaders.SCREEN_VERTEX, Shaders.SCREEN)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        decodeTable = texture()
        val decode = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder())
        Srgb.decode.forEach(decode::putFloat)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, 256, 1, 0, GL_RED, GL_FLOAT, decode.rewind())
        encodeTable = texture()
        val encode = ByteBuffer.allocateDirect(Srgb.ENCODE_STEPS)
        for (step in 0 until Srgb.ENCODE_STEPS) encode.put(Srgb.encode(step / (Srgb.ENCODE_STEPS - 1f)).toByte())
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, 64, 64, 0, GL_RED, GL_UNSIGNED_BYTE, encode.rewind())
        raw = texture()
        val max = IntArray(1)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, max, 0)
        maxTextureSize = max[0]
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_BLEND)
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
            turnUpright(newest)
        }
        val source = shown
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenWidth, screenHeight)
        glClearColor(0f, 0f, 0f, 1f)
        glClear(GL_COLOR_BUFFER_BIT)
        if (source == null) return
        val view = view
        val layout = layOut(screenWidth, screenHeight, frame.width, frame.height, view.images, captionHeight, gap)
        val share = compose(source, view)
        draw(layout)
        val drawn = Drawn(layout, share)
        if (drawn != lastDrawn) {
            lastDrawn = drawn
            onDrawn(drawn)
        }
    }

    private fun turnUpright(source: Frame) {
        frame.ensure(source.uprightWidth, source.uprightHeight)
        into(frame)
        upright.use()
        common(upright)
        upright.sampler("uRaw", 2, raw)
        glUniform1i(upright.uniform("uRotation"), source.rotation)
        glUniform1i(upright.uniform("uMirrored"), if (source.mirrored) 1 else 0)
        glUniform2i(upright.uniform("uSize"), frame.width, frame.height)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Renders every image of [view] into [images]; returns the share of pixels that differ, if mapped. */
    private fun compose(source: Frame, view: View): Double? {
        val width = frame.width
        val height = frame.height
        val mean by lazy { meanLinearRgb(width, height, source::uprightPixel) }
        val right = images[if (view.sideBySide) 1 else 0]
        val (rightMatrix, rightBlur) = simulationOf(width, view.params) { mean }
        simulate(rightMatrix, rightBlur, right)
        if (!view.sideBySide) return null
        val left = images[0]
        val compare = view.compare
        if (compare == null) {
            left.ensure(width, height)
            into(left)
            copy.use()
            common(copy)
            copy.sampler("uSource", 2, frame.texture)
            glDrawArrays(GL_TRIANGLES, 0, 3)
        } else {
            val (leftMatrix, leftBlur) = simulationOf(width, view.params.copy(species = compare)) { mean }
            simulate(leftMatrix, leftBlur, left)
        }
        if (!view.difference) return null
        val map = images[2]
        map.ensure(width, height)
        into(map)
        difference.use()
        common(difference)
        difference.sampler("uLeftImage", 2, left.texture)
        difference.sampler("uRightImage", 3, right.texture)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        return countDifferences(map)
    }

    /** Renders one simulation of the upright frame into [target]. */
    private fun simulate(matrix: Matrix, blur: Pair<Double, Double>?, target: Target) {
        val width = frame.width
        val height = frame.height
        target.ensure(width, height)
        if (blur == null) {
            into(target)
            colour.use()
            common(colour)
            colour.sampler("uSource", 2, frame.texture)
            matrix(colour, matrix)
            glDrawArrays(GL_TRIANGLES, 0, 3)
            return
        }
        across.ensure(width, height)
        into(across)
        blurAcross.use()
        common(blurAcross)
        blurAcross.sampler("uSource", 2, frame.texture)
        kernel(blurAcross, blur.first, width)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        into(target)
        blurDown.use()
        common(blurDown)
        blurDown.sampler("uSource", 2, across.texture)
        kernel(blurDown, blur.second, height)
        matrix(blurDown, matrix)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** How many pixels of [map] differ noticeably, as a share of all its pixels. */
    private fun countDifferences(map: Target): Double {
        val blocksAcross = (map.width + 7) / 8
        val blocksDown = (map.height + 7) / 8
        counts.ensure(blocksAcross, blocksDown)
        into(counts)
        count.use()
        common(count)
        count.sampler("uSource", 2, map.texture)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        val pixels = ByteBuffer.allocateDirect(blocksAcross * blocksDown * 4)
        glReadPixels(0, 0, blocksAcross, blocksDown, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        var noticeable = 0L
        for (i in 0 until blocksAcross * blocksDown) noticeable += pixels.get(i * 4).toInt() and 0xFF
        return noticeable.toDouble() / (map.width.toLong() * map.height)
    }

    /** Draws each image into its box of [layout] on the screen. */
    private fun draw(layout: ScreenLayout) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, screenWidth, screenHeight)
        screen.use()
        layout.images.forEachIndexed { i, box ->
            screen.sampler("uSource", 0, images[i].texture)
            glUniform4f(
                screen.uniform("uRect"),
                2f * box.left / screenWidth - 1,
                1 - 2f * box.bottom / screenHeight,
                2f * box.right / screenWidth - 1,
                1 - 2f * box.top / screenHeight,
            )
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /**
     * The images of the view drawn last, read back from the GPU, left to right or top to bottom;
     * call it on the GL thread. The map's alpha, which marked what COUNT counts, is made opaque.
     */
    fun readImages(): List<Image> {
        val view = view
        return images.take(view.images).map { target ->
            val pixels = ByteBuffer.allocateDirect(target.width * target.height * 4)
            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
            glReadPixels(0, 0, target.width, target.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            Image(target.width, target.height, IntArray(target.width * target.height) {
                val offset = it * 4
                rgb(
                    pixels.get(offset).toInt() and 0xFF,
                    pixels.get(offset + 1).toInt() and 0xFF,
                    pixels.get(offset + 2).toInt() and 0xFF,
                )
            })
        }
    }

    private fun into(target: Target) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
        glViewport(0, 0, target.width, target.height)
    }

    /** The uniforms every offscreen pass has: the sRGB tables. */
    private fun common(program: Program) {
        program.sampler("uDecode", 0, decodeTable)
        program.sampler("uEncode", 1, encodeTable)
    }

    private fun matrix(program: Program, matrix: Matrix) {
        val values = FloatArray(9) { matrix[it / 3, it % 3].toFloat() }
        glUniformMatrix3fv(program.uniform("uMatrix"), 1, true, values, 0)
    }

    private fun kernel(program: Program, sigma: Double, length: Int) {
        val radius = gaussianKernel(sigma).size / 2
        val sum = (-radius..radius).sumOf { exp(-it.toDouble() * it / (2 * sigma * sigma)) }
        glUniform1f(program.uniform("uSigma"), sigma.toFloat())
        glUniform1i(program.uniform("uRadius"), radius)
        glUniform1f(program.uniform("uNorm"), (1 / sum).toFloat())
        glUniform1i(program.uniform("uLength"), length)
    }
}
