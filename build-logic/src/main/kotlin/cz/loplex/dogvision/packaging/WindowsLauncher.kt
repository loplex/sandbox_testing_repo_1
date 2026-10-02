package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.attributes.Usage
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import java.io.File

/**
 * Writes in [destination] what a launcher of a Windows app image is to jpackage, named [launcherName]:
 *
 * - [launcherName].properties, in the format of jpackage's --add-launcher: [jar]'s name as its main-jar, [mainClass],
 *   [javaOptions], and whether it runs in a console. java-options is there when empty too, as jpackage would otherwise
 *   give the launcher the main launcher's.
 * - [launcherName].modules, the modules the launcher's runtime needs, one a line.
 */
abstract class WindowsLauncherFiles : DefaultTask() {
    @get:Input
    abstract val launcherName: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jar: RegularFileProperty

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val javaOptions: ListProperty<String>

    @get:Input
    abstract val console: Property<Boolean>

    @get:Input
    abstract val runtimeModules: ListProperty<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun write() {
        // jpackage takes java-options for words split at spaces.
        check(javaOptions.get().none { option -> option.any(Char::isWhitespace) }) {
            "A Java option holds a space: ${javaOptions.get()}"
        }
        val out = destination.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val properties = listOf(
            "main-jar" to jar.get().asFile.name,
            "main-class" to mainClass.get(),
            "java-options" to javaOptions.get().joinToString(" "),
            "win-console" to console.get().toString(),
        )
        for ((key, value) in properties) {
            // The values are written as they are: none of the project's needs a properties file's escapes.
            check(value.none { it == '\\' || it == '\n' || it == '\r' }) { "$key=$value needs escaping" }
        }
        File(out, "${launcherName.get()}.properties")
            .writeText(properties.joinToString("") { (key, value) -> "$key=$value\n" }, Charsets.UTF_8)
        File(out, "${launcherName.get()}.modules").writeText(runtimeModules.get().joinToString("") { "$it\n" })
    }
}

/** The usage of the variant that a module hands :packaging its Windows launcher by. */
const val WINDOWS_LAUNCHER_USAGE = "dog-vision-windows-launcher"

/**
 * Registers windowsLauncher, which writes in build/windows/launcher what the launcher [name] of the MSI's app image is,
 * as [WindowsLauncherFiles] says, and the variant of the module that hands :packaging those files and [jar]: the
 * launcher starts [mainClass] from [jar] with [javaOptions], in a console where [console], on a runtime with
 * [runtimeModules].
 */
fun Project.windowsLauncher(
    name: String,
    jar: Provider<RegularFile>,
    mainClass: String,
    javaOptions: List<String> = emptyList(),
    console: Boolean = false,
    runtimeModules: List<String>,
): TaskProvider<WindowsLauncherFiles> {
    val files = tasks.register("windowsLauncher", WindowsLauncherFiles::class.java) {
        description = "Writes build/windows/launcher, what $name is to jpackage in the MSI's app image."
        group = "distribution"
        launcherName.set(name)
        this.jar.set(jar)
        this.mainClass.set(mainClass)
        this.javaOptions.set(javaOptions)
        this.console.set(console)
        this.runtimeModules.set(runtimeModules)
        destination.set(layout.buildDirectory.dir("windows/launcher"))
    }
    configurations.consumable("windowsLauncherElements") {
        attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, WINDOWS_LAUNCHER_USAGE)) }
        outgoing.artifact(jar)
        outgoing.artifact(files.flatMap { it.destination.file("$name.properties") })
        outgoing.artifact(files.flatMap { it.destination.file("$name.modules") })
    }
    return files
}
