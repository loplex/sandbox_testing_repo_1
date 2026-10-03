import cz.loplex.dogvision.packaging.DebPackage
import cz.loplex.dogvision.packaging.JavaLauncher
import cz.loplex.dogvision.packaging.RpmPackage
import cz.loplex.dogvision.packaging.artifact
import cz.loplex.dogvision.packaging.uberJar
import cz.loplex.dogvision.packaging.windowsAppImage
import cz.loplex.dogvision.packaging.windowsLauncher
import cz.loplex.dogvision.packaging.windowsRuntimeImage
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The command line on the JVM, as the Python program has it: its options, a photo read as it is meant to be seen, and
// the photo converted at full size by core. It needs no toolkit and no GPU, so that it runs alone as well as through
// the desktop window's own main, which reads the same options.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    id("cz.loplex.dogvision.packaging")
}

/** The class whose main the command line and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.cli.MainKt"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
        }
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        binaries {
            executable {
                mainClass = mainClassName
                applicationName = "dog-vision-cli"
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(project(":texts"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(project.dependencies.platform(libs.junit.bom))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

/** The documents' figures, which core renders from the photo beside them. */
val figures = rootProject.layout.projectDirectory.dir("docs/images")

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
    inputs.dir(figures).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("dogvision.figures", figures.asFile.absolutePath)
}

tasks.register<JavaExec>("renderFigures") {
    description = "Renders the documents' figures in docs/images from the apple photo there."
    group = "documentation"
    val test = kotlin.jvm().compilations.getByName("test")
    classpath = files(test.output.allOutputs, test.runtimeDependencyFiles)
    mainClass = "cz.loplex.dogvision.cli.FiguresKt"
    args(figures.asFile.absolutePath)
}

// One JAR with core and the Kotlin standard library in it, which `java -jar` runs alone on a JDK 17 or newer.
val uberJar = tasks.register<Jar>("uberJar") {
    description = "Assembles build/jars/dog-vision-cli.jar, the command line with everything it needs."
    group = "distribution"
    destinationDirectory = layout.buildDirectory.dir("jars")
    uberJar(
        "dog-vision-cli.jar",
        mainClassName,
        tasks.named<Jar>("jvmJar").flatMap { it.archiveFile },
        configurations.named("jvmRuntimeClasspath"),
    )
}
artifact(uberJar)

/**
 * The modules of the runtime on Windows: what jdeps --print-module-deps finds the JAR using; ImageIO, which reads and
 * writes the photos, is in java.desktop.
 */
val windowsModules = listOf("java.base", "java.desktop")

// dog-vision-cli.exe, in a console, on the JARs uberJar merges: in the MSI beside the windows, which :packaging takes,
// and alone in the zip.
val windowsLauncher = windowsLauncher(
    name = "dog-vision-cli",
    ownJar = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile },
    classpath = configurations.named("jvmRuntimeClasspath"),
    mainClass = mainClassName,
    console = true,
    runtimeModules = windowsModules,
)

// The deb and the rpm, dog-vision-cli, on the system's Java: its JARs in /usr/share/dog-vision-cli/lib and a launcher
// in /usr/bin, which finds a Java 17 or newer. It has no natives, so one package serves every architecture, and it
// needs a headless Java only, as it opens no window.
val linuxPackage = "dog-vision-cli"
val linuxHome = "/usr/share/$linuxPackage"
val linuxJars = files(tasks.named<Jar>("jvmJar"), configurations.named("jvmRuntimeClasspath"))

val linuxLauncher = tasks.register<JavaLauncher>("linuxLauncher") {
    description = "Writes build/packages/launcher/dog-vision-cli, the script the deb and the rpm install in " +
        "/usr/bin, which starts the command line on the system's Java 17 or newer."
    commandName = linuxPackage
    mainClass = mainClassName
    jars.from(linuxJars)
    jarDirectory = "$linuxHome/lib"
    jvmOptions = emptyList()
    minimumJava = 17
    script = layout.buildDirectory.file("packages/launcher/$linuxPackage")
}

val linuxTree = tasks.register<Sync>("linuxTree") {
    description = "Lays out in build/packages/tree the files the deb and the rpm install: the JARs and the launcher."
    into(layout.buildDirectory.dir("packages/tree"))
    from(linuxJars) { into(linuxHome.removePrefix("/") + "/lib") }
    from(linuxLauncher) { into("usr/bin") }
}

/** What the deb and the rpm are listed with, in a package manager's search and its details. */
val linuxSummary = "How a dog or another animal sees a photo, from the command line"
val linuxDescription = """
    Converts a photo to the colours a dog, a cat or another animal can tell
    apart, as a PNG beside it, with no window and no display.

    The desktop window, which shows a video or the camera as well, is the
    package dog-vision.
""".trimIndent()

val packageDeb = tasks.register<DebPackage>("packageDeb") {
    description = "Packs build/packages/deb/dog-vision-cli_<version>_all.deb, on the system's Java."
    group = "distribution"
    tree = layout.dir(linuxTree.map { it.destinationDir })
    packageName = linuxPackage
    architecture = "all"
    summary = linuxSummary
    longDescription = linuxDescription
    // The distribution's default JRE where it is 17 or newer, as the Debian Java Policy has it, and any JRE that
    // provides java17-runtime-headless where it is not: Ubuntu 20.04's and 22.04's are 11.
    depends = listOf("default-jre-headless (>= 2:1.17) | java17-runtime-headless")
    recommends = emptyList()
}
artifact(packageDeb)

val packageRpm = tasks.register<RpmPackage>("packageRpm") {
    description = "Packs build/packages/rpm/dog-vision-cli-<version>-1.noarch.rpm, on the system's Java."
    group = "distribution"
    tree = layout.dir(linuxTree.map { it.destinationDir })
    packageName = linuxPackage
    architecture = "noarch"
    summary = linuxSummary
    longDescription = linuxDescription
    // No one name that every rpm JRE of 17 or newer provides, and no older one: jre-headless >= 17 would take Fedora's
    // and Rocky's Java 8, which provides it at epoch 1 and so above any version at epoch 0, and Temurin's 8 and 11,
    // which provide it with no version at all. A new LTS joins the list when a distribution makes it its default.
    requires = listOf("/bin/sh", "(jre-17-headless or jre-21-headless or jre-25-headless)")
    recommends = emptyList()
}
artifact(packageRpm)

// What jpackage makes the app image of the command line's zip for Windows on x86-64 of, as the scripts in tools hand
// it: the command line alone, on a runtime of its own.
windowsAppImage(
    packageName = "dog-vision-cli",
    description = "How a dog or another animal sees a photo, from the command line",
    launchers = files(windowsLauncher.flatMap { it.destination }),
    runtime = windowsRuntimeImage("the command line's runtime for Windows", windowsModules),
)
