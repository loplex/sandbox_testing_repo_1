package cz.loplex.dogvision.web

import org.khronos.webgl.WebGLObject
import org.khronos.webgl.WebGLRenderingContext

/**
 * The part of WebGL 2 the passes use beyond WebGL 1, which is all Kotlin's standard library declares: vertex array
 * objects, and the sized formats of textures with one channel.
 */
abstract external class WebGL2RenderingContext : WebGLRenderingContext {
    fun createVertexArray(): WebGLVertexArrayObject?

    fun bindVertexArray(array: WebGLVertexArrayObject?)

    fun deleteVertexArray(array: WebGLVertexArrayObject?)
}

external class WebGLVertexArrayObject : WebGLObject

/** WebGL 2's values of the GL enums of the same names. */
internal object Gl2 {
    const val RED = 0x1903
    const val R8 = 0x8229
    const val R32F = 0x822E
    const val RGBA8 = 0x8058
}
