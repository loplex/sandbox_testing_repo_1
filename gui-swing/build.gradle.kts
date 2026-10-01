import cz.loplex.dogvision.packaging.AppImage
import cz.loplex.dogvision.packaging.glNatives
import cz.loplex.dogvision.packaging.windowPackages
import cz.loplex.dogvision.packaging.windowsRuntime
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Swing, with FlatLaf, for Linux and Windows: gui-core's picture of a photo, a video or the
// camera, with Swing's own controls beside it, as the Compose window has them, and without Compose or skiko. Its main
// is the command line's as well, so that a photo given alone is converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("cz.loplex.dogvision.packaging")
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.swing.MainKt"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass = mainClassName
                applicationName = "dog-vision-swing"
            }
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":gui-core"))
            implementation(libs.flatlaf)
            implementation(libs.kotlinx.coroutines.swing)
            // This machine's natives, which the run and linuxUberJar take.
            for (natives in glNatives()) runtimeOnly(natives)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// What runs the window on Windows on x86-64 in place of this machine's natives: LWJGL's for it, and ANGLE. Only
// windowsUberJar takes it.
windowsRuntime()

/** The packages' version, as the other modules' packages have it. */
val packageVersion = providers.gradleProperty("appVersion")

/** The Compose window's icons and its second launcher's properties, which this window's packages take as they are. */
val packaging = rootProject.layout.projectDirectory.dir("gui-compose/packaging")

/** The JARs the window runs from: its own, and everything it needs, this machine's natives among them. */
val runtimeJars = files(tasks.named<Jar>("jvmJar"), configurations.named("jvmRuntimeClasspath"))

/** An uber JAR of [classpath]'s JARs with the window's own, which runs alone on a JDK 17 or newer. */
fun Jar.uberJar(fileName: String, classpath: Provider<out Iterable<File>>) {
    archiveFileName = fileName
    destinationDirectory = layout.buildDirectory.dir("jars")
    manifest { attributes("Main-Class" to mainClassName) }
    from(tasks.named<Jar>("jvmJar").map { zipTree(it.archiveFile) })
    from(classpath.map { jars -> jars.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    val excludes = listOf("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
    exclude(excludes)
    inputs.property("excludes", excludes)
}

tasks.register<Jar>("linuxUberJar") {
    description = "Assembles build/jars/dog-vision-swing-linux-x64-<version>.jar, the window for this machine."
    group = "distribution"
    uberJar("dog-vision-swing-linux-x64-${packageVersion.get()}.jar", configurations.named("jvmRuntimeClasspath"))
}

// Built on any machine: Windows's natives and ANGLE in place of this machine's.
tasks.register<Jar>("windowsUberJar") {
    description = "Assembles build/jars/dog-vision-swing-windows-x64-<version>.jar, the window for Windows."
    group = "distribution"
    uberJar("dog-vision-swing-windows-x64-${packageVersion.get()}.jar", configurations.named("windowsRuntime"))
    // ANGLE for Windows on ARM, which LWJGL's natives here are not for.
    exclude("nucleus/native/win32-aarch64/**")
}

/**
 * The JDK the tar.gz's runtime is linked from and jpackage runs from: Temurin, as the Compose window's build says why.
 * Gradle downloads it where this machine has none.
 */
val packagingJdk = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(25)
    vendor = JvmVendorSpec.ADOPTIUM
}

// jpackage's app image with a runtime of its own, and dog-vision-cli beside dog-vision-swing, as the Compose window's.
val appImage = tasks.register<AppImage>("appImage") {
    description = "Makes build/app-image/dog-vision-swing, jpackage's app image with a runtime of its own."
    group = "distribution"
    jdkHome = packagingJdk.map { it.metadata.installationPath.asFile.path }
    jars.from(runtimeJars)
    mainJar = tasks.named<Jar>("jvmJar").flatMap { it.archiveFileName }
    mainClass = mainClassName
    imageName = "dog-vision-swing"
    appVersion = packageVersion
    // What jdeps --print-module-deps finds the JARs using.
    modules = listOf("java.base", "java.desktop", "java.instrument", "jdk.unsupported")
    javaOptions = emptyList()
    icon = packaging.file("dog-vision.png")
    launchers = mapOf("dog-vision-cli" to packaging.file("dog-vision-cli.properties").asFile)
    destination = layout.buildDirectory.dir("app-image")
    input = layout.buildDirectory.dir("app-image-input")
}

// The app image as it is, to unpack and run anywhere on Linux on x86-64 without installing it.
tasks.register<Tar>("packageTarGz") {
    description = "Packs the app image into build/packages/tar/dog-vision-swing-<version>-linux-x64.tar.gz."
    group = "distribution"
    archiveFileName = "dog-vision-swing-${packageVersion.get()}-linux-x64.tar.gz"
    destinationDirectory = layout.buildDirectory.dir("packages/tar")
    compression = Compression.GZIP
    // The launchers and the runtime's jspawnhelper, which starts ffmpeg, stay executable.
    eachFile { permissions { unix(if (file.canExecute()) "755" else "644") } }
    from(appImage.flatMap { it.destination })
}

// The deb and the rpm, dog-vision-swing, on the system's Java, as the Compose window's dog-vision, with the desktop
// entry and icons under names of their own, so that both windows install side by side.
windowPackages(
    packageName = "dog-vision-swing",
    // Not the Compose window's cz.loplex.dogvision.
    applicationId = "cz.loplex.dogvision.swing",
    mainClass = mainClassName,
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
