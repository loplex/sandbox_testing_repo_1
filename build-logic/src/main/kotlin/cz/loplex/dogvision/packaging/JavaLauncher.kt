package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

/**
 * Writes the POSIX sh script a deb or an rpm starts [mainClass] with, on the system's Java rather than one of the
 * package's own: it finds a Java [minimumJava] or newer as launcher.sh, beside this class, says.
 */
abstract class JavaLauncher : DefaultTask() {
    /** The command's name, which it is installed as and says in its error. */
    @get:Input
    abstract val commandName: Property<String>

    @get:Input
    abstract val mainClass: Property<String>

    /** Each JAR's path where the package installs it, in the order the classpath takes them. */
    @get:Input
    abstract val classpath: ListProperty<String>

    @get:Input
    abstract val jvmOptions: ListProperty<String>

    @get:Input
    abstract val minimumJava: Property<Int>

    @get:OutputFile
    abstract val script: RegularFileProperty

    @TaskAction
    fun write() {
        val unquotable = (classpath.get() + jvmOptions.get()).filter { '\'' in it }
        check(unquotable.isEmpty()) { "The launcher cannot put $unquotable in single quotes" }
        val template = checkNotNull(javaClass.getResource("launcher.sh")) { "No launcher.sh beside JavaLauncher" }
        val text = template.readText()
            .replace("@NAME@", commandName.get())
            .replace("@MINIMUM@", minimumJava.get().toString())
            .replace("@JVM_OPTIONS@", jvmOptions.get().joinToString("") { "'$it' " })
            .replace("@CLASSPATH@", classpath.get().joinToString(":"))
            .replace("@MAIN_CLASS@", mainClass.get())
        val file = script.get().asFile
        file.writeText(text)
        file.setExecutable(true)
    }
}
