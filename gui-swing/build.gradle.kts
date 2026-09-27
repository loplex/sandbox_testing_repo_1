import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The desktop window in Swing, with FlatLaf, for Linux and Windows: gui-core's picture of a photo, a video or the
// camera, with Swing's own controls beside it, as the Compose window has them, and without Compose or skiko. Its main
// is the command line's as well, so that a photo given alone is converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

/** The class whose main the window and its JARs start with. */
val mainClassName = "cz.loplex.dogvision.swing.MainKt"

/** Whether this machine is Windows, where the run draws through ANGLE and WGL, as the window does. */
val onWindows = System.getProperty("os.name").startsWith("Windows")

/** LWJGL's native part for this machine: Linux's or Windows's, on x86-64. */
val lwjglNatives = if (onWindows) "natives-windows" else "natives-linux"

/** The LWJGL modules with a native part on this machine: on Windows, GLFW makes the WGL context. */
val lwjglWithNatives = listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengles, libs.lwjgl.opengl) +
    if (onWindows) listOf(libs.lwjgl.glfw) else emptyList()

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
            // This machine's natives, which the run takes.
            for (library in lwjglWithNatives) {
                runtimeOnly("${library.get().module}:${libs.versions.lwjgl.get()}:$lwjglNatives")
            }
            if (onWindows) runtimeOnly(libs.nucleus.angle.natives)
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
