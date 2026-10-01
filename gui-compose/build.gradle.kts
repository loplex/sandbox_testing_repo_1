import cz.loplex.dogvision.packaging.DebDepends
import cz.loplex.dogvision.packaging.DebPackage
import cz.loplex.dogvision.packaging.DesktopEntry
import cz.loplex.dogvision.packaging.JavaLauncher
import cz.loplex.dogvision.packaging.NativesOnly
import cz.loplex.dogvision.packaging.RpmLibraryRequires
import cz.loplex.dogvision.packaging.RpmPackage
import cz.loplex.dogvision.packaging.UnpackNatives
import cz.loplex.dogvision.packaging.debianPackages
import cz.loplex.dogvision.packaging.glNatives
import cz.loplex.dogvision.packaging.installedJarNames
import cz.loplex.dogvision.packaging.renameJars
import cz.loplex.dogvision.packaging.windowsRuntime
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Compose Multiplatform, for Linux and Windows: gui-core's picture of a photo, a video or
// the camera, with ui's controls beside it. Its main is the command line's as well, so that a photo given alone is
// converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("cz.loplex.dogvision.packaging")
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.desktop.MainKt"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":gui-core"))
            implementation(project(":ui"))
            implementation(libs.compose.multiplatform.desktop)
            implementation(libs.compose.multiplatform.material3)
            // This machine's natives, which the run and packageUberJarForCurrentOS take.
            runtimeOnly(compose.desktop.currentOs)
            for (natives in glNatives()) runtimeOnly(natives)
        }
    }
}

// What runs the window on Windows on x86-64 in place of this machine's natives: Compose's for it, before LWJGL's and
// ANGLE. Only windowsUberJar takes it.
windowsRuntime(listOf(libs.compose.multiplatform.desktop.windows.x64))

/** The icons, the second launcher's properties and what else the packages take. */
val packaging = layout.projectDirectory.dir("packaging")

/**
 * The JDK the tar.gz's runtime is linked from and jpackage runs from: Temurin, which brings its own libjpeg, giflib,
 * libpng, lcms2, HarfBuzz and FreeType, where a distribution's OpenJDK, Ubuntu's among them, links the system's, and
 * the tar.gz would then need that distribution's. Gradle downloads it where this machine has none.
 */
val packagingJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}

// packageUberJarForCurrentOS writes build/compose/jars/dog-vision-linux-x64-<version>.jar, which runs alone on a JDK
// 17 or newer, with this machine's natives in it. createDistributable writes jpackage's app image, with a runtime of
// its own, which packageTarGz packs; Windows's MSI is tools/package_msi_on_linux.sh's.
compose.desktop {
    application {
        mainClass = mainClassName
        javaHome = packagingJdk.get().metadata.installationPath.asFile.path
        nativeDistributions {
            packageName = "dog-vision"
            packageVersion = providers.gradleProperty("appVersion").get()
            description = "How a dog or another animal sees a photo, a video or the camera"
            vendor = "Martin Lopatář"
            // Beyond the modules Compose always takes: what suggestRuntimeModules finds the classes using.
            modules("java.instrument", "jdk.unsupported")
            linux {
                iconFile = packaging.file("dog-vision.png")
            }
        }
    }
}

// dog-vision-cli beside dog-vision in the app image: Compose runs jpackage for it from the JARs.
tasks.withType<AbstractJPackageTask>().configureEach {
    val launcher = packaging.file("dog-vision-cli.properties")
    freeArgs.addAll("--add-launcher", "dog-vision-cli=${launcher.asFile}")
    // freeArgs holds only its path, so that the app image is made again when the file changes.
    inputs.file(launcher)
}

// The deb and the rpm, dog-vision, on the system's Java: the window's JARs in /usr/share/dog-vision/lib, the natives
// they load unpacked in /usr/lib/dog-vision, where the FHS puts what depends on the architecture, a launcher in
// /usr/bin, which finds a Java 17 or newer, and the desktop entry and icons. The command line is the package
// dog-vision-cli, which each recommends.
val linuxPackage = "dog-vision"
val linuxHome = "/usr/share/$linuxPackage"
val linuxNativesHome = "/usr/lib/$linuxPackage"
val linuxJars = files(tasks.named<Jar>("jvmJar"), configurations.named("jvmRuntimeClasspath"))

/** The names of the JARs in /usr/share/dog-vision/lib that are not their own, as [installedJarNames] gives them. */
val linuxJarNames = configurations.named("jvmRuntimeClasspath")
    .flatMap { it.incoming.artifacts.resolvedArtifacts }
    .map(::installedJarNames)

/** The desktop entry's and the icons' name: the application's ID, which the Windows MSI does not use. */
val applicationId = "cz.loplex.dogvision"

// Every native library in the JARs, which skiko and LWJGL would otherwise unpack at run time into the user's home or
// /tmp: skiko's, and LWJGL's for this machine.
val linuxNatives = tasks.register<UnpackNatives>("linuxNatives") {
    description = "Unpacks skiko's and LWJGL's Linux natives out of the JARs into build/packages/natives, for the " +
        "deb and the rpm to install in /usr/lib/dog-vision."
    jars.from(linuxJars)
    natives = layout.buildDirectory.dir("packages/natives")
}

val linuxLauncher = tasks.register<JavaLauncher>("linuxLauncher") {
    description = "Writes build/packages/launcher/dog-vision, the script the deb and the rpm install in /usr/bin, " +
        "which starts the window on the system's Java 17 or newer, not a headless one."
    commandName = linuxPackage
    mainClass = mainClassName
    jars.from(linuxJars)
    jarDirectory = "$linuxHome/lib"
    jarNames = linuxJarNames
    // As jpackage's launcher passes them, but for the resources folder, which the window has no use for.
    jvmOptions = listOf(
        "-Dcompose.application.configure.swing.globals=true",
        "-Dskiko.library.path=$linuxNativesHome",
        "-Dorg.lwjgl.librarypath=$linuxNativesHome",
    )
    minimumJava = 17
    opensWindow = true
    script = layout.buildDirectory.file("packages/launcher/$linuxPackage")
}

val linuxDesktopEntry = tasks.register<DesktopEntry>("linuxDesktopEntry") {
    description = "Writes build/packages/cz.loplex.dogvision.desktop, the desktop entry that puts the window in the " +
        "desktop's menu, named in each language of texts/strings."
    strings = rootProject.layout.projectDirectory.dir("texts/strings")
    nameString = "app_name"
    commentString = "desktop_comment"
    exec = linuxPackage
    icon = applicationId
    categories = listOf("Graphics")
    // Java names each window's WM_CLASS after the class its main is in, the dots as dashes.
    startupWmClass = mainClassName.replace('.', '-')
    entry = layout.buildDirectory.file("packages/$applicationId.desktop")
}

val linuxTree = tasks.register<Sync>("linuxTree") {
    description = "Lays out in build/packages/tree the files the deb and the rpm install: the JARs, the natives, the " +
        "launcher, the desktop entry and the icons."
    into(layout.buildDirectory.dir("packages/tree"))
    from(linuxJars) {
        into(linuxHome.removePrefix("/") + "/lib")
        exclude(NativesOnly)
        renameJars(linuxJarNames)
    }
    from(linuxNatives) { into(linuxNativesHome.removePrefix("/")) }
    from(linuxLauncher) { into("usr/bin") }
    from(linuxDesktopEntry) { into("usr/share/applications") }
    from(packaging.file("dog-vision.png")) {
        into("usr/share/icons/hicolor/256x256/apps")
        rename("dog-vision.png", "$applicationId.png")
    }
    from(packaging.file("dog-vision.svg")) {
        into("usr/share/icons/hicolor/scalable/apps")
        rename("dog-vision.svg", "$applicationId.svg")
    }
}

val rpmLibraryRequires = tasks.register<RpmLibraryRequires>("rpmLibraryRequires") {
    description = "Lists in build/packages/rpmLibraryRequires.txt the libraries the natives link against and do not " +
        "bring themselves, which the rpm requires."
    image = linuxNatives.flatMap { it.natives }
    requires = layout.buildDirectory.file("packages/rpmLibraryRequires.txt")
}

val debDepends = tasks.register<DebDepends>("debDepends") {
    description = "Writes the deb's Depends into build/packages/debDepends.txt: the packages of the libraries the " +
        "natives link against and do not bring, then a Java that can open a window, libEGL and ffmpeg."
    image = linuxNatives.flatMap { it.natives }
    packages = debianPackages
    // What the natives do not name: a Java that can open a window, the distribution's default where it is 17 or
    // newer, as the Debian Java Policy has it; LWJGL opens libEGL once it runs, and ffmpeg runs apart for a video or
    // the camera.
    others = listOf("default-jre (>= 2:1.17) | java17-runtime", "libegl1", "ffmpeg")
    depends = layout.buildDirectory.file("packages/debDepends.txt")
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val linuxSummary = "How a dog or another animal sees colours (Kotlin Compose GUI)"
val linuxDescription = """
    dog-vision shows a photo, a video or the camera with the colours a dog,
    a cat or another animal can tell apart, beside the original.

    This package is the desktop window, in Compose.

    Given a photo alone, it converts it as the command line does, which is
    the package dog-vision-cli.
""".trimIndent()

tasks.register<DebPackage>("packageDeb") {
    description = "Packs build/packages/deb/dog-vision_<version>_amd64.deb, on the system's Java."
    group = "distribution"
    tree = layout.dir(linuxTree.map { it.destinationDir })
    packageName = linuxPackage
    architecture = "amd64"
    summary = linuxSummary
    longDescription = linuxDescription
    depends = debDepends.flatMap { it.depends }.map { it.asFile.readText().split(", ") }
    recommends = listOf("dog-vision-cli")
}

tasks.register<RpmPackage>("packageRpm") {
    description = "Packs build/packages/rpm/dog-vision-<version>-1.x86_64.rpm, on the system's Java."
    group = "distribution"
    tree = layout.dir(linuxTree.map { it.destinationDir })
    packageName = linuxPackage
    architecture = "x86_64"
    summary = linuxSummary
    longDescription = linuxDescription
    // The libraries the natives need, as rpm's own generator names them, since Fedora's and openSUSE's package names
    // differ; a Java that can open a window, as cli's rpm says why; libEGL, which LWJGL opens once it runs; and
    // ffmpeg's command rather than a package, as Fedora has two ffmpeg packages.
    requires = rpmLibraryRequires.flatMap { it.requires }.map { file ->
        file.asFile.readText().split(',') +
            listOf("/bin/sh", "(jre-17 or jre-21 or jre-25)", "libEGL.so.1()(64bit)", "/usr/bin/ffmpeg")
    }
    recommends = listOf("dog-vision-cli")
}

// The app image as it is, to unpack and run anywhere on Linux on x86-64 without installing it.
tasks.register<Tar>("packageTarGz") {
    description = "Packs the app image into build/compose/binaries/main/tar/dog-vision-<version>-linux-x64.tar.gz."
    group = "compose desktop"
    val version = compose.desktop.application.nativeDistributions.packageVersion
    archiveFileName = "dog-vision-$version-linux-x64.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("compose/binaries/main/tar")
    compression = Compression.GZIP
    // The launchers and the runtime's jspawnhelper, which starts ffmpeg, stay executable: Gradle's archives otherwise
    // give every file the same mode.
    eachFile { permissions { unix(if (file.canExecute()) "755" else "644") } }
    from(tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir })
}

// Built on any machine, as jpackage's installers are not: Windows's natives and ANGLE in place of this machine's.
tasks.register<Jar>("windowsUberJar") {
    description = "Assembles build/compose/jars/dog-vision-windows-x64-<version>.jar, the window for Windows."
    group = "compose desktop"
    archiveFileName = "dog-vision-windows-x64-${compose.desktop.application.nativeDistributions.packageVersion}.jar"
    destinationDirectory = layout.buildDirectory.dir("compose/jars")
    manifest { attributes("Main-Class" to mainClassName) }
    from(tasks.named<Jar>("jvmJar").map { zipTree(it.archiveFile) })
    from(configurations.named("windowsRuntime").map { classpath -> classpath.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val excludes = listOf(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "**/module-info.class",
        // ANGLE for Windows on ARM, which LWJGL's and skiko's natives here are not for.
        "nucleus/native/win32-aarch64/**",
    )
    exclude(excludes)
    // Gradle fingerprints the zip files, not what the patterns leave of them, and would otherwise keep the JAR as it is
    // when a pattern changes.
    inputs.property("excludes", excludes)
}
