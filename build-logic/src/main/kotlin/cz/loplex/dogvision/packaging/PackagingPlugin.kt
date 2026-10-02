package cz.loplex.dogvision.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Applied by a module that makes Linux packages and an MSI: it puts this package's tasks on the module's build script
 * classpath, gives each [DebPackage] and [RpmPackage] what every package of the project shares, which the module's
 * script registers with what is its own, and registers windowsLicense, the licence its MSI shows.
 */
@Suppress("unused", "RedundantSuppression") // named only by implementationClass in build.gradle.kts
class PackagingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val appVersion = project.providers.gradleProperty("appVersion")
        val licenseText = project.rootProject.layout.projectDirectory.file("LICENSE")
        val packages = project.layout.buildDirectory.dir("packages")
        project.tasks.withType(DebPackage::class.java).configureEach {
            version.convention(appVersion)
            maintainer.convention("Martin Lopatář <lopin.git@loplex.cz>")
            section.convention("graphics")
            homepage.convention(HOMEPAGE)
            license.convention(licenseText)
            destinationDirectory.convention(packages.map { it.dir("deb") })
        }
        project.tasks.withType(RpmPackage::class.java).configureEach {
            version.convention(appVersion)
            release.convention("1")
            url.convention(HOMEPAGE)
            licenseName.convention("GPL-3.0-or-later")
            license.convention(licenseText)
            destinationDirectory.convention(packages.map { it.dir("rpm") })
        }
        project.tasks.register("windowsLicense", LicenseRtf::class.java) {
            description = "Writes build/windows/LICENSE.rtf, the licence the MSI shows, line for line."
            group = "distribution"
            text.set(licenseText)
            rtf.set(project.layout.buildDirectory.file("windows/LICENSE.rtf"))
        }
    }

    private companion object {
        const val HOMEPAGE = "https://github.com/loplex/dog-vision"
    }
}
