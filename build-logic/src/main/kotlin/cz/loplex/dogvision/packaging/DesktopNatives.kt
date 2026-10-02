package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeContainer

/** Whether this machine is Windows, where the windows and desktop-core's tests draw through ANGLE and WGL. */
val onWindows = System.getProperty("os.name").startsWith("Windows")

/**
 * The native parts a window, or desktop-core's tests, run with on Windows on x86-64 when [windows], and on Linux on
 * x86-64 otherwise: LWJGL's for its core, OpenGL ES and desktop GL, whose is for LwjglGl through EGL on Linux; on
 * Windows GLFW's as well, which makes the WGL context, and ANGLE, OpenGL ES over Direct3D 11, which Windows has no EGL
 * for.
 */
fun Project.glNatives(windows: Boolean = onWindows): List<Any> {
    val libs = libs()
    val lwjgl = libs.findVersion("lwjgl").get().requiredVersion
    val modules = listOf("lwjgl", "lwjgl-opengles", "lwjgl-opengl") + if (windows) listOf("lwjgl-glfw") else emptyList()
    val classifier = if (windows) "natives-windows" else "natives-linux"
    val lwjglNatives = modules.map { "${libs.findLibrary(it).get().get().module}:$lwjgl:$classifier" }
    return lwjglNatives + if (windows) listOf(libs.findLibrary("nucleus-angle-natives").get()) else emptyList()
}

/**
 * Creates windowsRuntime, what runs a window on Windows on x86-64 in place of this machine's natives: the window's
 * dependencies with [toolkit], its toolkit's natives for Windows, and [glNatives] for Windows. Only the window's
 * windowsUberJar takes it.
 */
fun Project.windowsRuntime(toolkit: List<Any> = emptyList()): Configuration {
    val windowsRuntime = configurations.create("windowsRuntime") {
        isCanBeConsumed = false
        extendsFrom(
            configurations.getByName("commonMainImplementation"),
            configurations.getByName("jvmMainImplementation"),
        )
        // The attributes the JVM target resolves with, so that the other modules give their JVM variants.
        val jvmAttributes = configurations.getByName("jvmRuntimeClasspath").attributes
        attributes {
            for (key in jvmAttributes.keySet()) copyAttribute(key, jvmAttributes)
        }
    }
    for (natives in toolkit + glNatives(windows = true)) dependencies.add(windowsRuntime.name, natives)
    return windowsRuntime
}

private fun <T : Any> AttributeContainer.copyAttribute(key: Attribute<T>, from: AttributeContainer) {
    attribute(key, checkNotNull(from.getAttribute(key)))
}

/** The build's version catalog, libs.versions.toml. */
internal fun Project.libs(): VersionCatalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
