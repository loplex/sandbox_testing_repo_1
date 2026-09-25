package cz.loplex.dogvision.video

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES
import android.opengl.GLES30.GL_CLAMP_TO_EDGE
import android.opengl.GLES30.GL_FRAMEBUFFER
import android.opengl.GLES30.GL_LINEAR
import android.opengl.GLES30.GL_RGBA
import android.opengl.GLES30.GL_TEXTURE0
import android.opengl.GLES30.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES30.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES30.GL_TEXTURE_WRAP_S
import android.opengl.GLES30.GL_TEXTURE_WRAP_T
import android.opengl.GLES30.GL_TRIANGLES
import android.opengl.GLES30.GL_UNSIGNED_BYTE
import android.opengl.GLES30.glActiveTexture
import android.opengl.GLES30.glBindFramebuffer
import android.opengl.GLES30.glBindTexture
import android.opengl.GLES30.glBindVertexArray
import android.opengl.GLES30.glDeleteTextures
import android.opengl.GLES30.glDeleteVertexArrays
import android.opengl.GLES30.glDrawArrays
import android.opengl.GLES30.glGenTextures
import android.opengl.GLES30.glGenVertexArrays
import android.opengl.GLES30.glReadPixels
import android.opengl.GLES30.glTexParameteri
import android.opengl.GLES30.glUniform1i
import android.opengl.GLES30.glUniform2f
import android.opengl.GLES30.glUniformMatrix4fv
import android.opengl.GLES30.glViewport
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import cz.loplex.dogvision.render.FrameExchange
import cz.loplex.dogvision.render.OffscreenContext
import cz.loplex.dogvision.render.Program
import cz.loplex.dogvision.render.Shaders
import cz.loplex.dogvision.render.Target
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The frames of the video at [uri], played at its own rate and over again from its start, without
 * its sound, handed to [frames] as RGBA no larger than [longestSide] while it plays.
 *
 * ExoPlayer decodes it into a SurfaceTexture, which a GL context on a thread of the feed's own
 * scales down and reads back, so that the renderer takes a video's frames as it takes the camera's.
 * The player is made and told what to do on the thread that makes the feed, which must have a Looper.
 */
class VideoFeed(
    context: Context,
    uri: Uri,
    private val frames: FrameExchange,
    private val longestSide: Int,
    private val onError: (Throwable) -> Unit,
) {
    private val generation = frames.open()
    private val thread = HandlerThread("VideoFeed").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.myLooper() ?: error("A VideoFeed is made on a thread with a Looper"))
    private val surfaceTexture = SurfaceTexture(false)
    private val surface = Surface(surfaceTexture)

    /** The video's size as shown, once the player knows it. */
    @Volatile
    private var size: VideoSize? = null

    private var gl: Gl? = null

    private val player = ExoPlayer.Builder(context).build().apply {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            .build()
        repeatMode = Player.REPEAT_MODE_ONE
        addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) size = videoSize
            }

            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
        setVideoSurface(surface)
        setMediaItem(MediaItem.fromUri(uri))
        prepare()
    }

    init {
        handler.post {
            try {
                gl = Gl()
            } catch (error: RuntimeException) {
                main.post { onError(error) }
            }
        }
        surfaceTexture.setOnFrameAvailableListener({ deliver() }, handler)
    }

    fun play() {
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Stops the video for good; the feed cannot be used after. */
    fun release() {
        player.release()
        handler.post {
            gl?.release()
            gl = null
            surface.release()
            surfaceTexture.release()
            thread.quitSafely()
        }
    }

    /**
     * On the feed's thread: the newest frame the player rendered, scaled down, to [frames]. It is
     * latched even when it is not delivered, which frees its buffer for the player's next.
     */
    private fun deliver() {
        val gl = gl ?: return
        try {
            gl.latch()
            val video = size ?: return
            val shownWidth = video.width * video.pixelWidthHeightRatio
            val scale = minOf(1f, longestSide / max(shownWidth, video.height.toFloat()))
            val width = max(1, (shownWidth * scale).roundToInt())
            val height = max(1, (video.height * scale).roundToInt())
            val frame = frames.obtain(width, height) ?: return
            gl.draw(width, height, taps = ceil(1 / scale).toInt())
            frame.pixels.clear()
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, frame.pixels)
            frame.rotation = 0
            frame.mirrored = false
            frames.publish(frame, generation)
        } catch (error: RuntimeException) {
            main.post { onError(error) }
        }
    }

    /** The GL objects of the feed's thread, with the SurfaceTexture attached to its context. */
    private inner class Gl {
        private val context = OffscreenContext()
        private val program = Program(Shaders.FULL_VIEWPORT, Shaders.VIDEO_FRAME)
        private val target = Target()
        private val external: Int
        private val vertexArray: Int
        private val transform = FloatArray(16)

        init {
            val id = IntArray(1)
            glGenTextures(1, id, 0)
            external = id[0]
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, external)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
            surfaceTexture.attachToGLContext(external)
            glGenVertexArrays(1, id, 0)
            vertexArray = id[0]
        }

        /** Makes the newest frame the player rendered the texture's. */
        fun latch() {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(transform)
        }

        /** Draws the frame latched scaled into the target, which is left bound for reading. */
        fun draw(width: Int, height: Int, taps: Int) {
            target.ensure(width, height)
            glBindFramebuffer(GL_FRAMEBUFFER, target.framebuffer)
            glViewport(0, 0, width, height)
            glBindVertexArray(vertexArray)
            program.use()
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_EXTERNAL_OES, external)
            glUniform1i(program.uniform("uVideo"), 0)
            glUniformMatrix4fv(program.uniform("uTransform"), 1, false, transform, 0)
            glUniform2f(program.uniform("uSize"), width.toFloat(), height.toFloat())
            glUniform1i(program.uniform("uTaps"), taps)
            glDrawArrays(GL_TRIANGLES, 0, 3)
        }

        fun release() {
            surfaceTexture.detachFromGLContext()
            target.release()
            program.release()
            glDeleteTextures(1, intArrayOf(external), 0)
            glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
            context.release()
        }
    }
}
