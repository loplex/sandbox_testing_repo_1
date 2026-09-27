package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Writes an application's desktop entry, which puts it in the desktop's menu, named in each language [strings] has:
 * `Name=` from values/strings.xml, and `Name[cs]=` and the like from values-cs/strings.xml, so that the menu names the
 * application in the system's language, as the window's title does, each with [nameSuffix] after it. Its
 * `Comment=`, which the menu shows beside the name, comes from [commentString] in each language alike.
 */
abstract class DesktopEntry : DefaultTask() {
    /** Android's strings.xml files, as texts has them: values/ in English, values-<language>/ in each other one. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val strings: DirectoryProperty

    /** The string the name is, such as app_name. */
    @get:Input
    abstract val nameString: Property<String>

    /** What follows the name in every language, such as " (Swing)", where two packages share one; none by default. */
    @get:Input
    abstract val nameSuffix: Property<String>

    /** The string the comment is, such as desktop_comment. */
    @get:Input
    abstract val commentString: Property<String>

    /** The command, as the desktop runs it from PATH. */
    @get:Input
    abstract val exec: Property<String>

    /** The icon's name in the icon theme, with no extension. */
    @get:Input
    abstract val icon: Property<String>

    @get:Input
    abstract val categories: ListProperty<String>

    @get:OutputFile
    abstract val entry: RegularFileProperty

    init {
        nameSuffix.convention("")
    }

    @TaskAction
    fun write() {
        val folders = strings.get().asFile.listFiles { file -> file.isDirectory && file.name.startsWith("values") }
            .orEmpty().sortedBy { it.name }
        val names = mutableListOf<String>()
        val comments = mutableListOf<String>()
        for (folder in folders) {
            val file = folder.resolve("strings.xml")
            // values-pt-rBR is pt_BR in a desktop entry's key.
            val language = folder.name.removePrefix("values").removePrefix("-").replace("-r", "_")
            val key = if (language.isEmpty()) "" else "[$language]"
            androidString(file, nameString.get())?.let { names += "Name$key=$it${nameSuffix.get()}" }
            androidString(file, commentString.get())?.let { comments += "Comment$key=$it" }
        }
        check(names.any { it.startsWith("Name=") }) { "values/strings.xml has no ${nameString.get()}" }
        check(comments.any { it.startsWith("Comment=") }) { "values/strings.xml has no ${commentString.get()}" }
        val lines = listOf("[Desktop Entry]", "Type=Application") + names + comments + listOf(
            "Exec=${exec.get()}",
            "Icon=${icon.get()}",
            "Terminal=false",
            "Categories=${categories.get().joinToString("") { "$it;" }}",
        )
        entry.get().asFile.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    /** The string [name] of the strings.xml [file], unescaped as Android reads it, or null where it has none. */
    private fun androidString(file: File, name: String): String? {
        if (!file.isFile) return null
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = document.getElementsByTagName("string")
        val element = (0 until strings.length).map { strings.item(it) }
            .firstOrNull { it.attributes.getNamedItem("name")?.nodeValue == name } ?: return null
        return element.textContent.replace("\\'", "'").replace("\\\"", "\"")
    }
}
