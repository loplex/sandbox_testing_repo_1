package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Links in [destination] the runtime jpackage takes with --runtime-image: [modules] alone from the jmods in [jmods],
 * with the jlink of [jdkHome] and the options jpackage links its own with. The jmods may be another system's, whose
 * runtime it then is: jlink asks only that they are of its own feature release.
 */
abstract class RuntimeImage : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val files: FileSystemOperations

    /** The JDK whose jlink links the runtime. */
    @get:Input
    abstract val jdkHome: Property<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val jmods: DirectoryProperty

    @get:Input
    abstract val modules: ListProperty<String>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun link() {
        // jlink refuses a destination that exists.
        files.delete { delete(destination) }
        @Suppress("ktlint:standard:argument-list-wrapping")
        exec.exec {
            commandLine(
                "${jdkHome.get()}/bin/jlink" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "",
                "--module-path", jmods.get().asFile.path,
                "--add-modules", modules.get().joinToString(","),
                "--strip-native-commands",
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--output", destination.get().asFile.path,
            )
        }
    }
}
