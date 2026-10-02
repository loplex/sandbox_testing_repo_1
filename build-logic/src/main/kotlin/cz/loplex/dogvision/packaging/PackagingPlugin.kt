package cz.loplex.dogvision.packaging

import org.gradle.api.Plugin
import org.gradle.api.Project
import java.io.File

/**
 * Applied by a module that makes Linux packages and an MSI: it puts this package's tasks on the module's build script
 * classpath, gives each [DebPackage] and [RpmPackage] what every package of the project shares, which the module's
 * script registers with what is its own, and registers windowsLicense, the licence its MSI shows, windowsBitmaps, the
 * pictures of its dialogs, and windowsJpackage, what jpackage makes its app image and MSI for Windows of, which the
 * module's script gives what is its own.
 *
 * windowsJpackage takes -PjpackageJdk, the Windows JDK whose jpackage the scripts in tools run, and
 * -PwindowsAppVersion, a version of the package other than appVersion, as a test of an upgrade needs.
 */
@Suppress("unused", "RedundantSuppression") // named only by implementationClass in build.gradle.kts
class PackagingPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val appVersion = project.providers.gradleProperty("appVersion")
        val licenseText = project.rootProject.layout.projectDirectory.file("LICENSE")
        val packages = project.layout.buildDirectory.dir("packages")
        project.tasks.withType(DebPackage::class.java).configureEach {
            version.convention(appVersion)
            maintainer.convention("$VENDOR <lopin.git@loplex.cz>")
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
        val root = project.rootProject.layout.projectDirectory
        project.tasks.register("windowsBitmaps", InstallerBitmaps::class.java) {
            description = "Draws build/windows/banner.bmp and dialog.bmp, the pictures of the MSI's dialogs."
            group = "distribution"
            icon.set(root.file("gui-compose/packaging/dog-vision.png"))
            background.set(root.file("android/src/main/res/values/ic_launcher_background.xml"))
            banner.set(project.layout.buildDirectory.file("windows/banner.bmp"))
            dialog.set(project.layout.buildDirectory.file("windows/dialog.bmp"))
        }
        val license = project.tasks.named("windowsLicense", LicenseRtf::class.java)
        val bitmaps = project.tasks.named("windowsBitmaps", InstallerBitmaps::class.java)
        val strings = root.file("texts/strings/values/strings.xml")
        val appName = project.providers.fileContents(strings).asText.map { text ->
            checkNotNull(APP_NAME.find(text)) { "${strings.asFile} has no app_name" }.groupValues[1]
        }
        val jdk = project.layout.dir(project.providers.gradleProperty("jpackageJdk").map(::File))
        project.tasks.register("windowsJpackage", WindowsJpackageFiles::class.java) {
            description = "Writes build/windows/jpackage, what jpackage makes the app image and the MSI for Windows of."
            group = "distribution"
            this.appName.set(appName)
            productSuffix.convention("")
            version.set(project.providers.gradleProperty("windowsAppVersion").orElse(appVersion))
            vendor.set(VENDOR)
            icon.set(root.file("gui-compose/packaging/dog-vision.ico"))
            javaOptions.convention(emptyList())
            commandLine.convention(false)
            this.license.set(license.flatMap { it.rtf })
            banner.set(bitmaps.flatMap { it.banner })
            dialog.set(bitmaps.flatMap { it.dialog })
            componentGuid.convention("")
            codePage.set(root.file("gui-compose/packaging/windows/MsiInstallerCodepage_en.wxl"))
            jpackageJdk.set(jdk)
            jpackageRelease.set(jdk.map { it.file("release") })
            jpackageModules.set(jdk.map { it.file("lib/modules") })
            underWine.set(!System.getProperty("os.name").startsWith("Windows"))
            destination.set(project.layout.buildDirectory.dir("windows/jpackage"))
        }
    }

    private companion object {
        const val HOMEPAGE = "https://github.com/loplex/dog-vision"
        const val VENDOR = "Martin Lopatář"
        val APP_NAME = Regex("""<string name="app_name">([^<]+)</string>""")
    }
}
