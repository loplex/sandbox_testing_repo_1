package cz.loplex.dogvision.packaging

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files

/**
 * Writes in [destination] what jpackage makes a package's app image and MSI for Windows of, which
 * tools/package_app_image_on_linux.sh and tools/package_msi_on_linux.sh hand it under Wine and
 * tools/package_msi_on_windows.ps1 on Windows, so that both make the same MSI:
 *
 * - package-arguments: what the image and the MSI both say of the package: its name, version, vendor, description
 *   and icon.
 * - image-arguments: what the image runs: the folder holding [mainJar] alone, [mainClass], [javaOptions], [runtime]
 *   and the launchers.
 * - msi-arguments: what the MSI adds: [license], the resource directory, a chooser of the folder it installs into,
 *   [upgradeCode] and the shortcuts.
 * - resources, the resource directory: jpackage's main.wxs with [fragment] in it, and the MSI's code page.
 * - input, the folder of [mainJar] alone, as jpackage takes every file in its --input into the application.
 *
 * Each arguments file is UTF-8, each argument in double quotes, in which a backslash and a quote are escaped with a
 * backslash, as jpackage reads @files: within quotes, \n, \r, \t and \f are taken for control characters, which a
 * Windows path holds. JDK 17's jpackage reads a file in its default charset, which before JDK 18 is the system's, so
 * the scripts run it with -J-Dfile.encoding=UTF-8. Paths in the files are Windows's, under Wine, where the build
 * does not run on Windows, as Wine sees them through its drive Z:, which it maps to the root.
 */
abstract class WindowsJpackageFiles : DefaultTask() {
    /** The name of the package's files, its launcher's and the folder it installs into. */
    @get:Input
    abstract val packageName: Property<String>

    /** The app's name, which the product's name starts with and the fragment takes for @APP_NAME@. */
    @get:Input
    abstract val appName: Property<String>

    /**
     * What follows [appName] in the product's name, which the installer, the system's list of programs and the Start
     * menu's group show.
     */
    @get:Input
    abstract val productSuffix: Property<String>

    @get:Input
    abstract val version: Property<String>

    @get:Input
    abstract val vendor: Property<String>

    @get:Input
    abstract val packageDescription: Property<String>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val icon: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mainJar: RegularFileProperty

    @get:Input
    abstract val mainClass: Property<String>

    @get:Input
    abstract val javaOptions: ListProperty<String>

    /** The runtime the image runs on, as windowsRuntime links it. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val runtime: DirectoryProperty

    /** Launchers beside the package's own, each a jpackage launcher's properties file, named as it less .properties. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val launchers: ConfigurableFileCollection

    /**
     * Whether the package is the command line's: its launcher runs in a console, and its MSI makes no shortcut, as
     * dog-vision-cli.exe started from one only prints its usage.
     */
    @get:Input
    abstract val commandLine: Property<Boolean>

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val license: RegularFileProperty

    /** The pictures of the MSI's dialogs, as windowsBitmaps draws them. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val banner: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.ABSOLUTE)
    abstract val dialog: RegularFileProperty

    /**
     * The MSI's upgrade code: every later version's MSI replaces the one installed with the same code, as Windows
     * Installer tells versions of one product apart by it. Never change it, nor give two packages one, as installing
     * the one would then remove the other.
     */
    @get:Input
    abstract val upgradeCode: Property<String>

    /**
     * The fragment main.wxs takes before its end, which says what it does, with @APP_NAME@, @NAME@ and @GUID@ in it
     * for [appName], [packageName] and [componentGuid]; main.wxs refers to [componentGroup] in it beside its files.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val fragment: RegularFileProperty

    @get:Input
    abstract val componentGroup: Property<String>

    /** The GUID of the fragment's component, where it has one: the package's own, which never changes either. */
    @get:Input
    abstract val componentGuid: Property<String>

    /** The localization that sets the MSI's code page, for JDK 17's jpackage, as [writeCodePage] says. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val codePage: RegularFileProperty

    /** The Windows JDK whose jpackage the scripts run, which main.wxs and the code page's handling are taken from. */
    @get:Internal
    abstract val jpackageJdk: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jpackageRelease: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jpackageModules: RegularFileProperty

    /** Whether jpackage runs under Wine, which sees the paths through its drive Z:, rather than on Windows. */
    @get:Input
    abstract val underWine: Property<Boolean>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun write() {
        val out = destination.get().asFile
        out.deleteRecursively()
        val input = File(out, "input").apply { mkdirs() }
        val jar = mainJar.get().asFile
        jar.copyTo(File(input, jar.name))
        val resources = File(out, "resources").apply { mkdirs() }
        val jdk = jpackageJdk.get().asFile
        val feature = feature(jpackageRelease.get().asFile)
        writeCodePage(jdk, feature, resources)
        File(resources, "main.wxs").writeText(mainWxs(read(jdk, MAIN_WXS)), Charsets.UTF_8)

        val product = appName.get() + productSuffix.get()
        writeArguments(
            File(out, "package-arguments"),
            listOf(
                "--name" to packageName.get(),
                "--app-version" to version.get(),
                "--vendor" to vendor.get(),
                "--description" to packageDescription.get(),
                "--icon" to path(icon.get().asFile),
            ),
        )
        val launcherOptions = if (commandLine.get()) {
            listOf("--win-console" to null)
        } else {
            launchers.files.sortedBy { it.name }.map { launcher ->
                "--add-launcher" to "${launcher.name.removeSuffix(".properties")}=${path(launcher)}"
            }
        }
        writeArguments(
            File(out, "image-arguments"),
            listOf("--input" to path(input), "--main-jar" to jar.name, "--main-class" to mainClass.get()) +
                javaOptions.get().map { "--java-options" to it } +
                listOf("--runtime-image" to path(runtime.get().asFile)) +
                launcherOptions,
        )
        val shortcuts = if (commandLine.get()) {
            emptyList()
        } else {
            listOf("--win-menu" to null, "--win-menu-group" to product, "--win-shortcut" to null)
        }
        writeArguments(
            File(out, "msi-arguments"),
            listOf(
                "--license-file" to path(license.get().asFile),
                "--resource-dir" to path(resources),
                "--win-dir-chooser" to null,
                "--win-upgrade-uuid" to upgradeCode.get(),
            ) + shortcuts,
        )
    }

    /**
     * Sets the MSI's code page to Windows-1250, Central Europe's, in place of jpackage's Windows-1252, which has no ř
     * for the vendor's name. JDK 25's jpackage replaces its own English strings with a file of their name in the
     * resource directory, so for it they go there with this code page. JDK 17's hands light.exe its own strings and
     * then each localization of the resource directory; light.exe takes the code page from the first, which is
     * jpackage's, so [codePage] goes there, which defines no string, and tools/package_msi_on_linux.sh, which runs
     * light.exe again under Wine, hands it the resource directory's first.
     */
    private fun writeCodePage(jdk: File, feature: Int, resources: File) {
        when (feature) {
            17 -> codePage.get().asFile.copyTo(File(resources, codePage.get().asFile.name))

            25 -> {
                val strings = read(jdk, STRINGS_WXL)
                check(strings.contains("Codepage=\"1252\"")) { "$jdk's $STRINGS_WXL names no code page 1252" }
                val replaced = strings.replace("Codepage=\"1252\"", "Codepage=\"1250\"")
                File(resources, STRINGS_WXL).writeText(replaced, Charsets.UTF_8)
            }

            else -> error("$jdk is a JDK $feature: how its jpackage takes a code page is known for JDK 17 and 25 alone")
        }
    }

    /**
     * jpackage's main.wxs with the product's name and the dialogs' bitmaps set, and [fragment] in it; it fails where
     * main.wxs has not one product's name, one </Product>, one reference to Files and one </Wix>.
     */
    private fun mainWxs(text: String): String {
        val product = appName.get() + productSuffix.get()
        for (anchor in listOf(PRODUCT_NAME, "</Product>", FILES_REFERENCE, "</Wix>")) {
            check(text.split(anchor).size == 2) { "jpackage's main.wxs has not one $anchor" }
        }
        val bitmaps = "<WixVariable Id=\"WixUIBannerBmp\" Value=\"${path(banner.get().asFile)}\"/>\n" +
            "  <WixVariable Id=\"WixUIDialogBmp\" Value=\"${path(dialog.get().asFile)}\"/>\n  </Product>"
        val fragmentText = fragment.get().asFile.readText().trimEnd('\n')
            .replace("@APP_NAME@", appName.get())
            .replace("@NAME@", packageName.get())
            .replace("@GUID@", componentGuid.get())
        // Ended with one line break, as the shell's $(<file) and printf '%s\n' wrote it before.
        return text.trimEnd('\n')
            .replace(PRODUCT_NAME, "Name=\"$product\"")
            .replace("</Product>", bitmaps)
            .replace(FILES_REFERENCE, "$FILES_REFERENCE\n      <ComponentGroupRef Id=\"${componentGroup.get()}\"/>")
            .replace("</Wix>", "$fragmentText\n</Wix>") + "\n"
    }

    /** [file]'s path as jpackage sees it: Windows's own, or under Wine through its drive Z:. */
    private fun path(file: File): String =
        if (underWine.get()) "Z:" + file.absolutePath.replace('/', '\\') else file.absolutePath

    /** The feature release of the JDK whose release file is [release], as its JAVA_VERSION starts with it. */
    private fun feature(release: File): Int {
        val version = Regex("""(?m)^JAVA_VERSION="(\d+)""").find(release.readText())
        return checkNotNull(version) { "$release names no JAVA_VERSION" }.groupValues[1].toInt()
    }

    /** A resource of [jdk]'s jpackage, read through the JDK's own jrt file system. */
    private fun read(jdk: File, name: String): String =
        FileSystems.newFileSystem(URI.create("jrt:/"), mapOf("java.home" to jdk.absolutePath)).use { jrt ->
            val resource = jrt.getPath("modules", "jdk.jpackage", "jdk/jpackage/internal/resources", name)
            check(Files.exists(resource)) { "$jdk's jpackage has no $name" }
            Files.readString(resource)
        }

    /** Writes [options] into [file], each an option and its value, where it takes one. */
    private fun writeArguments(file: File, options: List<Pair<String, String?>>) {
        val arguments = options.flatMap { (option, value) -> listOfNotNull(option, value) }
        val lines = arguments.map { argument -> "\"" + argument.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
        file.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    private companion object {
        const val MAIN_WXS = "main.wxs"
        const val STRINGS_WXL = "MsiInstallerStrings_en.wxl"
        const val PRODUCT_NAME = "Name=\"\$(var.JpAppName)\""
        const val FILES_REFERENCE = "<ComponentGroupRef Id=\"Files\"/>"
    }
}
