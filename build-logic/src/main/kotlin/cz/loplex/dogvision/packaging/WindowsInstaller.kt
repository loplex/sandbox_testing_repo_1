package cz.loplex.dogvision.packaging

import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File

/** The version of the packages for Windows: appVersion, or -PwindowsAppVersion, as a test of an upgrade needs. */
private fun Project.windowsVersion(): Provider<String> =
    providers.gradleProperty("windowsAppVersion").orElse(providers.gradleProperty("appVersion"))

/** Whether the scripts in tools run jpackage and WiX under Wine, rather than on Windows. */
private fun underWine(): Boolean = !System.getProperty("os.name").startsWith("Windows")

/**
 * Registers windowsJpackage, which writes build/windows/jpackage, what jpackage makes the Windows app image
 * [packageName] of, as [WindowsJpackageFiles] says: [launchers] on [runtime], the launcher [packageName] the main one.
 */
fun Project.windowsAppImage(
    packageName: String,
    description: String,
    launchers: FileCollection,
    runtime: TaskProvider<RuntimeImage>,
): TaskProvider<WindowsJpackageFiles> = tasks.register("windowsJpackage", WindowsJpackageFiles::class.java) {
    this.description = "Writes build/windows/jpackage, what jpackage makes the app image $packageName for Windows of."
    group = "distribution"
    this.packageName.set(packageName)
    version.set(windowsVersion())
    vendor.set(VENDOR)
    packageDescription.set(description)
    icon.set(rootProject.layout.projectDirectory.file("desktop/packaging/dog-vision.ico"))
    this.launchers.from(launchers)
    this.runtime.set(runtime.flatMap { it.destination })
    underWine.set(underWine())
    destination.set(layout.buildDirectory.dir("windows/jpackage"))
}

/**
 * Registers what the MSI of the app image that -PwindowsAppImage names is made of, which the scripts in tools build
 * with windowsAppImage's arguments first:
 *
 * - windowsLauncherConfigs: build/windows/launchers, the image's launchers' .cfg, each with its own JAR alone on its
 *   classpath, as [WindowsLauncherConfigs] says, which the scripts copy into the image.
 * - windowsLicense: build/windows/LICENSE.rtf, the licence the MSI shows.
 * - windowsBitmaps: build/windows/banner.bmp and dialog.bmp, the pictures of the MSI's dialogs.
 * - windowsWix: build/windows/wix, [product] and what it takes, as [WixSource] says, [codePage] among it, which the
 *   scripts hand candle.exe and light.exe once they have copied the .cfg.
 *
 * The product's name is the app's English name, texts' app_name; [configure] gives windowsWix the rest.
 */
fun Project.windowsMsi(product: RegularFile, codePage: RegularFile, configure: WixSource.() -> Unit) {
    val root = rootProject.layout.projectDirectory
    val image = layout.dir(providers.gradleProperty("windowsAppImage").map(::File))
    tasks.register("windowsLauncherConfigs", WindowsLauncherConfigs::class.java) {
        description = "Writes build/windows/launchers, the .cfg of the launchers of -PwindowsAppImage's image, each " +
            "with its own JAR alone on its classpath."
        group = "distribution"
        this.image.set(image)
        destination.set(layout.buildDirectory.dir("windows/launchers"))
    }
    val license = tasks.register("windowsLicense", LicenseRtf::class.java) {
        description = "Writes build/windows/LICENSE.rtf, the licence the MSI shows, line for line."
        group = "distribution"
        text.set(root.file("LICENSE"))
        rtf.set(layout.buildDirectory.file("windows/LICENSE.rtf"))
    }
    val bitmaps = tasks.register("windowsBitmaps", InstallerBitmaps::class.java) {
        description = "Draws build/windows/banner.bmp and dialog.bmp, the pictures of the MSI's dialogs."
        group = "distribution"
        icon.set(root.file("desktop/packaging/dog-vision.png"))
        background.set(root.file("app/src/main/res/values/ic_launcher_background.xml"))
        banner.set(layout.buildDirectory.file("windows/banner.bmp"))
        dialog.set(layout.buildDirectory.file("windows/dialog.bmp"))
    }
    val strings = root.file("texts/strings/values/strings.xml")
    val appName = providers.fileContents(strings).asText.map { text ->
        checkNotNull(APP_NAME.find(text)) { "${strings.asFile} has no app_name" }.groupValues[1]
    }
    tasks.register("windowsWix", WixSource::class.java) {
        description = "Writes build/windows/wix, what WiX makes the MSI of -PwindowsAppImage's image of."
        group = "distribution"
        this.image.set(image)
        this.product.set(product)
        this.codePage.set(codePage)
        productName.set(appName)
        version.set(windowsVersion())
        vendor.set(VENDOR)
        icon.set(root.file("desktop/packaging/dog-vision.ico"))
        this.license.set(license.flatMap { it.rtf })
        banner.set(bitmaps.flatMap { it.banner })
        dialog.set(bitmaps.flatMap { it.dialog })
        underWine.set(underWine())
        destination.set(layout.buildDirectory.dir("windows/wix"))
        configure()
    }
}

private val APP_NAME = Regex("""<string name="app_name">([^<]+)</string>""")
