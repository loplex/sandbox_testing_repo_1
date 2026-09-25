package cz.loplex.dogvision.render

import android.opengl.GLES30.GL_CLAMP_TO_EDGE
import android.opengl.GLES30.GL_COLOR_ATTACHMENT0
import android.opengl.GLES30.GL_COMPILE_STATUS
import android.opengl.GLES30.GL_FRAGMENT_SHADER
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_FRAMEBUFFER_COMPLETE
import android.opengl.GLES30.GL_LINEAR
import android.opengl.GLES30.GL_LINK_STATUS
import android.opengl.GLES30.GL_NEAREST
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_RGBA8
import android.opengl.GLES30.GL_TEXTURE0
import android.opengl.GLES30.GL_TEXTURE_2D
import android.opengl.GLES30.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES30.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES30.GL_TEXTURE_WRAP_S
import android.opengl.GLES30.GL_TEXTURE_WRAP_T
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.GL_VERTEX_SHADER
import android.opengl.GLES30.glActiveTexture
import android.opengl.GLES30.glAttachShader
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glCheckFramebufferStatus
import android.opengl.GLES30.glCompileShader
import android.opengl.GLES30.glCreateProgram
import android.opengl.GLES30.glCreateShader
import android.opengl.GLES30.glDeleteFramebuffers
import android.opengl.GLES30.glDeleteProgram
import android.opengl.GLES30.glDeleteShader
import android.opengl.GLES30.glDeleteTextures
import android.opengl.GLES30.glFramebufferTexture2D
import android.opengl.GLES30.glGenFramebuffers
import android.opengl.GLES30.glGenTextures
import android.opengl.GLES30.glGetProgramInfoLog
import android.opengl.GLES30.glGetProgramiv
import android.opengl.GLES30.glGetShaderInfoLog
import android.opengl.GLES30.glGetShaderiv
import android.opengl.GLES30.glGetUniformLocation
import android.opengl.GLES30.glLinkProgram
import android.opengl.GLES30.glShaderSource
import android.opengl.GLES30.glTexImage2D
import android.opengl.GLES30.glTexParameteri
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUseProgram

/** A linked GL program, with its uniforms looked up by name once. */
internal class Program(vertex: String, fragment: String) {
    val id: Int = glCreateProgram()
    private val locations = mutableMapOf<String, Int>()

    init {
        val shaders = listOf(compile(GL_VERTEX_SHADER, vertex), compile(GL_FRAGMENT_SHADER, fragment))
        shaders.forEach { glAttachShader(id, it) }
        glLinkProgram(id)
        shaders.forEach(::glDeleteShader)
        val status = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { "Cannot link a program: ${glGetProgramInfoLog(id)}" }
    }

    fun use(): Program = apply { glUseProgram(id) }

    fun release() = glDeleteProgram(id)

    fun uniform(name: String): Int = locations.getOrPut(name) { glGetUniformLocation(id, name) }

    /** Binds [texture] to texture unit [unit] and points the sampler uniform [name] at it. */
    fun sampler(name: String, unit: Int, texture: Int) {
        glActiveTexture(GL_TEXTURE0 + unit)
        glBindTexture(GL_TEXTURE_2D, texture)
        glUniform1i(uniform(name), unit)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val status = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { "Cannot compile a shader: ${glGetShaderInfoLog(shader)}" }
        return shader
    }
}

/** A new texture, sampled at whole pixels unless [filtered]. */
internal fun texture(filtered: Boolean = false): Int {
    val id = IntArray(1)
    glGenTextures(1, id, 0)
    glBindTexture(GL_TEXTURE_2D, id[0])
    val filter = if (filtered) GL_LINEAR else GL_NEAREST
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    return id[0]
}

/** An RGBA8 texture with a framebuffer to render into it, reallocated when its size changes. */
internal class Target(private val filtered: Boolean = false) {
    var texture = 0
        private set
    var framebuffer = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(width: Int, height: Int) {
        if (width == this.width && height == this.height && texture != 0) return
        release()
        texture = texture(filtered)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, null)
        val id = IntArray(1)
        glGenFramebuffers(1, id, 0)
        framebuffer = id[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        check(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE) {
            "Cannot render into a $width x $height texture"
        }
        this.width = width
        this.height = height
    }

    fun release() {
        if (texture != 0) glDeleteTextures(1, intArrayOf(texture), 0)
        if (framebuffer != 0) glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        texture = 0
        framebuffer = 0
        width = 0
        height = 0
    }

    /** Forgets the GL objects without deleting them, as when their context is gone. */
    fun lose() {
        texture = 0
        framebuffer = 0
        width = 0
        height = 0
    }
}
