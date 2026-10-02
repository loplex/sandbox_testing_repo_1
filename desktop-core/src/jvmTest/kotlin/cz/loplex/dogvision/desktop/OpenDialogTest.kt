package cz.loplex.dogvision.desktop

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Which program asks for a file, with what, and what the window is told of it. */
class OpenDialogTest {
    private val both = setOf("kdialog", "zenity")

    @Test
    fun kdeGetsKdialogAndAnyOtherDesktopZenity() {
        assertEquals("kdialog", dialogCommand("Open", null, "KDE", both::contains)?.first())
        assertEquals("zenity", dialogCommand("Open", null, "ubuntu:GNOME", both::contains)?.first())
        assertEquals("zenity", dialogCommand("Open", null, null, both::contains)?.first())
    }

    @Test
    fun eitherStandsInForTheOtherAndNeitherLeavesItToAwt() {
        assertEquals("zenity", dialogCommand("Open", null, "KDE", setOf("zenity")::contains)?.first())
        assertEquals("kdialog", dialogCommand("Open", null, "GNOME", setOf("kdialog")::contains)?.first())
        assertNull(dialogCommand("Open", null, "KDE") { false })
    }

    @Test
    fun theDialogStartsBesideTheFileShown() {
        // Its absolute path, which on Windows, where the tests run too, is a drive's and has backslashes.
        val shown = File("/home/someone/photos/a.jpg")
        assertEquals(
            listOf("kdialog", "--title", "Open", "--getopenfilename", shown.absolutePath),
            dialogCommand("Open", shown, "KDE", both::contains),
        )
        assertEquals(
            listOf("zenity", "--file-selection", "--title=Open", "--filename=${shown.absolutePath}"),
            dialogCommand("Open", shown, "GNOME", both::contains),
        )
        assertEquals(
            listOf("zenity", "--file-selection", "--title=Open"),
            dialogCommand("Open", null, "", both::contains),
        )
    }

    @Test
    fun whatIsPickedIsOpenedAndACancelOpensNothing() {
        assertEquals(listOf(File("/tmp/a.jpg")), picked(run = { "/tmp/a.jpg" }))
        assertEquals(emptyList(), picked(run = { null }))
    }

    @Test
    fun aProgramThatCannotStartLeavesItToAwt() {
        assertEquals(
            listOf(File("/tmp/b.mp4")),
            picked(run = { throw IOException("No such file") }, fallback = File("/tmp/b.mp4")),
        )
    }

    @Test
    fun noProgramLeavesItToAwtThere() {
        val opened = mutableListOf<File>()
        OpenDialog(command = { _, _ -> null }, run = { error("Ran $it") }, fallback = { _, _, _ -> File("/tmp/c.jpg") })
            .show(null, "Open", null, opened::add)
        assertEquals(listOf(File("/tmp/c.jpg")), opened)
    }

    @Test
    fun aDialogOpenIsNotOpenedAgain() {
        val started = LinkedBlockingQueue<Unit>()
        val closed = CountDownLatch(1)
        val posted = LinkedBlockingQueue<() -> Unit>()
        val dialog = OpenDialog(
            command = { _, _ -> listOf("zenity") },
            run = {
                started.put(Unit)
                closed.await()
                "/tmp/a.jpg"
            },
            fallback = { _, _, _ -> error("No fallback") },
            post = posted::put,
        )
        val opened = mutableListOf<File>()
        dialog.show(null, "Open", null, opened::add)
        dialog.show(null, "Open", null, opened::add)
        assertNotNull(started.poll(5, TimeUnit.SECONDS))
        // A second program would have started by now, while the first one's dialog is still open.
        assertNull(started.poll(500, TimeUnit.MILLISECONDS))
        closed.countDown()
        checkNotNull(posted.poll(5, TimeUnit.SECONDS))()
        assertEquals(listOf(File("/tmp/a.jpg")), opened)
        dialog.show(null, "Open", null, opened::add)
        assertNotNull(started.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun runDialogSaysWhatTheProgramPrintedOnSuccessAlone() {
        assertEquals("/tmp/a b.jpg", runDialog(listOf("sh", "-c", "printf '/tmp/a b.jpg\\n'")))
        assertNull(runDialog(listOf("sh", "-c", "printf '/tmp/a.jpg\\n'; exit 1")))
        assertNull(runDialog(listOf("sh", "-c", "exit 0")))
    }

    /** What the window is told was picked in a dialog whose program is [run], with AWT's dialog picking [fallback]. */
    private fun picked(run: (List<String>) -> String?, fallback: File? = null): List<File> {
        val posted = LinkedBlockingQueue<() -> Unit>()
        val opened = mutableListOf<File>()
        val dialog = OpenDialog(
            command = { _, _ -> listOf("zenity") },
            run = run,
            fallback = { _, _, _ -> fallback },
            post = posted::put,
        )
        dialog.show(null, "Open", null, opened::add)
        checkNotNull(posted.poll(5, TimeUnit.SECONDS)) { "Nothing posted" }()
        return opened
    }
}
