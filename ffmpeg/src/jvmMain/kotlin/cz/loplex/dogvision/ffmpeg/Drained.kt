package cz.loplex.dogvision.ffmpeg

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A process's standard error, read on a thread of its own,
 * so that a program that writes a lot cannot fill the pipe and stall.\
 * Its last lines are kept, because they say why the program stopped.
 */
internal class Drained(process: Process) {
    private val lines = ArrayDeque<String>()

    private val reader = thread(name = "ffmpeg-errors", isDaemon = true) {
        try {
            process.errorStream.bufferedReader().forEachLine { line ->
                synchronized(lines) {
                    if (line.isNotBlank()) lines.addLast(line.trim())
                    if (lines.size > KEPT_LINES) lines.removeFirst()
                }
            }
        } catch (_: IOException) {
            // The stream closes when the process is stopped.
        }
    }

    /** Waits for the program's standard error to end, which it does when the program ends. */
    fun join() = reader.join(WAIT_MILLIS)

    /** The last line the program wrote, or null if it wrote nothing. */
    fun lastLine(): String? = synchronized(lines) { lines.lastOrNull() }

    private companion object {
        const val KEPT_LINES = 20
    }
}

/** [process]'s standard error, read as [Drained] reads it. */
internal fun drained(process: Process) = Drained(process)

/**
 * Ends [process] and whatever it started.
 * A launcher standing in for ffmpeg, such as Chocolatey's shim on Windows, runs the real ffmpeg as its child,
 * and ending the launcher alone would leave that child running and holding the files it writes.
 */
internal fun stop(process: Process) {
    val children = process.descendants().toList()
    process.destroyForcibly()
    children.forEach(ProcessHandle::destroyForcibly)
    process.waitFor(WAIT_MILLIS, TimeUnit.MILLISECONDS)
    for (child in children) runCatching { child.onExit().get(WAIT_MILLIS, TimeUnit.MILLISECONDS) }
}

private const val WAIT_MILLIS = 2000L
