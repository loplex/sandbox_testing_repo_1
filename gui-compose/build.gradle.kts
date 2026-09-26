import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Compose Multiplatform, for Linux and Windows: a photo, a video or the camera through ffmpeg,
// rendered by gl's passes over OpenGL ES 3 in an offscreen EGL context, with ui's controls beside it. Its main is the
// command line's as well, so that a photo given alone is converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.desktop.MainKt"

/** LWJGL's native part for this machine: Linux on x86-64's. */
val lwjglNatives = "natives-linux"

/** The LWJGL modules with a native part. */
val lwjglWithNatives = listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengles)

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
            implementation(libs.lwjgl.opengles)
            // This machine's natives, which the run, the tests and packageUberJarForCurrentOS take.
            runtimeOnly(compose.desktop.currentOs)
            for (library in lwjglWithNatives) {
                runtimeOnly("${library.get().module}:${libs.versions.lwjgl.get()}:$lwjglNatives")
            }
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
    for (library in lwjglWithNatives) {
        windowsRuntime("${library.get().module}:${libs.versions.lwjgl.get()}:natives-windows")
    }
    windowsRuntime(libs.nucleus.angle.natives)
}

// packageUberJarForCurrentOS writes build/compose/jars/dog-vision-linux-x64-<version>.jar, which runs alone on a JDK
// 17 or newer, with this machine's natives in it.
compose.desktop {
    application {
        mainClass = mainClassName
        nativeDistributions {
            packageName = "dog-vision"
            packageVersion = "0.1.0" // the Android app's versionName
        }
    }
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
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "**/module-info.class")
    // ANGLE for Windows on ARM, which LWJGL's and skiko's natives here are not for.
    exclude("nucleus/native/win32-aarch64/**")
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
