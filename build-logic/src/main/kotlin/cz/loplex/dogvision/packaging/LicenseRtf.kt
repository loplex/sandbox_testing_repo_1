package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Writes [rtf], the licence an MSI's dialog shows, which jpackage takes as it is from a .rtf file, from [text], line
 * for line, as the text has its lines broken by hand: jpackage's own conversion of a text file runs each paragraph's
 * lines together, so that a title runs into the line below it, and ends the RTF's header with a lone LF, which the
 * dialog under Wine draws as a box, where this ends every line with CRLF.
 */
abstract class LicenseRtf : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val text: RegularFileProperty

    @get:OutputFile
    abstract val rtf: RegularFileProperty

    @TaskAction
    fun write() {
        val lines = text.get().asFile.readText().removeSuffix("\n").lines()
        val body = lines.joinToString("") { line -> if (line.isBlank()) "\\par\r\n" else "${escape(line)}\\line\r\n" }
        // Arial at 9 points, in which all but the longest lines fit the dialog's width.
        val header = "{\\rtf1\\ansi\\ansicpg1252\\deff0{\\fonttbl{\\f0\\fswiss Arial;}}\r\n\\f0\\fs18\r\n"
        rtf.get().asFile.writeText("$header$body}\r\n", Charsets.US_ASCII)
    }

    /** The line with RTF's own characters escaped, and each other than ASCII as its UTF-16 unit. */
    private fun escape(line: String): String = buildString {
        for (char in line.trimEnd('\r')) {
            when {
                char == '\\' || char == '{' || char == '}' -> append('\\').append(char)
                char.code < 0x80 -> append(char)
                else -> append("\\u").append(char.code.toShort()).append('?')
            }
        }
    }
}
