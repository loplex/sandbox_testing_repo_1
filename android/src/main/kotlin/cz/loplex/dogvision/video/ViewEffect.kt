package cz.loplex.dogvision.video

import android.content.Context
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_FRAMEBUFFER_BINDING
import android.opengl.GLES30.GL_VERSION
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glGetIntegerv
import android.opengl.GLES30.glGetString
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import cz.loplex.dogvision.core.Arrangement
import cz.loplex.dogvision.core.Box
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.composedSize
import cz.loplex.dogvision.render.ViewPasses

/**
 * A Media3 effect that turns each frame of a video into [view] of it, its images side by side or
 * one above another as the view's arrangement says: the frames a conversion writes.
 *
 * It renders with the passes the screen renders with, which need OpenGL ES 3.0, and takes 8-bit SDR
 * frames only, as a conversion that tone-maps HDR to SDR hands them over. Media3 keeps a frame's
 * first row at the top of its texture, t = 1, where the passes keep it at t = 0, so the frame is
 * flipped on the way in, and [ViewPasses.draw] flips the images back on the way out.
 */
@OptIn(UnstableApi::class)
class ViewEffect(private val view: View) : GlEffect {
    /** The size of the frames it renders, once it has been told the size of the frames it takes. */
    @Volatile
    var renderedSize: Pair<Int, Int>? = null
        private set

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("The view is rendered from SDR frames only")
        return Program()
    }

    private inner class Program : BaseGlShaderProgram(false, 1) {
        private var passes: ViewPasses? = null
        private var inputWidth = 0
        private var inputHeight = 0
        private var boxes = emptyList<Box>()
        private var outputWidth = 0
        private var outputHeight = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            if (passes == null) {
                val version = glGetString(GL_VERSION).orEmpty()
                if (!version.startsWith("OpenGL ES 3")) {
                    throw VideoFrameProcessingException("The view needs OpenGL ES 3.0, and the context is $version")
                }
                passes = ViewPasses().apply { create() }
            }
            this.inputWidth = inputWidth
            this.inputHeight = inputHeight
            val (width, height) = composedSize(view, inputWidth, inputHeight)
            outputWidth = width
            outputHeight = height
            renderedSize = width to height
            boxes = List(view.images) { i ->
                if (view.arrangement == Arrangement.ROW) {
                    Box(i * inputWidth, 0, inputWidth, inputHeight)
                } else {
                    Box(0, i * inputHeight, inputWidth, inputHeight)
                }
            }
            return Size(width, height)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val passes = checkNotNull(passes)
            val output = IntArray(1)
            glGetIntegerv(GL_FRAMEBUFFER_BINDING, output, 0)
            // Half a turn and a mirror are a flip from top to bottom.
            passes.turnUpright(inputTexId, inputWidth, inputHeight, rotation = 180, mirrored = true)
            passes.compose(view)
            glBindFramebuffer(GL_FRAMEBUFFER, output[0])
            passes.draw(boxes, outputWidth, outputHeight)
        }

        override fun release() {
            super.release()
            passes?.release()
            passes = null
        }
    }
}
