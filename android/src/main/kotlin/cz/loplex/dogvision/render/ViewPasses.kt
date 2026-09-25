package cz.loplex.dogvision.render

import android.opengl.GLES30.GL_BLEND
import android.opengl.GLES30.GL_DEPTH_TEST
import android.opengl.GLES30.GL_FLOAT
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_R32F
import android.opengl.GLES30.GL_R8
import android.opengl.GLES30.GL_RED
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_SCISSOR_TEST
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_TRIANGLES
import android.opengl.GLES30.GL_TRIANGLE_STRIP
import android.opengl.GLES30.GL_UNPACK_ALIGNMENT
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindVertexArray
import android.opengl.GLES30.glDeleteTextures
import android.opengl.GLES30.glDeleteVertexArrays
import android.opengl.GLES30.glDisable
import android.opengl.GLES30.glDrawArrays
import android.opengl.GLES30.glGenVertexArrays
import android.opengl.GLES30.glPixelStorei
import android.opengl.GLES30.glReadPixels
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glUniform1f
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUniform2i
import android.opengl.GLES30.glUniform4f
import android.opengl.GLES30.glUniformMatrix3fv
import android.opengl.GLES30.glViewport
import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.Image
import cz.loplex.dogvision.core.Matrix
import cz.loplex.dogvision.core.Srgb
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.gaussianKernel
import cz.loplex.dogvision.core.rgb
import cz.loplex.dogvision.core.simulationOf
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * The GL passes that render a view's images from a frame, in whichever context draws them: the
 * screen's, or that of a video being converted. Everything here runs on that context's thread,
 * between [create] and [release].
 *
 * The frame is turned upright into a texture of its own, each image of the view is rendered into a
 * texture of its own, the size the upright frame has, and [draw] puts the images into boxes of the
 * framebuffer bound. Rendering the map of differences into a texture of its own keeps it from reading
 * the texture it draws into, which GL leaves undefined.
 *
 * The passes bind a vertex array of their own while they draw and unbind it after, so that a context
 * they share with code drawing from client-side vertex arrays, as Media3's does, is left as it was.
 */
internal class ViewPasses {
    private lateinit var upright: Program
    private lateinit var copy: Program
    private lateinit var colour: Program
    private lateinit var blurAcross: Program
    private lateinit var blurDown: Program
    private lateinit var difference: Program
    private lateinit var count: Program
    private lateinit var everyEighth: Program
    private lateinit var screen: Program
    private var decodeTable = 0
    private var encodeTable = 0
    private var vertexArray = 0

    /** The frame, upright. */
    val frame = Target()
    private val across = Target()

    /** The view's images, left to right or top to bottom; drawn scaled, so filtered. */
    private val images = List(3) { Target(filtered = true) }
    private val counts = Target()
    private val samples = Target()

    /** Compiles the programs and makes the lookup tables, in the context current on this thread. */
    fun create() {
        upright = Program(Shaders.FULL_VIEWPORT, Shaders.UPRIGHT)
        copy = Program(Shaders.FULL_VIEWPORT, Shaders.COPY)
        colour = Program(Shaders.FULL_VIEWPORT, Shaders.COLOUR)
        blurAcross = Program(Shaders.FULL_VIEWPORT, Shaders.BLUR_ACROSS)
        blurDown = Program(Shaders.FULL_VIEWPORT, Shaders.BLUR_DOWN_AND_COLOUR)
        difference = Program(Shaders.FULL_VIEWPORT, Shaders.DIFFERENCE)
        count = Program(Shaders.FULL_VIEWPORT, Shaders.COUNT)
        everyEighth = Program(Shaders.FULL_VIEWPORT, Shaders.EVERY_EIGHTH)
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
        val id = IntArray(1)
        glGenVertexArrays(1, id, 0)
        vertexArray = id[0]
    }

    private val targets: List<Target> get() = listOf(frame, across, counts, samples) + images

    /** Forgets the GL objects without deleting them, as when their context is gone. */
    fun lose() = targets.forEach(Target::lose)

    /** Deletes the GL objects, in the context they were made in. */
    fun release() {
        targets.forEach(Target::release)
        listOf(upright, copy, colour, blurAcross, blurDown, difference, count, everyEighth, screen)
            .forEach(Program::release)
        glDeleteTextures(2, intArrayOf(decodeTable, encodeTable), 0)
        glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
    }

    /**
     * Turns the frame in [texture], [width] x [height], upright into [frame]: [rotation] degrees
     * clockwise, then mirrored if [mirrored] says so.
     */
    fun turnUpright(texture: Int, width: Int, height: Int, rotation: Int, mirrored: Boolean) = drawing {
        if (rotation % 180 == 0) frame.ensure(width, height) else frame.ensure(height, width)
        into(frame)
        upright.use()
        common(upright)
        upright.sampler("uRaw", 2, texture)
        glUniform1i(upright.uniform("uRotation"), rotation)
        glUniform1i(upright.uniform("uMirrored"), if (mirrored) 1 else 0)
        glUniform2i(upright.uniform("uSize"), frame.width, frame.height)
        glDrawArrays(GL_TRIANGLES, 0, 3)
    }

    /** Renders every image of [view] of the upright frame; returns the share of pixels that differ, if mapped. */
    fun compose(view: View): Double? = drawing {
        val width = frame.width
        val height = frame.height
        val mean by lazy(::meanLinearRgb)
        val right = images[if (view.sideBySide) 1 else 0]
        val (rightMatrix, rightBlur) = simulationOf(width, view.params) { mean }
        simulate(rightMatrix, rightBlur, right)
        if (!view.sideBySide) return@drawing null
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
        if (!view.difference) return@drawing null
        val map = images[2]
        map.ensure(width, height)
        into(map)
        difference.use()
        common(difference)
        difference.sampler("uLeftImage", 2, left.texture)
        difference.sampler("uRightImage", 3, right.texture)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        countDifferences(map)
    }

    /**
     * Draws the first images composed into [boxes], one each, of the framebuffer bound, [width] x
     * [height], measured from its top left corner as the top of an image is seen.
     */
    fun draw(boxes: List<Box>, width: Int, height: Int) = drawing {
        glViewport(0, 0, width, height)
        screen.use()
        boxes.forEachIndexed { i, box ->
            screen.sampler("uSource", 0, images[i].texture)
            glUniform4f(
                screen.uniform("uRect"),
                2f * box.left / width - 1,
                1 - 2f * box.bottom / height,
                2f * box.right / width - 1,
                1 - 2f * box.top / height,
            )
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    /**
     * The first [count] images composed, read back from the GPU, left to right or top to bottom. The
     * map's alpha, which marked what COUNT counts, is made opaque.
     */
    fun readImages(count: Int): List<Image> = images.take(count).map { target ->
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

    /** Runs [passes] with the passes' own vertex array bound, and nothing blending or cutting what they draw. */
    private inline fun <T> drawing(passes: () -> T): T {
        glBindVertexArray(vertexArray)
        glDisable(GL_BLEND)
        glDisable(GL_DEPTH_TEST)
        glDisable(GL_SCISSOR_TEST)
        try {
            return passes()
        } finally {
            glBindVertexArray(0)
        }
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

    /**
     * The upright frame's mean linear RGB, from every 8th pixel of every 8th row as core's
     * meanLinearRgb takes it, so that the two agree exactly.
     */
    private fun meanLinearRgb(): DoubleArray {
        val across = (frame.width + 7) / 8
        val down = (frame.height + 7) / 8
        samples.ensure(across, down)
        into(samples)
        everyEighth.use()
        everyEighth.sampler("uSource", 2, frame.texture)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        val pixels = read(samples)
        val sum = DoubleArray(3)
        for (i in 0 until across * down) {
            for (channel in 0 until 3) sum[channel] += Srgb.decode[pixels.get(i * 4 + channel).toInt() and 0xFF]
        }
        return DoubleArray(3) { sum[it] / (across * down) }
    }

    /** How many pixels of [map] differ noticeably, as a share of all its pixels. */
    private fun countDifferences(map: Target): Double {
        val across = (map.width + 7) / 8
        val down = (map.height + 7) / 8
        counts.ensure(across, down)
        into(counts)
        count.use()
        common(count)
        count.sampler("uSource", 2, map.texture)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        val pixels = read(counts)
        var noticeable = 0L
        for (i in 0 until across * down) noticeable += pixels.get(i * 4).toInt() and 0xFF
        return noticeable.toDouble() / (map.width.toLong() * map.height)
    }

    /** The pixels of [target], which must be the framebuffer bound. */
    private fun read(target: Target): ByteBuffer {
        val pixels = ByteBuffer.allocateDirect(target.width * target.height * 4)
        glReadPixels(0, 0, target.width, target.height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
        return pixels
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
