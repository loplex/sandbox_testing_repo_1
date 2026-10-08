package cz.loplex.dogvision.desktop

import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser
import kotlin.concurrent.thread

/**
 * The dialog a window opens a photo or a video from, or picks a [folder] in, one at a time, which starts beside the
 * file shown, or in the folder shown, if any.
 *
 * On Linux it is kdialog's on KDE and zenity's elsewhere, either where the other is missing, each run as a program of
 * its own, and AWT's FileDialog where neither is on the PATH, and on Windows. The JDK's GTK dialog comes up below the
 * window on KDE from its second opening on: its window keeps the time of the first one's last use, which KWin's focus
 * stealing prevention takes as older than the click into the window. A program started afresh has no such time, so
 * its dialog comes up on top. tinyfiledialogs picks its dialog the same way.
 *
 * [command] is what asks for a file, if anything does, [run] runs it and says what was picked, and [fallback] shows
 * AWT's dialog, or Swing's for a folder, which AWT's cannot pick on Linux and Windows; the tests give their own of
 * each, and a [post] that runs what it is given there and then.
 */
class OpenDialog(
    folder: Boolean = false,
    private val command: (title: String, shown: File?) -> List<String>? = { title, shown ->
        systemCommand(title, shown, folder)
    },
    private val run: (List<String>) -> String? = ::runDialog,
    private val fallback: (parent: Frame?, title: String, shown: File?) -> File? =
        if (folder) ::swingFolderDialog else ::awtDialog,
    private val post: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
) {
    /** Whether a program's dialog is open, which a key or a button pressed meanwhile does not open again. */
    private var open = false

    /**
     * Shows the dialog over [parent], titled [title], in the folder of the file [shown], if one is, and tells
     * [onPicked] the file picked, if one is; a program's dialog is waited for on a thread of its own, and [onPicked]
     * told on the event thread, as it is called.
     */
    fun show(parent: Frame?, title: String, shown: File?, onPicked: (File) -> Unit) {
        if (open) return
        val command = command(title, shown)
        if (command == null) {
            fallback(parent, title, shown)?.let(onPicked)
            return
        }
        open = true
        thread(name = "dog-vision-open-dialog", isDaemon = true) {
            val picked = runCatching { run(command) }
            post {
                open = false
                picked.fold(
                    onSuccess = { path -> path?.let { onPicked(File(it)) } },
                    onFailure = { fallback(parent, title, shown)?.let(onPicked) },
                )
            }
        }
    }
}

/**
 * The command of kdialog or zenity that asks for a file, or a [folder], in a dialog titled [title], starting beside
 * [shown], or in it for a folder, if any: the first of kdialog and zenity found by [onPath], kdialog first where
 * [desktops], XDG_CURRENT_DESKTOP's list, names KDE; null where neither is found.
 */
internal fun dialogCommand(
    title: String,
    shown: File?,
    desktops: String?,
    onPath: (String) -> Boolean,
    folder: Boolean = false,
): List<String>? {
    val onKde = desktops.orEmpty().split(':').any { it.equals("KDE", ignoreCase = true) }
    val program = (if (onKde) listOf("kdialog", "zenity") else listOf("zenity", "kdialog")).firstOrNull(onPath)
    // Both open the folder of a file they are given, kdialog with the file picked; zenity 4 opens a folder given alone,
    // with or without a slash at its end, in the folder above it.
    val start = shown?.absolutePath
    return when (program) {
        "kdialog" -> listOf(
            "kdialog",
            "--title",
            title,
            if (folder) "--getexistingdirectory" else "--getopenfilename",
        ) +
            listOfNotNull(start)

        // zenity opens the folder a path ends with a slash in, where the path is a folder's.
        "zenity" -> listOf("zenity", "--file-selection") + listOfNotNull("--directory".takeIf { folder }) +
            listOf("--title=$title") + listOfNotNull(start?.let { if (folder) "--filename=$it/" else "--filename=$it" })

        else -> null
    }
}

/** The dialog's command on this system: kdialog's or zenity's on Linux, where found, and none on Windows. */
private fun systemCommand(title: String, shown: File?, folder: Boolean): List<String>? =
    if (onWindows) null else dialogCommand(title, shown, System.getenv("XDG_CURRENT_DESKTOP"), ::onPath, folder)

/** Whether [program] is an executable file in one of the PATH's folders. */
private fun onPath(program: String): Boolean =
    System.getenv("PATH").orEmpty().split(File.pathSeparator).any { File(it, program).canExecute() }

/**
 * Runs [command] until its dialog is closed, and says the path it printed, or null where nothing was picked, which
 * kdialog and zenity say by an exit status of 1; throws where the program cannot be started.
 */
internal fun runDialog(command: List<String>): String? {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.close()
    val printed = process.inputStream.bufferedReader().readText().trimEnd('\n')
    return printed.takeIf { process.waitFor() == 0 && it.isNotEmpty() }
}

/** Swing's dialog for a folder over [parent], titled [title], in the folder [shown], if any, and the one picked. */
private fun swingFolderDialog(parent: Frame?, title: String, shown: File?): File? {
    val chooser = JFileChooser(shown).apply {
        dialogTitle = title
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
    }
    val picked = chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION
    return chooser.selectedFile.takeIf { picked }
}

/** AWT's dialog over [parent], titled [title], in the folder of [shown], if any, and the file picked, if one is. */
private fun awtDialog(parent: Frame?, title: String, shown: File?): File? {
    val dialog = FileDialog(parent, title, FileDialog.LOAD)
    if (shown != null) dialog.directory = shown.absoluteFile.parent
    dialog.isVisible = true
    return dialog.files.firstOrNull()
}
