import cz.loplex.dogvision.packaging.DebDepends
import cz.loplex.dogvision.packaging.DeleteDirectory
import cz.loplex.dogvision.packaging.RepackDeb
import cz.loplex.dogvision.packaging.RepackRpm
import cz.loplex.dogvision.packaging.RpmLibraryRequires
import cz.loplex.dogvision.packaging.debianPackages
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Compose Multiplatform, for Linux and Windows: a photo, a video or the camera through ffmpeg,
// rendered by gl's passes in an offscreen GL context, with ui's controls beside it. Its main is the command line's as
// well, so that a photo given alone is converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    id("cz.loplex.dogvision.packaging")
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.desktop.MainKt"

/** Whether this machine is Windows, where the run and the tests draw through ANGLE and WGL, as the window does. */
val onWindows = System.getProperty("os.name").startsWith("Windows")

/** LWJGL's native part for this machine: Linux's or Windows's, on x86-64. */
val lwjglNatives = if (onWindows) "natives-windows" else "natives-linux"

/** The LWJGL modules with a native part on Linux: desktop GL's is for the tests of LwjglGl through EGL. */
val lwjglWithLinuxNatives = listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengles, libs.lwjgl.opengl)

/** The LWJGL modules with a native part on Windows, where GLFW makes the WGL context. */
val lwjglWithWindowsNatives = lwjglWithLinuxNatives + libs.lwjgl.glfw

/** The LWJGL modules with a native part on this machine. */
val lwjglWithNatives = if (onWindows) lwjglWithWindowsNatives else lwjglWithLinuxNatives

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":cli"))
            implementation(project(":gl"))
            implementation(project(":ui"))
            implementation(libs.compose.multiplatform.desktop)
            implementation(libs.compose.multiplatform.material3)
            implementation(libs.lwjgl.asProvider())
            implementation(libs.lwjgl.egl)
            implementation(libs.lwjgl.glfw)
            implementation(libs.lwjgl.opengl)
            implementation(libs.lwjgl.opengles)
            // This machine's natives, which the run, the tests and packageUberJarForCurrentOS take.
            runtimeOnly(compose.desktop.currentOs)
            for (library in lwjglWithNatives) {
                runtimeOnly("${library.get().module}:${libs.versions.lwjgl.get()}:$lwjglNatives")
            }
            if (onWindows) runtimeOnly(libs.nucleus.angle.natives)
        }
        jvmTest.dependencies {
            implementation(project(":testing"))
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

/**
 * What runs the window on Windows on x86-64 in place of this machine's natives: Compose's and LWJGL's natives for it,
 * and ANGLE, OpenGL ES over Direct3D 11, which Windows has no EGL for. Only windowsUberJar takes it.
 */
val windowsRuntime: Configuration = configurations.create("windowsRuntime") {
    isCanBeConsumed = false
    extendsFrom(configurations["commonMainImplementation"], configurations["jvmMainImplementation"])
    // The attributes the JVM target resolves with, so that the other modules give their JVM variants.
    val jvmAttributes = configurations["jvmRuntimeClasspath"].attributes
    attributes {
        for (key in jvmAttributes.keySet()) copyAttribute(key, jvmAttributes)
    }
}

fun <T : Any> AttributeContainer.copyAttribute(key: Attribute<T>, from: AttributeContainer) {
    attribute(key, checkNotNull(from.getAttribute(key)))
}

dependencies {
    windowsRuntime(libs.compose.multiplatform.desktop.windows.x64)
    for (library in lwjglWithWindowsNatives) {
        windowsRuntime("${library.get().module}:${libs.versions.lwjgl.get()}:natives-windows")
    }
    windowsRuntime(libs.nucleus.angle.natives)
}

/** The icons, the second launcher's properties and what else the packages take. */
val packaging = layout.projectDirectory.dir("packaging")

/**
 * The JDK the Linux packages' runtime is linked from and jpackage runs from: Temurin, which brings its own libjpeg,
 * giflib, libpng, lcms2, HarfBuzz and FreeType, where a distribution's OpenJDK, Ubuntu's among them, links the
 * system's, and the packages would then need that distribution's. Gradle downloads it where this machine has none.
 */
val packagingJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}

// packageUberJarForCurrentOS writes build/compose/jars/dog-vision-linux-x64-<version>.jar, which runs alone on a JDK
// 17 or newer, with this machine's natives in it. packageDeb and packageRpm write jpackage's packages for Linux, with a
// runtime of their own, under build/compose/binaries/main/{deb,rpm}; Windows's MSI is tools/package_msi_on_linux.sh's.
compose.desktop {
    application {
        mainClass = mainClassName
        javaHome = packagingJdk.get().metadata.installationPath.asFile.path
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "dog-vision"
            packageVersion = providers.gradleProperty("appVersion").get()
            description = "How a dog or another animal sees a photo, a video or the camera"
            vendor = "Martin Lopatář"
            licenseFile = rootProject.file("LICENSE")
            // Beyond the modules Compose always takes: what suggestRuntimeModules finds the classes using.
            modules("java.instrument", "jdk.unsupported")
            linux {
                iconFile = packaging.file("dog-vision.png")
                shortcut = true
                menuGroup = "Graphics"
                appCategory = "graphics"
                debMaintainer = "lopin.git@loplex.cz"
                rpmLicenseType = "GPL-3.0-or-later"
            }
        }
    }
}

val rpmLibraryRequires = tasks.register<RpmLibraryRequires>("rpmLibraryRequires") {
    image = tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir }
    requires = layout.buildDirectory.file("compose/tmp/rpmLibraryRequires.txt")
}

val debDepends = tasks.register<DebDepends>("debDepends") {
    image = tasks.named<AbstractJPackageTask>("createDistributable").flatMap { it.destinationDir }
    packages = debianPackages
    // What the image's ELF files do not name: LWJGL opens libEGL once it runs, and ffmpeg runs apart for a video or
    // the camera.
    others = listOf("libegl1", "ffmpeg")
    depends = layout.buildDirectory.file("compose/tmp/debDepends.txt")
}

// dog-vision-cli beside dog-vision, in the app image and in each package: Compose runs jpackage for each of them from
// the JARs, not the packages from the app image.
tasks.withType<AbstractJPackageTask>().configureEach {
    val launcher = packaging.file("dog-vision-cli.properties")
    freeArgs.addAll("--add-launcher", "dog-vision-cli=${launcher.asFile}")
    // freeArgs holds only its path, so that the packages are made again when the file changes.
    inputs.file(launcher)
    // The deb's Depends is debDepends'. The rpm adds what jpackage cannot find through ldd: LWJGL opens libEGL once it
    // runs, and ffmpeg runs apart for a video or the camera. It names what it needs rather than a package, as Fedora's
    // and openSUSE's names differ, and Fedora has two ffmpeg packages; it takes the libraries rpmLibraryRequires lists
    // as well. No spaces: Compose writes freeArgs into jpackage's argument file unquoted.
    when {
        name.endsWith("Deb") -> {
            inputs.files(debDepends)
            val packageName = compose.desktop.application.nativeDistributions.packageName
            doLast(
                RepackDeb(
                    destinationDir,
                    debDepends.flatMap { it.depends },
                    jpackageEntry = "opt/$packageName/lib/$packageName-$packageName.desktop",
                    entry = "usr/share/applications/cz.loplex.dogvision.desktop",
                ),
            )
        }

        name.endsWith("Rpm") -> {
            freeArgs.add("--linux-package-deps")
            val libraries = rpmLibraryRequires.flatMap { it.requires }.map { it.asFile.readText() }
            freeArgs.add(libraries.map { "$it,libEGL.so.1()(64bit),/usr/bin/ffmpeg" })
            // jpackage's --temp, which it wants empty, for RepackRpm to build the rpm again from.
            val temp = layout.buildDirectory.dir("compose/tmp/$name-jpackage")
            freeArgs.add("--temp")
            freeArgs.add(temp.map { it.asFile.path })
            doFirst(DeleteDirectory(temp))
            val packageName = compose.desktop.application.nativeDistributions.packageName
            doLast(
                RepackRpm(
                    destinationDir,
                    temp,
                    jpackageEntry = "opt/$packageName/lib/$packageName-$packageName.desktop",
                    entry = "usr/share/applications/cz.loplex.dogvision.desktop",
                ),
            )
        }
    }
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

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
