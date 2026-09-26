package cz.loplex.dogvision.web

import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.Matrix
import cz.loplex.dogvision.core.Srgb
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.gaussianKernel
import cz.loplex.dogvision.core.simulationOf
import cz.loplex.dogvision.gl.Shaders
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.WebGLFramebuffer
import org.khronos.webgl.WebGLProgram
import org.khronos.webgl.WebGLRenderingContext.Companion.BLEND
import org.khronos.webgl.WebGLRenderingContext.Companion.CLAMP_TO_EDGE
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_ATTACHMENT0
import org.khronos.webgl.WebGLRenderingContext.Companion.COLOR_BUFFER_BIT
import org.khronos.webgl.WebGLRenderingContext.Companion.COMPILE_STATUS
import org.khronos.webgl.WebGLRenderingContext.Companion.DEPTH_TEST
import org.khronos.webgl.WebGLRenderingContext.Companion.FLOAT
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAGMENT_SHADER
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER
import org.khronos.webgl.WebGLRenderingContext.Companion.FRAMEBUFFER_COMPLETE
import org.khronos.webgl.WebGLRenderingContext.Companion.LINEAR
import org.khronos.webgl.WebGLRenderingContext.Companion.LINK_STATUS
import org.khronos.webgl.WebGLRenderingContext.Companion.NEAREST
import org.khronos.webgl.WebGLRenderingContext.Companion.RGBA
import org.khronos.webgl.WebGLRenderingContext.Companion.SCISSOR_TEST
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE0
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_2D
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_MAG_FILTER
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_MIN_FILTER
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_WRAP_S
import org.khronos.webgl.WebGLRenderingContext.Companion.TEXTURE_WRAP_T
import org.khronos.webgl.WebGLRenderingContext.Companion.TRIANGLES
import org.khronos.webgl.WebGLRenderingContext.Companion.TRIANGLE_STRIP
import org.khronos.webgl.WebGLRenderingContext.Companion.UNPACK_ALIGNMENT
import org.khronos.webgl.WebGLRenderingContext.Companion.UNSIGNED_BYTE
import org.khronos.webgl.WebGLRenderingContext.Companion.VERTEX_SHADER
import org.khronos.webgl.WebGLTexture
import org.khronos.webgl.WebGLUniformLocation
import org.khronos.webgl.get
import org.khronos.webgl.set
import kotlin.math.exp

/**
 * The passes that render a view's images from a photo in WebGL 2, as the Android app's ViewPasses renders them in
 * OpenGL ES 3.0, from the same shaders: each image into a texture of its own, the size the photo has, which [draw] puts
 * into boxes of the canvas.
 *
 * The photo comes upright, as the browser decodes it, so there is no pass to turn it. A photo does not change while it
 * is shown, so the share of pixels its map of differences marks is read back at once instead of a frame later.
 */
internal class Passes(private val gl: WebGL2RenderingContext) {
    private val copy = Program(gl, Shaders.FULL_VIEWPORT, Shaders.COPY)
    private val colour = Program(gl, Shaders.FULL_VIEWPORT, Shaders.COLOUR)
    private val blurAcross = Program(gl, Shaders.FULL_VIEWPORT, Shaders.BLUR_ACROSS)
    private val blurDown = Program(gl, Shaders.FULL_VIEWPORT, Shaders.BLUR_DOWN_AND_COLOUR)
    private val difference = Program(gl, Shaders.FULL_VIEWPORT, Shaders.DIFFERENCE)
    private val count = Program(gl, Shaders.FULL_VIEWPORT, Shaders.COUNT)
    private val everyEighth = Program(gl, Shaders.FULL_VIEWPORT, Shaders.EVERY_EIGHTH)
    private val screen = Program(gl, Shaders.SCREEN_VERTEX, Shaders.SCREEN)
    private val decodeTable = texture(gl)
    private val encodeTable = texture(gl)
    private val vertexArray = gl.createVertexArray()

    /** The photo, 8-bit sRGB with its first row at t = 0. */
    private val photo = texture(gl)
    private var width = 0
    private var height = 0

    private val across = Target(gl)

    /** The view's images, left to right or top to bottom; drawn scaled, so filtered. */
    private val images = List(3) { Target(gl, filtered = true) }
    private val counts = Target(gl)
    private val samples = Target(gl)

    init {
        gl.pixelStorei(UNPACK_ALIGNMENT, 1)
        gl.bindTexture(TEXTURE_2D, decodeTable)
        val decode = Float32Array(Srgb.decode.toTypedArray())
        gl.texImage2D(TEXTURE_2D, 0, Gl2.R32F, 256, 1, 0, Gl2.RED, FLOAT, decode)
        gl.bindTexture(TEXTURE_2D, encodeTable)
        val encode = Uint8Array(Srgb.ENCODE_STEPS)
        for (step in 0 until Srgb.ENCODE_STEPS) {
            encode[step] = Srgb.encode(step / (Srgb.ENCODE_STEPS - 1f)).toByte()
        }
        gl.texImage2D(TEXTURE_2D, 0, Gl2.R8, 64, 64, 0, Gl2.RED, UNSIGNED_BYTE, encode)
    }

    /** Makes [pixels], tightly packed RGBA of [width] x [height] with the first row first, the photo to render. */
    fun upload(width: Int, height: Int, pixels: Uint8Array) {
        gl.bindTexture(TEXTURE_2D, photo)
        gl.texImage2D(TEXTURE_2D, 0, Gl2.RGBA8, width, height, 0, RGBA, UNSIGNED_BYTE, pixels)
        this.width = width
        this.height = height
    }

    /**
     * Renders every image of [view] of the photo, and returns the share of pixels its map of differences marks if it
     * shows one.
     */
    fun compose(view: View): Double? = drawing {
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
            copy.sampler("uSource", 2, photo)
            gl.drawArrays(TRIANGLES, 0, 3)
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
        gl.drawArrays(TRIANGLES, 0, 3)
        differenceShare(map)
    }

    /**
     * Clears the canvas, [width] x [height], and draws the first images composed into [boxes], one each, measured from
     * its top left corner.
     */
    fun draw(boxes: List<Box>, width: Int, height: Int) = drawing {
        gl.bindFramebuffer(FRAMEBUFFER, null)
        gl.viewport(0, 0, width, height)
        gl.clearColor(0f, 0f, 0f, 0f)
        gl.clear(COLOR_BUFFER_BIT)
        screen.use()
        boxes.forEachIndexed { i, box ->
            screen.sampler("uSource", 0, images[i].texture)
            gl.uniform4f(
                screen.uniform("uRect"),
                2f * box.left / width - 1,
                1 - 2f * box.bottom / height,
                2f * box.right / width - 1,
                1 - 2f * box.top / height,
            )
            gl.drawArrays(TRIANGLE_STRIP, 0, 4)
        }
    }

    /** Runs [passes] with the passes' own vertex array bound, and nothing blending or cutting what they draw. */
    private inline fun <T> drawing(passes: () -> T): T {
        gl.bindVertexArray(vertexArray)
        gl.disable(BLEND)
        gl.disable(DEPTH_TEST)
        gl.disable(SCISSOR_TEST)
        try {
            return passes()
        } finally {
            gl.bindVertexArray(null)
        }
    }

    /** Renders one simulation of the photo into [target]. */
    private fun simulate(matrix: Matrix, blur: Pair<Double, Double>?, target: Target) {
        target.ensure(width, height)
        if (blur == null) {
            into(target)
            colour.use()
            common(colour)
            colour.sampler("uSource", 2, photo)
            matrix(colour, matrix)
            gl.drawArrays(TRIANGLES, 0, 3)
            return
        }
        across.ensure(width, height)
        into(across)
        blurAcross.use()
        common(blurAcross)
        blurAcross.sampler("uSource", 2, photo)
        kernel(blurAcross, blur.first, width)
        gl.drawArrays(TRIANGLES, 0, 3)
        into(target)
        blurDown.use()
        common(blurDown)
        blurDown.sampler("uSource", 2, across.texture)
        kernel(blurDown, blur.second, height)
        matrix(blurDown, matrix)
        gl.drawArrays(TRIANGLES, 0, 3)
    }

    /**
     * The photo's mean linear RGB, from every 8th pixel of every 8th row as core's meanLinearRgb takes it, so that the
     * two agree exactly.
     */
    private fun meanLinearRgb(): DoubleArray {
        val across = (width + 7) / 8
        val down = (height + 7) / 8
        samples.ensure(across, down)
        into(samples)
        everyEighth.use()
        everyEighth.sampler("uSource", 2, photo)
        gl.drawArrays(TRIANGLES, 0, 3)
        val pixels = read(samples)
        val sum = DoubleArray(3)
        for (i in 0 until across * down) {
            for (channel in 0 until 3) sum[channel] += Srgb.decode[pixels[i * 4 + channel].toInt() and 0xFF]
        }
        return DoubleArray(3) { sum[it] / (across * down) }
    }

    /** The share of the pixels of [map] that differ noticeably, counted in blocks of 8 x 8. */
    private fun differenceShare(map: Target): Double {
        counts.ensure((map.width + 7) / 8, (map.height + 7) / 8)
        into(counts)
        count.use()
        common(count)
        count.sampler("uSource", 2, map.texture)
        gl.drawArrays(TRIANGLES, 0, 3)
        val pixels = read(counts)
        var noticeable = 0L
        for (i in 0 until counts.width * counts.height) noticeable += pixels[i * 4].toInt() and 0xFF
        return noticeable.toDouble() / (map.width.toLong() * map.height)
    }

    /** The pixels of [target], which must be the framebuffer bound. */
    private fun read(target: Target): Uint8Array {
        val pixels = Uint8Array(target.width * target.height * 4)
        gl.readPixels(0, 0, target.width, target.height, RGBA, UNSIGNED_BYTE, pixels)
        return pixels
    }

    private fun into(target: Target) {
        gl.bindFramebuffer(FRAMEBUFFER, target.framebuffer)
        gl.viewport(0, 0, target.width, target.height)
    }

    /** The uniforms every offscreen pass has: the sRGB tables. */
    private fun common(program: Program) {
        program.sampler("uDecode", 0, decodeTable)
        program.sampler("uEncode", 1, encodeTable)
    }

    private fun matrix(program: Program, matrix: Matrix) {
        val values = Float32Array(Array(9) { matrix[it / 3, it % 3].toFloat() })
        gl.uniformMatrix3fv(program.uniform("uMatrix"), true, values)
    }

    private fun kernel(program: Program, sigma: Double, length: Int) {
        val radius = gaussianKernel(sigma).size / 2
        val sum = (-radius..radius).sumOf { exp(-it.toDouble() * it / (2 * sigma * sigma)) }
        gl.uniform1f(program.uniform("uSigma"), sigma.toFloat())
        gl.uniform1i(program.uniform("uRadius"), radius)
        gl.uniform1f(program.uniform("uNorm"), (1 / sum).toFloat())
        gl.uniform1i(program.uniform("uLength"), length)
    }
}

/** A linked program, with its uniforms looked up by name once. */
internal class Program(private val gl: WebGL2RenderingContext, vertex: String, fragment: String) {
    private val id: WebGLProgram = checkNotNull(gl.createProgram()) { "Cannot create a program" }
    private val locations = mutableMapOf<String, WebGLUniformLocation?>()

    init {
        val shaders = listOf(compile(VERTEX_SHADER, vertex), compile(FRAGMENT_SHADER, fragment))
        shaders.forEach { gl.attachShader(id, it) }
        gl.linkProgram(id)
        shaders.forEach(gl::deleteShader)
        check(gl.getProgramParameter(id, LINK_STATUS) == true) { "Cannot link a program: ${gl.getProgramInfoLog(id)}" }
    }

    fun use(): Program = apply { gl.useProgram(id) }

    fun uniform(name: String): WebGLUniformLocation? = locations.getOrPut(name) { gl.getUniformLocation(id, name) }

    /** Binds [texture] to texture unit [unit] and points the sampler uniform [name] at it. */
    fun sampler(name: String, unit: Int, texture: WebGLTexture?) {
        gl.activeTexture(TEXTURE0 + unit)
        gl.bindTexture(TEXTURE_2D, texture)
        gl.uniform1i(uniform(name), unit)
    }

    private fun compile(type: Int, source: String) = checkNotNull(gl.createShader(type)).also { shader ->
        gl.shaderSource(shader, source)
        gl.compileShader(shader)
        check(gl.getShaderParameter(shader, COMPILE_STATUS) == true) {
            "Cannot compile a shader: ${gl.getShaderInfoLog(shader)}"
        }
    }
}

/** A new texture, sampled at whole pixels unless [filtered]. */
internal fun texture(gl: WebGL2RenderingContext, filtered: Boolean = false): WebGLTexture? = gl.createTexture().also {
    gl.bindTexture(TEXTURE_2D, it)
    val filter = if (filtered) LINEAR else NEAREST
    gl.texParameteri(TEXTURE_2D, TEXTURE_MIN_FILTER, filter)
    gl.texParameteri(TEXTURE_2D, TEXTURE_MAG_FILTER, filter)
    gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_S, CLAMP_TO_EDGE)
    gl.texParameteri(TEXTURE_2D, TEXTURE_WRAP_T, CLAMP_TO_EDGE)
}

/** An RGBA8 texture with a framebuffer to render into it, reallocated when its size changes. */
internal class Target(private val gl: WebGL2RenderingContext, private val filtered: Boolean = false) {
    var texture: WebGLTexture? = null
        private set
    var framebuffer: WebGLFramebuffer? = null
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(width: Int, height: Int) {
        if (width == this.width && height == this.height && texture != null) return
        gl.deleteTexture(texture)
        gl.deleteFramebuffer(framebuffer)
        texture = texture(gl, filtered)
        gl.texImage2D(TEXTURE_2D, 0, Gl2.RGBA8, width, height, 0, RGBA, UNSIGNED_BYTE, null)
        framebuffer = gl.createFramebuffer()
        gl.bindFramebuffer(FRAMEBUFFER, framebuffer)
        gl.framebufferTexture2D(FRAMEBUFFER, COLOR_ATTACHMENT0, TEXTURE_2D, texture, 0)
        check(gl.checkFramebufferStatus(FRAMEBUFFER) == FRAMEBUFFER_COMPLETE) {
            "Cannot render into a $width x $height texture"
        }
        this.width = width
        this.height = height
    }
}
