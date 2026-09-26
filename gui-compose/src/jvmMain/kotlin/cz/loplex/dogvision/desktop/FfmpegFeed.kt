package cz.loplex.dogvision.desktop

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Frames that the system's ffmpeg decodes, as raw RGBA through a pipe, [width] x [height] with the top row first,
 * handed to [onFrame] on a thread of its own as they come; the buffer is reused for the next frame once [onFrame]
 * returns. [onEnd] is told why the frames stopped, unless [close] stopped them.
 *
 * ffmpeg decodes, turns a video upright as its file says, and scales the frames down to [PREVIEW_LONGEST_SIDE], so
 * that the frames arrive as the passes take them.
 */
class FfmpegFeed private constructor(
    command: List<String>,
    val width: Int,
    val height: Int,
    private val onFrame: (Frame) -> Unit,
    private val onEnd: (String) -> Unit,
) : AutoCloseable {
    private val process = start(command)

    @Volatile
    private var closed = false

    /** The last lines ffmpeg wrote to its standard error, which say why it stopped. */
    private val errors = ArrayDeque<String>()

    private val errorReader = thread(name = "ffmpeg-errors", isDaemon = true) {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(errors) {
                    errors.addLast(line)
                    if (errors.size > KEPT_ERROR_LINES) errors.removeFirst()
                }
            }
        } catch (_: IOException) {
            // The stream is closed as the process is stopped.
        }
    }

    private val reader = thread(name = "ffmpeg-frames", isDaemon = true) { read() }

    private fun read() {
        val channel = Channels.newChannel(process.inputStream)
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        try {
            while (true) {
                buffer.clear()
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return end()
                }
                onFrame(Frame(width, height, buffer.flip()))
            }
        } catch (error: IOException) {
            if (!closed) onEnd(error.message.orEmpty())
        }
    }

    private fun end() {
        val status = process.waitFor()
        errorReader.join(END_WAIT_MILLIS)
        if (closed) return
        val said = synchronized(errors) { errors.lastOrNull() }
        onEnd(said ?: "ffmpeg ended with status $status")
    }

    override fun close() {
        closed = true
        process.destroy()
        if (!process.waitFor(END_WAIT_MILLIS, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        reader.join(END_WAIT_MILLIS)
    }

    companion object {
        private const val KEPT_ERROR_LINES = 20
        private const val END_WAIT_MILLIS = 2000L

        /** The size a camera is asked for, as the Android app's and the web page's are. */
        private const val CAMERA_SIZE = "1280x720"

        /**
         * The camera /dev/video[index], asked for Motion-JPEG at 1280 x 720, which a USB webcam gives at 30 frames a
         * second where its raw frames come at 10, and else for whatever it gives. Throws IOException if ffmpeg cannot
         * open it.
         */
        fun camera(index: Int, onFrame: (Frame) -> Unit, onEnd: (String) -> Unit): FfmpegFeed {
            val device = "/dev/video$index"
            val preferred = listOf("-f", "v4l2", "-input_format", "mjpeg", "-video_size", CAMERA_SIZE)
            val any = listOf("-f", "v4l2")
            val (options, size) = try {
                preferred to probe(preferred, device)
            } catch (_: IOException) {
                any to probe(any, device)
            }
            return start(options + listOf("-i", device), size, onFrame, onEnd)
        }

        /** The video in [file], played over and over at its own speed, without its sound. */
        fun video(file: File, onFrame: (Frame) -> Unit, onEnd: (String) -> Unit): FfmpegFeed {
            val size = probe(emptyList(), file.path)
            return start(listOf("-re", "-stream_loop", "-1", "-i", file.path), size, onFrame, onEnd)
        }

        private fun start(
            input: List<String>,
            upright: Pair<Int, Int>,
            onFrame: (Frame) -> Unit,
            onEnd: (String) -> Unit,
        ): FfmpegFeed {
            val (width, height) = fitted(upright.first, upright.second)
            val output = listOf("-an", "-sn", "-vf", "scale=$width:$height:flags=area", "-pix_fmt", "rgba")
            val command = listOf("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin") + input + output +
                listOf("-f", "rawvideo", "-")
            return FfmpegFeed(command, width, height, onFrame, onEnd)
        }

        /**
         * The upright size of the first video stream of [input] opened with [options], as ffprobe gives it: a size
         * turned a quarter by the stream's rotation is swapped, as ffmpeg turns its frames. Throws IOException with
         * what ffprobe says if it cannot read one.
         */
        internal fun probe(options: List<String>, input: String): Pair<Int, Int> {
            val command = listOf("ffprobe", "-v", "error") + options + listOf(
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height:stream_side_data=rotation",
                "-of",
                "default=noprint_wrappers=1",
                input,
            )
            val process = start(command)
            val output = process.inputStream.bufferedReader().readText()
            val errors = process.errorStream.bufferedReader().readText()
            val status = process.waitFor()
            val values = output.lines().mapNotNull { line ->
                line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
            }.toMap()
            val width = values["width"]?.toIntOrNull()
            val height = values["height"]?.toIntOrNull()
            if (status != 0 || width == null || height == null || width <= 0 || height <= 0) {
                throw IOException(errors.lineSequence().lastOrNull(String::isNotBlank) ?: "$input has no video")
            }
            val rotation = values["rotation"]?.toDoubleOrNull()?.let { abs(it.toInt()) % 180 } ?: 0
            return if (rotation == 90) height to width else width to height
        }

        private fun start(command: List<String>): Process = try {
            ProcessBuilder(command).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
        } catch (error: IOException) {
            throw IOException("Cannot run ${command.first()}: ${error.message}", error)
        }
    }
}
