import cz.loplex.dogvision.packaging.DebPackage
import cz.loplex.dogvision.packaging.JavaLauncher
import cz.loplex.dogvision.packaging.RpmPackage
import cz.loplex.dogvision.packaging.WINDOWS_LAUNCHER_USAGE
import cz.loplex.dogvision.packaging.artifact
import cz.loplex.dogvision.packaging.jvmRuntimeOf
import cz.loplex.dogvision.packaging.windowPackages
import cz.loplex.dogvision.packaging.windowsAppImage
import cz.loplex.dogvision.packaging.windowsMsi
import cz.loplex.dogvision.packaging.windowsRuntimeImage

// Packages made apart from the modules they hold:
// - the deb and the rpm of the Compose window, the Swing window and the command line, on the system's Java, each of
//   the JARs its module runs on, which jvmRuntimeOf takes;
// - the MSI for Windows on x86-64, of the Compose window, the Swing window and the command line, each a launcher of
//   one app image, on one runtime, and the command line's zip, of its launcher alone. Each of those modules hands this
//   one its launcher through windowsLauncher. The scripts in tools run jpackage and WiX over what this module writes.
plugins {
    base
    // The toolchains, for the JDK whose jlink links the runtime.
    `jvm-toolchains`
    id("cz.loplex.dogvision.packaging")
}

val launchers = configurations.dependencyScope("windowsLaunchers")
val launcherFiles = configurations.resolvable("windowsLauncherFiles") {
    extendsFrom(launchers.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(WINDOWS_LAUNCHER_USAGE)) }
}
dependencies {
    for (module in listOf(":gui-compose", ":gui-swing", ":cli")) launchers(project(module))
}

// One runtime of every module a launcher needs, and dog-vision.exe the image's main launcher, whose name the image's
// folder has.
windowsAppImage(
    packageName = "dog-vision",
    description = "How a dog or another animal sees a photo, a video or the camera",
    launchers = launcherFiles.get(),
    runtime = windowsRuntimeImage(
        "the runtime of the MSI's launchers",
        modules = emptyList(),
        moduleLists = launcherFiles.get().filter { it.name.endsWith(".modules") },
    ),
)

// What jpackage makes the app image of the command line's zip for Windows on x86-64 of, as the scripts in tools hand
// it: the command line alone, on a runtime of its own, from the files cli hands over.
val cliLauncherFiles = launcherFiles.get().incoming.artifactView {
    componentFilter { it is ProjectComponentIdentifier && it.projectPath == ":cli" }
}.files
windowsAppImage(
    packageName = "dog-vision-cli",
    description = "How a dog or another animal sees a photo, from the command line",
    launchers = cliLauncherFiles,
    runtime = windowsRuntimeImage(
        "the command line's runtime for Windows",
        modules = emptyList(),
        moduleLists = cliLauncherFiles.filter { it.name.endsWith(".modules") },
        name = "Cli",
    ),
    name = "Cli",
)

windowsMsi(
    product = layout.projectDirectory.file("windows/dog-vision.wxs"),
    codePage = layout.projectDirectory.file("windows/codepage.wxl"),
) {
    packageDescription = "How a dog or another animal sees a photo, a video or the camera"
    upgradeCode = "bd534b2e-fc9e-40d5-a461-b800bf54bcda"
    installFolder = "dog-vision"
}

tasks.register("packageDeb") {
    description = "Packs every deb into build/distributions."
    group = "distribution"
    dependsOn(tasks.withType<DebPackage>())
}
tasks.register("packageRpm") {
    description = "Packs every rpm into build/distributions."
    group = "distribution"
    dependsOn(tasks.withType<RpmPackage>())
}

// The deb and the rpm, dog-vision, on the system's Java.
windowPackages(
    packageName = "dog-vision",
    jars = jvmRuntimeOf(":gui-compose"),
    // The application's ID, which the Windows MSI does not use.
    applicationId = "cz.loplex.dogvision",
    mainClass = "cz.loplex.dogvision.desktop.MainKt",
    // As jpackage's launcher passes them, but for the resources folder, which the window has no use for.
    jvmOptions = { natives ->
        listOf("-Dcompose.application.configure.swing.globals=true", "-Dskiko.library.path=$natives")
    },
    summary = "How a dog or another animal sees colours (Kotlin Compose GUI)",
    description = """
        dog-vision shows a photo, a video or the camera with the colours a dog,
        a cat or another animal can tell apart, beside the original.

        This package is the desktop window, in Compose.

        Given a photo alone, it converts it as the command line does, which is
        the package dog-vision-cli.
    """.trimIndent(),
)

// The deb and the rpm, dog-vision-swing, on the system's Java, as the Compose window's dog-vision, with the desktop
// entry and icons under names of their own, so that both windows install side by side.
windowPackages(
    packageName = "dog-vision-swing",
    jars = jvmRuntimeOf(":gui-swing"),
    // Not the Compose window's cz.loplex.dogvision.
    applicationId = "cz.loplex.dogvision.swing",
    mainClass = "cz.loplex.dogvision.swing.MainKt",
    nameSuffix = " (Swing)",
    summary = "How a dog or another animal sees colours (Java Swing GUI)",
    description = """
        dog-vision shows a photo, a video or the camera with the colours a dog,
        a cat or another animal can tell apart, beside the original.

        This package is the desktop window in Swing, which needs neither Compose
        nor skiko. The package dog-vision is the same window in Compose.

        Given a photo alone, it converts it as the command line does, which is
        the package dog-vision-cli.
    """.trimIndent(),
    // FlatLaf loads its natives on Linux only for window decorations of its own, which the window does not use, and
    // they link GTK 3.
    nativesLeftIn = listOf("flatlaf-"),
    // A font Java can use, without which Swing cannot start: openSUSE's JRE brings none, where Debian's fontconfig
    // does. Named outright, DejaVu Sans as Fedora and Rocky package it, or DejaVu as openSUSE does: font(:lang=en) is
    // met on openSUSE by xorg-x11-fonts-core, whose bitmap fonts Java does not read.
    rpmRequires = listOf("(dejavu-sans-fonts or dejavu-fonts)"),
)

// The deb and the rpm, dog-vision-cli, on the system's Java: its JARs in /usr/share/dog-vision-cli/lib and a launcher
// in /usr/bin, which finds a Java 17 or newer. It has no natives, so one package serves every architecture, and it
// needs a headless Java only, as it opens no window.
val cliPackage = "dog-vision-cli"
val cliHome = "/usr/share/$cliPackage"
val cliJars = jvmRuntimeOf(":cli")

val cliLauncher = tasks.register<JavaLauncher>("dogVisionCliLauncher") {
    description = "Writes build/linux/dog-vision-cli/launcher/dog-vision-cli, the script its deb and rpm install in " +
        "/usr/bin, which starts the command line on the system's Java 17 or newer."
    commandName = cliPackage
    mainClass = "cz.loplex.dogvision.cli.MainKt"
    jars.from(cliJars)
    jarDirectory = "$cliHome/lib"
    jvmOptions = emptyList()
    minimumJava = 17
    script = layout.buildDirectory.file("linux/$cliPackage/launcher/$cliPackage")
}

val cliTree = tasks.register<Sync>("dogVisionCliTree") {
    description = "Lays out in build/linux/dog-vision-cli/tree the files its deb and its rpm install: the JARs " +
        "and the launcher."
    into(layout.buildDirectory.dir("linux/$cliPackage/tree"))
    from(cliJars) { into(cliHome.removePrefix("/") + "/lib") }
    from(cliLauncher) { into("usr/bin") }
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val cliSummary = "How a dog or another animal sees colours (command line)"
val cliDescription = """
    dog-vision shows a photo, a video or the camera with the colours a dog,
    a cat or another animal can tell apart, beside the original.

    This package is the command line, which converts a photo to those
    colours, as a PNG beside it, with no window and no display.

    The desktop windows, which show a video or the camera as well, are
    the packages dog-vision and dog-vision-swing.
""".trimIndent()

val packageCliDeb = tasks.register<DebPackage>("packageDogVisionCliDeb") {
    description = "Packs build/distributions/dog-vision-cli_<version>_all.deb, on the system's Java."
    group = "distribution"
    tree = layout.dir(cliTree.map { it.destinationDir })
    packageName = cliPackage
    architecture = "all"
    summary = cliSummary
    longDescription = cliDescription
    // The distribution's default JRE where it is 17 or newer, as the Debian Java Policy has it, and any JRE that
    // provides java17-runtime-headless where it is not: Ubuntu 20.04's and 22.04's are 11.
    depends = listOf("default-jre-headless (>= 2:1.17) | java17-runtime-headless")
    recommends = emptyList()
}
artifact(packageCliDeb)

val packageCliRpm = tasks.register<RpmPackage>("packageDogVisionCliRpm") {
    description = "Packs build/distributions/dog-vision-cli-<version>-1.noarch.rpm, on the system's Java."
    group = "distribution"
    tree = layout.dir(cliTree.map { it.destinationDir })
    packageName = cliPackage
    architecture = "noarch"
    summary = cliSummary
    longDescription = cliDescription
    // No one name that every rpm JRE of 17 or newer provides, and no older one: jre-headless >= 17 would take Fedora's
    // and Rocky's Java 8, which provides it at epoch 1 and so above any version at epoch 0, and Temurin's 8 and 11,
    // which provide it with no version at all. A new LTS joins the list when a distribution makes it its default.
    requires = listOf("/bin/sh", "(jre-17-headless or jre-21-headless or jre-25-headless)")
    recommends = emptyList()
}
artifact(packageCliRpm)
