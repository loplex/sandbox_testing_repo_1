# Building dog-vision

How to build each program from this repository, and the desktop window's packages.
What each program does is [Using it](using.md)'s.

- [What the build needs](#what-the-build-needs) — the JDK, the Android SDK, Chrome.
- [The Android app](#the-android-app), [the web page](#the-web-page),
  [the command line](#the-command-line) and [the desktop window](#the-desktop-window) — the
  `./gradlew` tasks and what they write.
- [The desktop packages](#the-desktop-packages) — the deb, the rpm, the tar.gz and the MSI: the
  tools they need, what they install, their dependencies, and the scripts that try them.

## What the build needs

- **A JDK 17 or newer**; Gradle itself is fetched by the wrapper, and Node.js, which runs `core`'s
  tests in JavaScript, by the Kotlin Gradle plugin.
- **The Android SDK with platform 36**, found through `ANDROID_HOME` or `sdk.dir` in
  `local.properties`, which Android Studio and IntelliJ IDEA write when they open the project.
- **Google Chrome** for the web page's tests, found on the `PATH` as `google-chrome` or through
  `CHROME_BIN`.

## The Android app

```sh
./gradlew :android:assembleDebug     # android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:installDebug      # onto the connected phone or emulator
```

The phone needs Android 8.0 (API 26) or later, and OpenGL ES 3.0.

## The web page

```sh
./gradlew :web:jsBrowserDevelopmentRun   # serves the page, and builds it again on every change
./gradlew :web:jsBrowserDistribution     # web/build/dist/js/productionExecutable
```

## The command line

```sh
./gradlew :cli:uberJar           # cli/build/jars/dog-vision-cli.jar, with everything it needs
./gradlew :cli:installJvmDist    # or a start script and its JARs, in cli/build/install/dog-vision-cli-jvm
```

## The desktop window

```sh
./gradlew :gui-compose:run                                   # the camera, /dev/video0
./gradlew :gui-compose:run --args="--window photo.jpg"       # a photo, or a video played over and over
./gradlew :gui-compose:packageUberJarForCurrentOS            # gui-compose/build/compose/jars/dog-vision-linux-x64-0.1.0.jar
./gradlew :gui-compose:windowsUberJar                        # gui-compose/build/compose/jars/dog-vision-windows-x64-0.1.0.jar
```

- **Each JAR holds everything the window needs**, the natives for its system on x86-64 included,
  and runs as `java -jar dog-vision-linux-x64-0.1.0.jar` on a JDK 17 or newer, with the window's
  options.
- **The Windows JAR is built on any machine**, Linux included, with Windows's natives and ANGLE in
  place of this machine's.
  ANGLE's DLLs come from Nucleus's build of it (`dev.nucleusframework:nucleus.angle-natives`), as
  Google ships none.

## The desktop packages

```sh
./gradlew :gui-compose:packageDeb    # gui-compose/build/compose/binaries/main/deb/dog-vision_0.1.0_amd64.deb
./gradlew :gui-compose:packageRpm    # gui-compose/build/compose/binaries/main/rpm/dog-vision-0.1.0-1.x86_64.rpm
./gradlew :gui-compose:packageTarGz  # gui-compose/build/compose/binaries/main/tar/dog-vision-0.1.0-linux-x64.tar.gz
tools/package_msi_on_linux.sh --jdk <Windows JDK 17> --jmods <Windows JDK 25's jmods> --wix <WiX 3.14>
                                     # gui-compose/build/compose/binaries/main/msi/dog-vision-0.1.0.msi
pwsh tools/package_msi_on_windows.ps1
                                     # on Windows: gui-compose/build/compose/binaries/main/msi/0.1.0/dog-vision-0.1.0.msi
```

Besides the build's own needs, making them takes these tools, on Ubuntu from the packages named:

| Package | Tools                                      | Ubuntu's packages | Run by                                           |
|---------|--------------------------------------------|-------------------|--------------------------------------------------|
| deb     | `dpkg-deb`, `dpkg`, `fakeroot`             | dpkg, fakeroot    | jpackage, and `packageDeb` to pack it again      |
| deb     | `readelf`                                  | binutils          | `debDepends`                                     |
| rpm     | `rpmbuild` 4.10 or later, `rpm`, `elfdeps` | rpm               | jpackage, `rpmLibraryRequires`, `packageRpm`     |
| MSI     | Wine, a Windows JDK 17, jmods, WiX 3.14    | wine, winetricks  | `tools/package_msi_on_linux.sh`, which says more |

- **Gradle downloads Temurin 25 on the first build** that needs it, where it finds none installed,
  so that build needs the network.
- **The tar.gz needs nothing more**, and neither do the JARs.
- **The version, 0.1.0 in the names above, is `appVersion` in
  [`gradle.properties`](../gradle.properties)**, the Android app's `versionName` too.
  `-PappVersion=<version>` builds the packages with another, and the MSI scripts take
  `--app-version` and `-AppVersion` for the same.

### What each package holds

- **Each brings a runtime of its own**, Temurin's JDK 25, which jlink cuts down to the modules the
  window uses.
  Temurin brings its own image and font libraries, which a distribution's OpenJDK takes from the
  system, so the Linux packages need little more than glibc 2.17 or later, X11, ALSA, fontconfig
  and the C++ runtime.
- **Each has two launchers**:
  - `dog-vision`, the window, which converts a photo given alone as the command line does;
  - `dog-vision-cli`, the command line alone, which prints its usage rather than open the window,
    and on Windows runs in a console.
- **The deb and the rpm install into `/opt/dog-vision`**, with the launchers in its `bin`, and add
  the window to the desktop's menu under Graphics, as the file of the package
  `/usr/share/applications/cz.loplex.dogvision.desktop`.
- **The tar.gz is the same application unpacked**, to run as `dog-vision/bin/dog-vision` without
  installing it.
- **The MSI installs into `Program Files\dog-vision`** and adds the window to the Start menu, in a
  dog-vision group, and to the desktop.
  It carries a fixed upgrade code, so that a later version's MSI replaces it.

### The Linux packages' dependencies

The deb and the rpm depend on libEGL and ffmpeg as well as on the libraries the image links
against, since LWJGL opens libEGL only once it runs.

- **The deb names packages as Ubuntu 20.04 names them**, which later releases keep or
  provide (Ubuntu 24.04's `libasound2t64` provides `libasound2`), so that the deb is the same
  wherever it is built.
  The table `debianPackages` in
  [`DebDepends.kt`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/DebDepends.kt)
  names them, and the build fails on a library it lacks.
- **The rpm names libraries and a file** (`libX11.so.6()(64bit)`, `libEGL.so.1()(64bit)`,
  `/usr/bin/ffmpeg` and the like), as Fedora and openSUSE name their packages differently.
- **Neither needs xdg-utils**: jpackage's scripts would install the desktop entry with
  `xdg-desktop-menu`, which fails on a system with no desktop, so `packageDeb` and `packageRpm`
  make it a file of the package instead.

How the build rewrites jpackage's deb and rpm for each of these is in the comments of
[`RepackDeb.kt`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/RepackDeb.kt) and
[`RepackRpm.kt`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/RepackRpm.kt), in
[`build-logic`](../build-logic), which
[`gui-compose/build.gradle.kts`](../gui-compose/build.gradle.kts) registers and wires to jpackage's tasks.

### Trying the Linux packages

- **[`tools/test_deb.sh`](../tools/test_deb.sh) installs the deb in a bare container**, runs its
  command line and removes it; it holds on Ubuntu 20.04, 22.04 and 24.04 and on Debian 12.
  Debian 11 is not among them: its support ended in August 2026, and its repositories moved to
  archive.debian.org.
- **[`tools/test_rpm.sh`](../tools/test_rpm.sh) does for the rpm what `test_deb.sh` does**, with
  dnf, zypper or microdnf; it holds on Fedora 42, its minimal image, openSUSE Leap 15.6 and
  Tumbleweed, and on Rocky Linux 9's and UBI 9's minimal images, whose repositories lack ffmpeg.
- **With `--upgrade <a later package>`, each installs that one over the first** before removing
  it, as an update does, and checks that the later version is the one installed and that the menu
  entry is still there.
  The later package is the same build with `-PappVersion=0.1.1`, and each build empties the folder
  it writes to, so the first package goes aside before, as the Linux job of
  [`ci.yml`](../.github/workflows/ci.yml) does.
  The upgrade holds on Ubuntu 20.04 and 24.04, Fedora 42, openSUSE Leap 15.6 and Rocky Linux 9's
  minimal image.

### The MSI, built under Wine or on Windows

jpackage builds an installer only on the system it is for, so the MSI is built either under Wine on
Linux or on Windows.

- **On Windows, [`tools/package_msi_on_windows.ps1`](../tools/package_msi_on_windows.ps1) builds
  it** with the JDK 25 it runs on and WiX 3.14, and light.exe validates it.
- **Under Wine, [`tools/package_msi_on_linux.sh`](../tools/package_msi_on_linux.sh) builds it**,
  with three detours round Wine that the script describes.
  Its runtime is linked from the jmods it is given.
  Wine cannot validate it, so the workflow
  [`msi-under-wine.yml`](../.github/workflows/msi-under-wine.yml), started by hand, builds it on
  Linux and validates, installs, upgrades and removes it on Windows.
- **[`tools/test_msi_on_windows.ps1`](../tools/test_msi_on_windows.ps1) tries it on a Windows
  machine to throw away**: it installs the MSI, runs the command line, looks at the shortcuts,
  installs a later version over it and removes that.
- **The MSI's code page is Windows-1250**, from
  [`gui-compose/packaging/windows`](../gui-compose/packaging/windows/MsiInstallerCodepage_en.wxl), as the
  vendor's name has a ř that jpackage's Windows-1252 lacks.

#### Caveat: the MSI built under Wine gives `dog-vision-cli` shortcuts too

It adds a Start menu entry and a desktop shortcut for `dog-vision-cli` as well, which started from
there only prints its usage: JDK 17's jpackage cannot leave one launcher out, where JDK 25's, on
Windows, does.
