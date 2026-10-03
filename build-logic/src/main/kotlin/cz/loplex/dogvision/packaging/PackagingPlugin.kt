package cz.loplex.dogvision.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied by a module that makes Linux packages or a package for Windows: it puts this package's tasks and functions
 * on the module's build script classpath, and gives each [DebPackage] and [RpmPackage] what every package of the
 * project shares, which the module's script registers with what is its own.
 */
@Suppress("unused", "RedundantSuppression") // named only by implementationClass in build.gradle.kts
class PackagingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val appVersion = project.providers.gradleProperty("appVersion")
        val licenseText = project.rootProject.layout.projectDirectory.file("LICENSE")
        // Gradle's own folder of what a build distributes, base.distsDirectory's.
        val distributions = project.layout.buildDirectory.dir("distributions")
        project.tasks.withType(DebPackage::class.java).configureEach {
            version.convention(appVersion)
            maintainer.convention("$VENDOR <lopin.git@loplex.cz>")
            section.convention("graphics")
            homepage.convention(HOMEPAGE)
            license.convention(licenseText)
            destinationDirectory.convention(distributions)
        }
        project.tasks.withType(RpmPackage::class.java).configureEach {
            version.convention(appVersion)
            release.convention("1")
            url.convention(HOMEPAGE)
            licenseName.convention("GPL-3.0-or-later")
            license.convention(licenseText)
            destinationDirectory.convention(distributions)
        }
    }

    private companion object {
        const val HOMEPAGE = "https://github.com/loplex/dog-vision"
    }
}

/** Who makes the packages, their vendor and maintainer. */
internal const val VENDOR = "Martin Lopatář"
