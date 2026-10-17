package cz.loplex.dogvision.web.media

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLCanvasElement
import org.w3c.files.Blob
import org.w3c.files.File
import org.w3c.files.FilePropertyBag
import kotlin.js.Promise
import kotlin.test.Test

/**
 * A video whose first frame loads while the page is hidden waits at it until played;
 * one whose first frame loads while the page is shown plays.\
 * Headless Chrome never hides the page, so the test tells the feed whether it is hidden.
 */
class VideoFeedTest {
    /** A feed and the frames it has handed over since it was ready. */
    private class Opened {
        lateinit var feed: VideoFeed
        var frames = 0
    }

    /** Records a canvas that changes at every frame for [millis], so that the video has frames to hand over. */
    private fun recordVideo(millis: Int, onDone: (File) -> Unit) {
        val canvas = document.createElement("canvas") as HTMLCanvasElement
        canvas.width = 64
        canvas.height = 32
        val context = canvas.getContext("2d") as CanvasRenderingContext2D
        var drawn = 0
        fun draw() {
            context.fillStyle = if (drawn++ % 2 == 0) "red" else "blue"
            context.fillRect(0.0, 0.0, 64.0, 32.0)
        }
        draw()
        val stream = canvas.asDynamic().captureStream(30)
        val recorder = js("new MediaRecorder(stream)")
        val chunks = mutableListOf<Blob>()
        recorder.ondataavailable = { event: dynamic -> chunks += event.data.unsafeCast<Blob>() }
        recorder.onstop = {
            onDone(File(chunks.toTypedArray(), "video.webm", FilePropertyBag(type = recorder.mimeType as String)))
        }
        recorder.start()
        val drawing = window.setInterval({ draw() }, 33)
        window.setTimeout({
            window.clearInterval(drawing)
            recorder.stop()
        }, millis)
    }

    /** Opens a recorded video as if the page were [hidden] then, and resolves 500 ms after it was ready. */
    private fun openVideo(hidden: Boolean): Promise<Opened> = Promise { resolve, reject ->
        recordVideo(1000) { file ->
            val opened = Opened()
            var ready = false
            opened.feed = VideoFeed(
                file,
                onFrame = { if (ready) opened.frames++ },
                onReady = {
                    ready = true
                    window.setTimeout({ resolve(opened) }, 500)
                },
                onFailed = { reject(AssertionError("the browser cannot play it: $it")) },
                hidden = { hidden },
            )
        }
    }

    private fun after(millis: Int): Promise<Unit> = Promise { resolve, _ ->
        window.setTimeout({ resolve(Unit) }, millis)
    }

    @Test
    fun aVideoOpenedWhileThePageIsShownPlaysOnceReady(): Promise<Unit> = openVideo(hidden = false).then { opened ->
        opened.feed.close()
        if (opened.frames == 0) throw AssertionError("no frame handed over in 500 ms after it was ready")
    }

    @Test
    fun aVideoOpenedWhileThePageIsHiddenWaitsUntilPlayed(): Promise<Unit> = openVideo(hidden = true).then { opened ->
        if (opened.frames != 0) {
            opened.feed.close()
            throw AssertionError("${opened.frames} frames handed over while the page was hidden")
        }
        // As the page plays it once it is shown.
        opened.feed.play()
        after(500).then {
            opened.feed.close()
            if (opened.frames == 0) throw AssertionError("no frame handed over in 500 ms after it was played")
        }
    }.then { }
}
