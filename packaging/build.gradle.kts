import cz.loplex.dogvision.packaging.WINDOWS_LAUNCHER_USAGE
import cz.loplex.dogvision.packaging.windowsAppImage
import cz.loplex.dogvision.packaging.windowsMsi
import cz.loplex.dogvision.packaging.windowsRuntimeImage

// The packages that take more than one module: the MSI for Windows on x86-64, of the Compose window, the Swing
// window and the command line, each a launcher of one app image, on one runtime. Each of those modules hands this one
// its launcher through windowsLauncher. The scripts in tools run jpackage and WiX over what this module writes.
plugins {
    base
    // The toolchains, for the JDK whose jlink links the runtime.
    `jvm-toolchains`
    id("cz.loplex.dogvision.packaging")
}

val launchers = configurations.dependencyScope("windowsLaunchers")
val launcherFiles = configurations.resolvable("windowsLauncherFiles") {
    extendsFrom(launchers.get())
    attributes { attribute(Usage.USAGE_ATTRIBUTE, objects.named(WINDOWS_LAUNCHER_USAGE)) }
}
dependencies {
    for (module in listOf(":desktop", ":swing", ":cli")) launchers(project(module))
}

// One runtime of every module a launcher needs, and dog-vision.exe the image's main launcher, whose name the image's
// folder has.
windowsAppImage(
    packageName = "dog-vision",
    description = "How a dog or another animal sees a photo, a video or the camera",
    launchers = launcherFiles.get(),
    runtime = windowsRuntimeImage(
        "the runtime of the MSI's launchers",
        modules = emptyList(),
        moduleLists = launcherFiles.get().filter { it.name.endsWith(".modules") },
    ),
)

windowsMsi(
    product = layout.projectDirectory.file("windows/dog-vision.wxs"),
    codePage = layout.projectDirectory.file("windows/codepage.wxl"),
) {
    packageDescription = "How a dog or another animal sees a photo, a video or the camera"
    upgradeCode = "bd534b2e-fc9e-40d5-a461-b800bf54bcda"
    installFolder = "dog-vision"
}
