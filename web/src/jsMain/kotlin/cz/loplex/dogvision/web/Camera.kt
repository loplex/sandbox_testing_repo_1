package cz.loplex.dogvision.web

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.mediacapture.MediaStream

/**
 * A camera's live image, through getUserMedia, into a video element of its own that the page never shows. While it
 * runs, [onFrame] is handed the video and whether to mirror it for each new frame, as the browser renders the page;
 * [onEnded] is called if the browser ends the stream itself, as when the camera is unplugged.
 *
 * Every image is mirrored but a camera's that says it faces away from the viewer: a laptop's webcam often says nothing
 * of where it faces, and faces the viewer.
 */
internal class Camera(
    private val onFrame: (video: HTMLVideoElement, mirrored: Boolean) -> Unit,
    private val onEnded: () -> Unit,
) {
    private val video = (document.createElement("video") as HTMLVideoElement).apply {
        muted = true
        setAttribute("playsinline", "")
    }
    private var stream: MediaStream? = null
    private var mirrored = true

    /** Counts the streams asked for, so that what one started or stopped since hands over is ignored. */
    private var generation = 0

    /** Whether the front camera was asked for last. */
    var front = false
        private set

    /** Whether a stream runs. */
    val running: Boolean get() = stream != null

    /**
     * Stops the stream running, if any, and starts the front camera if [front], else the back one, where the device
     * tells them apart; [onStarted] is then handed how many cameras the browser knows of, or [onFailed] the name and
     * the message of the DOMException getUserMedia rejected with.
     */
    fun start(front: Boolean, onStarted: (cameras: Int) -> Unit, onFailed: (name: String, message: String) -> Unit) {
        stop()
        this.front = front
        val generation = generation
        val facing = if (front) "user" else "environment"
        val constraints = js("({ audio: false, video: { width: { ideal: 1280 }, height: { ideal: 720 } } })")
        // Asked for as the Android app asks for its frames, 1280 x 720, whichever camera it gets.
        constraints.video.facingMode = facing
        window.navigator.mediaDevices.getUserMedia(constraints).then(
            onFulfilled = { stream ->
                if (generation != this.generation) {
                    stream.getTracks().forEach { it.stop() }
                    return@then
                }
                this.stream = stream
                val track = stream.getVideoTracks().first()
                mirrored = track.getSettings().facingMode != "environment"
                track.addEventListener("ended", {
                    if (generation == this.generation) {
                        stop()
                        onEnded()
                    }
                })
                video.srcObject = stream
                // A play() cut short by the next stream rejects; that stream starts its own.
                video.play().catch { }
                awaitFrame(generation)
                window.navigator.mediaDevices.enumerateDevices().then({ devices ->
                    if (generation == this.generation) onStarted(devices.count { it.kind.toString() == "videoinput" })
                }, { if (generation == this.generation) onStarted(1) })
            },
            onRejected = { error ->
                if (generation == this.generation) onFailed(error.asDynamic().name as String, error.message.orEmpty())
            },
        )
    }

    /** Stops the stream running, if any, which turns the camera's light off. */
    fun stop() {
        generation++
        stream?.getTracks()?.forEach { it.stop() }
        stream = null
        video.srcObject = null
    }

    /**
     * Hands each new frame to [onFrame] while the stream of [generation] runs, as the browser renders the page. [shown]
     * is how many frames the video had presented when one was handed over last, where the browser cannot say when a
     * frame comes.
     */
    private fun awaitFrame(generation: Int, shown: Int = -1) {
        if (video.asDynamic().requestVideoFrameCallback != undefined) {
            video.asDynamic().requestVideoFrameCallback { _: Double, _: dynamic ->
                if (generation == this.generation) {
                    onFrame(video, mirrored)
                    awaitFrame(generation)
                }
            }
            return
        }
        // Checked once per frame of the page, which may come twice as often as the camera's. A live video's time runs
        // on with the clock, not frame by frame; its count of frames does, where the browser keeps one: Firefox 156
        // keeps it at 0, and each frame of the page is then handed over.
        window.requestAnimationFrame {
            if (generation == this.generation) {
                val frames = video.asDynamic().getVideoPlaybackQuality?.call(video)?.totalVideoFrames as Int? ?: 0
                if (frames == 0 || frames != shown) onFrame(video, mirrored)
                awaitFrame(generation, frames)
            }
        }
    }

    companion object {
        /** Whether the browser offers a camera to this page at all: it offers none outside a secure context. */
        val available: Boolean get() = window.navigator.asDynamic().mediaDevices?.getUserMedia != undefined
    }
}
