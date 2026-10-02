import cz.loplex.dogvision.packaging.artifact
import cz.loplex.dogvision.packaging.glNatives
import cz.loplex.dogvision.packaging.packagingJdk
import cz.loplex.dogvision.packaging.windowPackages
import cz.loplex.dogvision.packaging.windowsRuntime
import cz.loplex.dogvision.packaging.windowsRuntimeImage
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

/** The JDK the tar.gz's runtime is linked from and jpackage runs from, Temurin, which build-logic says why. */
val packagingJdk = packagingJdk()

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

// The MSI's runtime, of the modules the app image's has: Compose's own and those added above.
windowsRuntimeImage("the window's runtime for Windows", compose.desktop.application.nativeDistributions.modules)

// The JAR with this machine's natives, whose task Compose's plugin registers.
artifact("packageUberJarForCurrentOS")

// dog-vision-cli beside dog-vision in the app image: Compose runs jpackage for it from the JARs.
tasks.withType<AbstractJPackageTask>().configureEach {
    val launcher = packaging.file("dog-vision-cli.properties")
    freeArgs.addAll("--add-launcher", "dog-vision-cli=${launcher.asFile}")
    // freeArgs holds only its path, so that the app image is made again when the file changes.
    inputs.file(launcher)
}

// The deb and the rpm, dog-vision, on the system's Java.
windowPackages(
    packageName = "dog-vision",
    // The application's ID, which the Windows MSI does not use.
    applicationId = "cz.loplex.dogvision",
    mainClass = mainClassName,
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

// The app image as it is, to unpack and run anywhere on Linux on x86-64 without installing it.
val packageTarGz = tasks.register<Tar>("packageTarGz") {
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
artifact(packageTarGz)

// Built on any machine, as jpackage's installers are not: Windows's natives and ANGLE in place of this machine's.
val windowsUberJar = tasks.register<Jar>("windowsUberJar") {
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
artifact(windowsUberJar)
