import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The Linux desktop window in Compose Multiplatform: a photo, a video or the camera through ffmpeg, rendered by gl's
// passes over OpenGL ES 3 in an offscreen EGL context, with ui's controls beside it. Its main is the command line's
// as well, so that a photo given alone is converted as cli converts it.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

/** LWJGL's native part for this machine: Linux on x86-64's. */
val lwjglNatives = "natives-linux"

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
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.multiplatform.material3)
            implementation(libs.lwjgl.asProvider())
            implementation(libs.lwjgl.egl)
            implementation(libs.lwjgl.opengles)
            for (library in listOf(libs.lwjgl.asProvider(), libs.lwjgl.opengles)) {
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

// packageUberJarForCurrentOS writes build/compose/jars/dog-vision-linux-x64-<version>.jar, which runs alone on a JDK
// 17 or newer, with this machine's natives in it.
compose.desktop {
    application {
        mainClass = "cz.loplex.dogvision.desktop.MainKt"
        nativeDistributions {
            packageName = "dog-vision"
            packageVersion = "0.1.0" // the Android app's versionName
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}
