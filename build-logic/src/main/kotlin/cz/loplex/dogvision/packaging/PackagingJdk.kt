package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JvmVendorSpec

/** Temurin's feature release the packages' runtimes are of, temurin in libs.versions.toml. */
internal fun Project.temurinFeature(): Int = libs().findVersion("temurin").get().requiredVersion.toInt()

/**
 * The JDK that links the packages' runtimes, and that jpackage runs from on Linux:
 * Temurin, of [temurinFeature]; jlink requires the Windows jmods it links to be of its own feature release.\
 * Temurin brings its own libjpeg, giflib, libpng, lcms2, HarfBuzz and FreeType,
 * whereas a distribution's OpenJDK, Ubuntu's among them, links the system's,
 * and a tar.gz would then need that distribution's.\
 * Gradle downloads it if this machine has none.
 */
fun Project.packagingJdk(): Provider<JavaLauncher> =
    extensions.getByType(JavaToolchainService::class.java).launcherFor {
        languageVersion.set(JavaLanguageVersion.of(temurinFeature()))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }

/**
 * The Temurin release that [packagingJdk] is, such as 25.0.4.1+1, as the JDK's release file gives it:
 * the Windows jmods are of exactly this release,
 * so that every package's runtime is the same and is updated along with the JDK the build packages with.
 */
fun Project.temurinRelease(): Provider<String> = packagingJdk().map { jdk ->
    val release = jdk.metadata.installationPath.file("release").asFile
    val implementor = release.readLines().firstOrNull { it.startsWith("IMPLEMENTOR_VERSION=") }
    checkNotNull(implementor?.substringAfter("\"Temurin-", "")?.removeSuffix("\"")?.ifEmpty { null }) {
        "$release names no Temurin release: $implementor"
    }
}
