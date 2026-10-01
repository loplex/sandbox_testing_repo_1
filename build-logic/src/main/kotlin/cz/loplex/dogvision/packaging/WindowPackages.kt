package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.tasks.Sync
import org.gradle.jvm.tasks.Jar

/**
 * Registers a window's deb and rpm, [packageName], on the system's Java, packageDeb and packageRpm, with the tasks they
 * are made from: the window's JARs in /usr/share/[packageName]/lib, the natives they load unpacked in
 * /usr/lib/[packageName], where the FHS puts what depends on the architecture, a launcher in /usr/bin, which finds a
 * Java 17 or newer, and the desktop entry and the icons, named [applicationId]. The command line is the package
 * dog-vision-cli, which each recommends.
 *
 * - [mainClass] is the class the launcher starts, its main the window's.
 * - [jvmOptions] are the launcher's options before org.lwjgl.librarypath's, given the folder of the natives.
 * - [nameSuffix] follows the desktop entry's name in each language.
 * - [summary] and [description] are what a package manager lists the packages with, in its search and its details.
 * - [nativesLeftIn] names the JARs, by the start of their names, whose natives are not unpacked.
 * - [rpmRequires] is what the rpm requires besides what every window's does.
 */
fun Project.windowPackages(
    packageName: String,
    applicationId: String,
    mainClass: String,
    jvmOptions: (natives: String) -> List<String> = { emptyList() },
    nameSuffix: String = "",
    summary: String,
    description: String,
    nativesLeftIn: List<String> = emptyList(),
    rpmRequires: List<String> = emptyList(),
) {
    val home = "/usr/share/$packageName"
    val nativesHome = "/usr/lib/$packageName"
    val linuxJars = files(tasks.named("jvmJar", Jar::class.java), configurations.named("jvmRuntimeClasspath"))
    // The names of the JARs in the lib folder that are not their own.
    val linuxJarNames = configurations.named("jvmRuntimeClasspath")
        .flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map(::installedJarNames)
    // The window's icons, which every package of the project installs.
    val icons = rootProject.layout.projectDirectory.dir("gui-compose/packaging")

    // Every native library in the JARs, which skiko and LWJGL would otherwise unpack at run time into the user's home
    // or /tmp.
    val linuxNatives = tasks.register("linuxNatives", UnpackNatives::class.java) {
        this.description = "Unpacks the Linux natives out of the JARs into build/packages/natives, for the deb and " +
            "the rpm to install in $nativesHome."
        jars.from(linuxJars.filter { jar -> nativesLeftIn.none { jar.name.startsWith(it) } })
        natives.set(layout.buildDirectory.dir("packages/natives"))
    }

    val linuxLauncher = tasks.register("linuxLauncher", JavaLauncher::class.java) {
        this.description = "Writes build/packages/launcher/$packageName, the script the deb and the rpm install in " +
            "/usr/bin, which starts the window on the system's Java 17 or newer, not a headless one."
        commandName.set(packageName)
        this.mainClass.set(mainClass)
        jars.from(linuxJars)
        jarDirectory.set("$home/lib")
        jarNames.set(linuxJarNames)
        this.jvmOptions.set(jvmOptions(nativesHome) + "-Dorg.lwjgl.librarypath=$nativesHome")
        minimumJava.set(17)
        opensWindow.set(true)
        script.set(layout.buildDirectory.file("packages/launcher/$packageName"))
    }

    val linuxDesktopEntry = tasks.register("linuxDesktopEntry", DesktopEntry::class.java) {
        this.description = "Writes build/packages/$applicationId.desktop, the desktop entry that puts the window in " +
            "the desktop's menu, named in each language of texts/strings" +
            if (nameSuffix.isEmpty()) "." else " with \"$nameSuffix\" after it."
        strings.set(rootProject.layout.projectDirectory.dir("texts/strings"))
        nameString.set("app_name")
        this.nameSuffix.set(nameSuffix)
        commentString.set("desktop_comment")
        exec.set(packageName)
        icon.set(applicationId)
        categories.set(listOf("Graphics"))
        // Java names each window's WM_CLASS after the class its main is in, the dots as dashes.
        startupWmClass.set(mainClass.replace('.', '-'))
        entry.set(layout.buildDirectory.file("packages/$applicationId.desktop"))
    }

    val linuxTree = tasks.register("linuxTree", Sync::class.java) {
        this.description = "Lays out in build/packages/tree the files the deb and the rpm install: the JARs, the " +
            "natives, the launcher, the desktop entry and the icons."
        into(layout.buildDirectory.dir("packages/tree"))
        from(linuxJars) {
            into(home.removePrefix("/") + "/lib")
            exclude(NativesOnly)
            renameJars(linuxJarNames)
        }
        from(linuxNatives) { into(nativesHome.removePrefix("/")) }
        from(linuxLauncher) { into("usr/bin") }
        from(linuxDesktopEntry) { into("usr/share/applications") }
        from(icons.file("dog-vision.png")) {
            into("usr/share/icons/hicolor/256x256/apps")
            rename("dog-vision.png", "$applicationId.png")
        }
        from(icons.file("dog-vision.svg")) {
            into("usr/share/icons/hicolor/scalable/apps")
            rename("dog-vision.svg", "$applicationId.svg")
        }
    }

    val rpmLibraryRequires = tasks.register("rpmLibraryRequires", RpmLibraryRequires::class.java) {
        this.description = "Lists in build/packages/rpmLibraryRequires.txt the libraries the natives link against " +
            "and do not bring themselves, which the rpm requires."
        image.set(linuxNatives.flatMap { it.natives })
        requires.set(layout.buildDirectory.file("packages/rpmLibraryRequires.txt"))
    }

    val debDepends = tasks.register("debDepends", DebDepends::class.java) {
        this.description = "Writes the deb's Depends into build/packages/debDepends.txt: the packages of the " +
            "libraries the natives link against and do not bring, then a Java that can open a window, libEGL and " +
            "ffmpeg."
        image.set(linuxNatives.flatMap { it.natives })
        packages.set(debianPackages)
        // What the natives do not name: a Java that can open a window, the distribution's default where it is 17 or
        // newer, as the Debian Java Policy has it; LWJGL opens libEGL once it runs, and ffmpeg runs apart for a video
        // or the camera.
        others.set(listOf("default-jre (>= 2:1.17) | java17-runtime", "libegl1", "ffmpeg"))
        depends.set(layout.buildDirectory.file("packages/debDepends.txt"))
    }

    val packageDeb = tasks.register("packageDeb", DebPackage::class.java) {
        this.description = "Packs build/packages/deb/${packageName}_<version>_amd64.deb, on the system's Java."
        group = "distribution"
        tree.set(layout.dir(linuxTree.map { it.destinationDir }))
        this.packageName.set(packageName)
        architecture.set("amd64")
        this.summary.set(summary)
        longDescription.set(description)
        depends.set(debDepends.flatMap { it.depends }.map { it.asFile.readText().split(", ") })
        recommends.set(listOf("dog-vision-cli"))
    }

    val packageRpm = tasks.register("packageRpm", RpmPackage::class.java) {
        this.description = "Packs build/packages/rpm/$packageName-<version>-1.x86_64.rpm, on the system's Java."
        group = "distribution"
        tree.set(layout.dir(linuxTree.map { it.destinationDir }))
        this.packageName.set(packageName)
        architecture.set("x86_64")
        this.summary.set(summary)
        longDescription.set(description)
        // The libraries the natives need, as rpm's own generator names them, since Fedora's and openSUSE's package
        // names differ; a Java that can open a window, as cli's rpm says why; libEGL, which LWJGL opens once it runs;
        // and ffmpeg's command rather than a package, as Fedora has two ffmpeg packages.
        requires.set(
            rpmLibraryRequires.flatMap { it.requires }.map { file ->
                file.asFile.readText().split(',') +
                    listOf("/bin/sh", "(jre-17 or jre-21 or jre-25)", "libEGL.so.1()(64bit)", "/usr/bin/ffmpeg") +
                    rpmRequires
            },
        )
        recommends.set(listOf("dog-vision-cli"))
    }
    artifact(packageDeb)
    artifact(packageRpm)
}
