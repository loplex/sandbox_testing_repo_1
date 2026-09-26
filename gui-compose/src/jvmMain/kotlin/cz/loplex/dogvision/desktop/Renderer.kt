package cz.loplex.dogvision.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import cz.loplex.dogvision.core.ScreenLayout
import cz.loplex.dogvision.core.View
import cz.loplex.dogvision.core.layOut
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import org.jetbrains.skia.Image as SkiaImage

/** The images of [view] drawn into [layout]'s boxes on a transparent [bitmap] of the area, and the share it counted. */
class Picture(val bitmap: ImageBitmap, val layout: ScreenLayout, val view: View, val differenceShare: Double?)

/** The area the images are laid out on, and the height of a caption under each and the gap between them, in pixels. */
data class Area(val width: Int, val height: Int, val captionHeight: Int, val gap: Int)

/**
 * Renders the view of the frame shown last on a thread of its own, which holds an [EglContext] and [Passes], and hands
 * each picture to [onPicture] on that thread. [onFailure] is told, once, why nothing can be drawn, if GL cannot be set
 * up.
 *
 * It renders when something has changed: a frame, the view or the area; a live frame's share of differing pixels,
 * counted without waiting for the GPU as the Android app counts it, comes with a later picture.
 */
class Renderer(private val onPicture: (Picture) -> Unit, private val onFailure: (String) -> Unit) : AutoCloseable {
    private val lock = ReentrantLock()

    /** Signalled whenever [changed] or [closed] is set. */
    private val changes = lock.newCondition()

    /** The frame shown last, not uploaded yet, copied into a buffer the renderer's thread swaps with its own. */
    private var pending: ByteBuffer = ByteBuffer.allocateDirect(0)
    private var pendingWidth = 0
    private var pendingHeight = 0
    private var pendingLive = false
    private var framePending = false
    private var view: View? = null
    private var area: Area? = null
    private var changed = false
    private var closed = false

    private val thread = thread(name = "dog-vision-gl", isDaemon = true) { run() }

    /**
     * Makes [frame] the one to render, copying it, so that its buffer can be reused as soon as this returns; a [live]
     * frame is a video's or a camera's, followed by others, a frame that is not is a photo's.
     */
    fun show(frame: Frame, live: Boolean) = lock.withLock {
        val size = frame.width * frame.height * 4
        if (pending.capacity() < size) pending = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        pending.clear().put(frame.pixels.duplicate()).flip()
        pendingWidth = frame.width
        pendingHeight = frame.height
        pendingLive = live
        framePending = true
        changed = true
        changes.signalAll()
    }

    fun setView(view: View) = update { this.view = view }

    fun setArea(area: Area) = update { this.area = area }

    private inline fun update(change: () -> Unit) = lock.withLock {
        change()
        changed = true
        changes.signalAll()
    }

    override fun close() {
        lock.withLock {
            closed = true
            changes.signalAll()
        }
        thread.join(CLOSE_WAIT_MILLIS)
    }

    private fun run() {
        val context = try {
            EglContext()
        } catch (error: IllegalStateException) {
            onFailure(error.message.orEmpty())
            return
        } catch (error: UnsatisfiedLinkError) {
            onFailure(error.message.orEmpty())
            return
        }
        if (context.software) System.err.println("OpenGL ES renders on the CPU here: ${context.renderer}")
        try {
            val passes = try {
                Passes(LwjglGles())
            } catch (error: IllegalStateException) {
                onFailure(error.message.orEmpty())
                return
            }
            render(passes)
            passes.release()
        } finally {
            context.close()
        }
    }

    /** The loop that renders until [close]. */
    private fun render(passes: Passes) {
        var uploading: ByteBuffer = ByteBuffer.allocateDirect(0)
        var live = false
        var hasFrame = false
        var composed: View? = null
        var share: Double? = null
        var last: Picture? = null
        var drawnArea: Area? = null
        while (true) {
            var frameWidth = 0
            var frameHeight = 0
            val newFrame: Boolean
            val view: View?
            val area: Area?
            lock.withLock {
                while (!closed && !changed) {
                    // A count under way is asked for once a frame, and a live frame may be long in coming.
                    if (live && passes.counting) {
                        changes.await(COUNT_POLL_MILLIS, TimeUnit.MILLISECONDS)
                        break
                    }
                    changes.await()
                }
                if (closed) return
                changed = false
                newFrame = framePending
                if (newFrame) {
                    framePending = false
                    val swapped = uploading
                    uploading = pending
                    pending = swapped
                    frameWidth = pendingWidth
                    frameHeight = pendingHeight
                    live = pendingLive
                }
                view = this.view
                area = this.area
            }
            if (newFrame) {
                passes.upload(frameWidth, frameHeight, uploading)
                hasFrame = true
            }
            if (!hasFrame || view == null || area == null || area.width <= 0 || area.height <= 0) continue
            val counts = view.sideBySide && view.difference
            val recomposed = newFrame || view != composed
            if (recomposed) {
                if (live) {
                    passes.composeLive(view)
                    if (!counts) share = null
                } else {
                    share = passes.compose(view)
                }
                composed = view
            }
            var counted = false
            if (live && passes.counting) {
                passes.takeDifferenceShare()?.let {
                    share = it
                    counted = true
                }
            }
            val previous = last
            val picture = when {
                recomposed || area != drawnArea || previous == null -> draw(passes, view, area, share.takeIf { counts })
                counted -> Picture(previous.bitmap, previous.layout, view, share.takeIf { counts })
                else -> continue
            }
            drawnArea = area
            last = picture
            onPicture(picture)
        }
    }

    private fun draw(passes: Passes, view: View, area: Area, share: Double?): Picture {
        val layout = layOut(
            area.width,
            area.height,
            passes.frameWidth,
            passes.frameHeight,
            view.images,
            area.captionHeight,
            area.gap,
        )
        val pixels = ByteArray(area.width * area.height * 4)
        passes.draw(layout.images, area.width, area.height, pixels)
        val info = ImageInfo(area.width, area.height, ColorType.RGBA_8888, ColorAlphaType.PREMUL)
        val bitmap = SkiaImage.makeRaster(info, pixels, area.width * 4).toComposeImageBitmap()
        return Picture(bitmap, layout, view, share)
    }

    private companion object {
        const val COUNT_POLL_MILLIS = 4L
        const val CLOSE_WAIT_MILLIS = 2000L
    }
}
