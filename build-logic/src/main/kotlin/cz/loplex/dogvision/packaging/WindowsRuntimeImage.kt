package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RelativePath
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

/**
 * Temurin's release the packages' runtimes come from, temurin-windows-jmods in libs.versions.toml: the jmods for
 * Windows are of it exactly, and [packagingJdk] of its feature release.
 */
private fun Project.temurinRelease(): String = libs().findVersion("temurin-windows-jmods").get().requiredVersion

/**
 * The JDK the packages' runtimes are linked with and jpackage runs from on Linux: Temurin, of [temurinRelease]'s
 * feature release, which jlink asks the jmods it links for Windows to be of. Temurin brings its own libjpeg, giflib,
 * libpng, lcms2, HarfBuzz and FreeType, where a distribution's OpenJDK, Ubuntu's among them, links the system's, and a
 * tar.gz would then need that distribution's. Gradle downloads it where this machine has none.
 */
fun Project.packagingJdk(): Provider<JavaLauncher> =
    extensions.getByType(JavaToolchainService::class.java).launcherFor {
        languageVersion.set(JavaLanguageVersion.of(temurinRelease().substringBefore('.').toInt()))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

/**
 * Registers windowsRuntime, which links in build/windows/runtime [what], the runtime for Windows on x86-64 that the
 * scripts in tools hand jpackage: [modules] and those [moduleLists] name alone, with [packagingJdk]'s jlink, from
 * Temurin's jmods for Windows of [temurinRelease], which windowsJmods unpacks into build/windows/jmods. So it is the
 * same runtime on Windows and on Linux, where jpackage runs under Wine, whatever JDK either runs on.
 */
fun Project.windowsRuntimeImage(
    what: String,
    modules: List<String>,
    moduleLists: FileCollection = files(),
): TaskProvider<RuntimeImage> {
    val release = temurinRelease()
    val jmodsScope = configurations.dependencyScope("windowsJmods")
    val jmodsZip = configurations.resolvable("windowsJmodsZip") { extendsFrom(jmodsScope.get()) }
    dependencies.addProvider<MinimalExternalModuleDependency, ExternalModuleDependency>(
        jmodsScope.name,
        libs().findLibrary("temurin-windows-jmods").get(),
    ) {
        // Adoptium names the zip after the release, with an underscore for its plus.
        artifact {
            name = "OpenJDK${release.substringBefore('.')}U-jmods_x64_windows_hotspot_${release.replace('+', '_')}"
            type = "zip"
        }
    }

    // The jmods alone, out of the folder the zip holds them in, as jlink's module path takes a folder of them.
    val jmods = tasks.register("windowsJmods", Sync::class.java) {
        description = "Unpacks Temurin's jmods for Windows into build/windows/jmods."
        into(layout.buildDirectory.dir("windows/jmods"))
        from(jmodsZip.map { zips -> zips.map { zipTree(it) } }) { include("*/*.jmod") }
        eachFile { relativePath = RelativePath(true, name) }
        includeEmptyDirs = false
    }

    val runtime = tasks.register("windowsRuntime", RuntimeImage::class.java) {
        description = "Links build/windows/runtime, $what, from Temurin's jmods for Windows."
        group = "distribution"
        jdkHome.set(packagingJdk().map { it.metadata.installationPath.asFile.path })
        this.jmods.set(layout.dir(jmods.map { it.destinationDir }))
        this.modules.set(modules)
        this.moduleLists.from(moduleLists)
        destination.set(layout.buildDirectory.dir("windows/runtime"))
    }
    return runtime
}
