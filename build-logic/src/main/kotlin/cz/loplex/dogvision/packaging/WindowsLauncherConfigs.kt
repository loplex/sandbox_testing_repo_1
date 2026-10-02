package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileTree
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Writes in [destination] the .cfg of each launcher in the app folder of [image], a Windows app image as jpackage made
 * it of [WindowsJpackageFiles], with the launcher's own JAR alone on its classpath: jpackage puts every JAR of its
 * input on each launcher's, the launcher's own first. The scripts in tools copy them over jpackage's.
 */
abstract class WindowsLauncherConfigs : DefaultTask() {
    @get:Internal
    abstract val image: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    val configs: FileTree
        get() = image.asFileTree.matching { include("app/*.cfg") }

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun write() {
        val out = destination.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val app = image.get().dir("app").asFile
        val configs = configs.files
        check(configs.isNotEmpty()) { "$app holds no launcher's .cfg" }
        for (config in configs) {
            // The lines with their ends, which jpackage writes as the system it runs on does.
            val lines = Regex("(?<=\n)").split(config.readText(Charsets.UTF_8)).filter(String::isNotEmpty)
            val classpath = lines.filter { it.startsWith(LAUNCHER_CLASSPATH) }
            check(classpath.isNotEmpty()) { "$config has no $LAUNCHER_CLASSPATH" }
            val own = classpath.first().removePrefix(LAUNCHER_CLASSPATH).trimEnd()
            val jar = File(app, own.removePrefix("\$APPDIR\\"))
            check(own.startsWith("\$APPDIR\\") && jar.isFile) {
                "$config's first $LAUNCHER_CLASSPATH is no JAR in $app: $own"
            }
            File(out, config.name).writeText((lines - classpath.drop(1).toSet()).joinToString(""), Charsets.UTF_8)
        }
    }
}

/** The key of a line of a launcher's .cfg that names a file of its classpath, which jpackage writes one a line. */
internal const val LAUNCHER_CLASSPATH = "app.classpath="
