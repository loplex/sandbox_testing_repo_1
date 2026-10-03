# Building dog-vision

How to build each program from this repository, and the desktop windows' packages.
What each program does is [Using it](using.md)'s.

- [What the build needs](#what-the-build-needs) — the JDK, the Android SDK, Chrome.
- [Every artifact at once](#every-artifact-at-once) — `./gradlew packageAll`, and the folder each
  artifact lands in.
- [Starting over](#starting-over) — `tools/clean_all.sh`, a clean with `.gradle` and the
  configuration cache.
- [The Android app](#the-android-app), [the web page](#the-web-page),
  [the command line](#the-command-line) and [the desktop window](#the-desktop-window) — the
  `./gradlew` tasks and what they write.
- [The command line's zip for Windows](#the-command-lines-zip-for-windows) — `dog-vision-cli.exe`
  on a runtime of its own, built by jpackage on Windows or under Wine.
- [The desktop packages](#the-desktop-packages) — the deb, the rpm and the tar.gz of each window,
  and the one MSI of both and the command line: the tools they need, what they install, their
  dependencies, and the scripts that try them.

## What the build needs

- **A JDK 17 or newer**; Gradle itself is fetched by the wrapper, and Node.js, which runs `core`'s
  tests in JavaScript, by the Kotlin Gradle plugin.
- **The Android SDK with platform 37**, found through `ANDROID_HOME` or `sdk.dir` in
  `local.properties`, which Android Studio and IntelliJ IDEA write when they open the project.
- **Google Chrome** for the web page's tests, found on the `PATH` as `google-chrome` or through
  `CHROME_BIN`.

## Every artifact at once

```sh
./gradlew packageAll
```

Gradle runs the task of that name in every module that has one.
A module's build script marks each task that makes one of its artifacts with
[`artifact()`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/Artifact.kt), which adds it
to the module's `packageAll`.
Each artifact lands here:

| Program        | Artifacts                    | Folder                                       |
|----------------|------------------------------|----------------------------------------------|
| Android app    | the debug APK                | `app/build/outputs/apk/debug`                |
| web page       | the page                     | `web/build/dist/js/productionExecutable`     |
| command line   | the JAR                      | `cli/build/jars`                             |
| command line   | the deb, the rpm             | `packaging/build/packages/deb`, `…/rpm`      |
| Compose window | the JARs, Linux's, Windows's | `desktop/build/compose/jars`                 |
| Compose window | the deb, the rpm             | `packaging/build/packages/deb`, `…/rpm`      |
| Compose window | the tar.gz                   | `desktop/build/compose/binaries/main/tar`    |
| Swing window   | the JARs, Linux's, Windows's | `swing/build/jars`                           |
| Swing window   | the deb, the rpm             | `packaging/build/packages/deb`, `…/rpm`      |
| Swing window   | the tar.gz                   | `swing/build/packages/tar`                   |

`./gradlew checkArtifactFolders` holds the folders to the tasks, and runs in `check`: every file a
task of `packageAll` declares lies in one of them, and each of them holds one.

The MSI and the command line's zip for Windows are not among them, as jpackage builds an installer
or a native launcher only on the system it is for:
[The MSI, built under Wine or on Windows](#the-msi-built-under-wine-or-on-windows) and
[The command line's zip for Windows](#the-command-lines-zip-for-windows) say how.

## Starting over

```sh
tools/clean_all.sh
```

- **It deletes everything the build has made in the repository**: each module's `build`
  directory, `build-logic`'s too, which `./gradlew clean` leaves, as it is a build of its own, and
  in both builds `.gradle`, Gradle's project cache, with the configuration cache, for which Gradle
  has no task, and `.kotlin`, the Kotlin daemon's session files.
- **It leaves Gradle's build cache in the home**, from which the next build takes back each task's
  outputs: `--no-build-cache` or `--rerun-tasks` on that build runs the tasks all the same.

## The Android app

```sh
./gradlew :app:assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug      # onto the connected phone or emulator
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

### The command line's zip for Windows

```sh
tools/package_cli_zip_on_linux.sh
                                 # tools/build/zip/dog-vision-cli-0.1.0-windows-x64.zip
pwsh tools/package_cli_zip_on_windows.ps1
                                 # on Windows: the same
```

- **It is to unpack and run without installing it**, as `dog-vision-cli\dog-vision-cli.exe`, on
  Windows on x86-64; it adds itself to neither the `PATH` nor the Start menu, where the MSI, in
  [What each package holds](#what-each-package-holds), puts it on the `PATH`.
- **It holds jpackage's app image**: `dog-vision-cli.exe`, a native launcher that runs in a
  console, its JARs in `app`, a runtime of its own in `runtime`, and the licence.
- **Its runtime is Temurin's JDK 25, cut down by jlink to `java.base` and `java.desktop`**, which
  ImageIO needs.
  `./gradlew :packaging:windowsCliRuntime` links it, on any system, from Temurin's jmods for
  Windows, which Gradle downloads from Adoptium's releases on GitHub.
  Their version is `temurin-windows-jmods` in [`libs.versions.toml`](../gradle/libs.versions.toml):
  jlink takes jmods of any update of its own feature release, 25.
- **jpackage makes the launcher only on Windows**, so each script runs a Windows jpackage over the
  runtime and the JARs:
  - [`tools/package_cli_zip_on_windows.ps1`](../tools/package_cli_zip_on_windows.ps1) runs the
    JDK 25 that `JAVA_HOME` names;
  - [`tools/package_cli_zip_on_linux.sh`](../tools/package_cli_zip_on_linux.sh) zips the image
    that [`package_app_image_on_linux.sh`](../tools/package_app_image_on_linux.sh) builds under
    Wine, and needs `zip`.
- **[`tools/test_cli_zip_on_windows.ps1`](../tools/test_cli_zip_on_windows.ps1) tries it on
  Windows**: it unpacks it into a folder whose name has a space, and runs `dog-vision-cli` from the
  `PATH` with `--help`, on a photo and with an unknown option.
  The Windows zip job of [`ci.yml`](../.github/workflows/ci.yml) builds it and tries it on every
  push, and [`msi-under-wine.yml`](../.github/workflows/msi-under-wine.yml) tries the one built
  under Wine.

## The desktop window

```sh
./gradlew :desktop:run                                   # the camera, /dev/video0
./gradlew :desktop:run --args="--window photo.jpg"       # a photo, or a video played over and over
./gradlew :desktop:linuxUberJar                          # desktop/build/compose/jars/dog-vision-linux-x64-0.1.0.jar
./gradlew :desktop:windowsUberJar                        # desktop/build/compose/jars/dog-vision-windows-x64-0.1.0.jar
./gradlew :swing:runJvm                                  # the Swing window, on the camera
./gradlew :swing:runJvm --args="--window photo.jpg"      # the Swing window, on a photo or a video
./gradlew :swing:linuxUberJar                            # swing/build/jars/dog-vision-swing-linux-x64-0.1.0.jar
./gradlew :swing:windowsUberJar                          # swing/build/jars/dog-vision-swing-windows-x64-0.1.0.jar
```

- **`desktop` is the Compose window and `swing` the Swing one**, each with its own JARs and
  packages.
- **Each JAR holds everything the window needs**, the natives for its system on x86-64 included,
  and runs as `java -jar dog-vision-linux-x64-0.1.0.jar` on a JDK 17 or newer, with the window's
  options.
- **A file two of the merged JARs both hold has to be the same in each**, or the JAR's task fails
  and names the JARs: the uber JARs, the command line's as well, are made by
  [`uberJar()`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/UberJar.kt), whose
  comment says what it leaves out instead.
- **The Windows JAR is built on any machine**, Linux included, with Windows's natives and ANGLE in
  place of this machine's.
  ANGLE's DLLs come from Nucleus's build of it (`dev.nucleusframework:nucleus.angle-natives`), as
  Google ships none.

## The desktop packages

```sh
./gradlew :packaging:packageDeb  # packaging/build/packages/deb: dog-vision_0.1.0_amd64.deb,
                                 # dog-vision-swing_0.1.0_amd64.deb, dog-vision-cli_0.1.0_all.deb
./gradlew :packaging:packageRpm  # packaging/build/packages/rpm: dog-vision-0.1.0-1.x86_64.rpm,
                                 # dog-vision-swing-0.1.0-1.x86_64.rpm, dog-vision-cli-0.1.0-1.noarch.rpm
./gradlew :desktop:packageTarGz  # desktop/build/compose/binaries/main/tar/dog-vision-0.1.0-linux-x64.tar.gz
./gradlew :swing:packageTarGz    # swing/build/packages/tar/dog-vision-swing-0.1.0-linux-x64.tar.gz
tools/fetch_msi_tools_on_linux.sh
                                 # once: what the MSI is built with, into tools/cache
tools/make_wine_prefix_on_linux.sh
                                 # once: its Wine prefix, ~/.local/share/wineprefixes/mono_msi_builder
tools/package_app_image_on_linux.sh
                                 # the folders the MSI and the zip hold: tools/build/app-image/dog-vision, …
tools/package_msi_on_linux.sh    # tools/build/msi/dog-vision-0.1.0.msi
pwsh tools/package_msi_on_windows.ps1
                                 # on Windows: the same
```

Each deb and rpm alone is a task of `:packaging` named after it: `packageDogVisionDeb`,
`packageDogVisionSwingDeb` and `packageDogVisionCliDeb`, and the same with `Rpm`.

Besides the build's own needs, making them takes these tools, on Ubuntu from the packages named:

| Package | Tools                                      | Ubuntu's packages | Run by                                           |
|---------|--------------------------------------------|-------------------|--------------------------------------------------|
| deb     | `dpkg-deb`                                 | dpkg              | `package…Deb`                                    |
| deb     | `readelf`                                  | binutils          | `…DebDepends`                                    |
| rpm     | `rpmbuild` 4.13 or later, `rpm`, `elfdeps` | rpm               | `…RpmLibraryRequires`, `package…Rpm`             |
| MSI     | Wine, a Windows JDK 17, WiX 3.14           | wine              | `tools/package_msi_on_linux.sh`, which says more |

- **Gradle downloads Temurin 25 on the first build** that needs it, where it finds none installed,
  so that build needs the network: jpackage runs from it, and it is the tar.gzs' runtime.
  Its jlink links the MSI's runtime as well, from Temurin's jmods for Windows, which Gradle
  downloads too, of the release `temurin-windows-jmods` in
  [`libs.versions.toml`](../gradle/libs.versions.toml) gives.
  The Compose window's tar.gz comes from Compose's own jpackage task, the Swing window's from
  [`AppImage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/AppImage.kt) in
  `build-logic`, as Compose's plugin is not applied to it.
  The deb and the rpm take none, as they run on the system's Java.
- **The tar.gz needs nothing more**, and neither do the JARs.
- **The version, 0.1.0 in the names above, is `appVersion` in
  [`gradle.properties`](../gradle.properties)**, the Android app's `versionName` too.
  `-PappVersion=<version>` builds the packages with another, and the MSI scripts take
  `--app-version` and `-AppVersion` for the same.

### What each package holds

- **The deb and the rpm run on the system's Java**, 17 or newer, which they depend on: the
  distribution updates it, and a machine with a JRE downloads little more.
  They come in three packages:
  - `dog-vision`, the Compose window, which converts a photo given alone as the command line does;
  - `dog-vision-swing`, the Swing window, which does the same, and installs beside `dog-vision`;
  - `dog-vision-cli`, the command line alone, which needs no display and so only a headless Java,
    and which both windows recommend.
- **Their files are where Debian's and Fedora's Java applications have them**:
  - the JARs in `/usr/share/<package>/lib`, each package with its own copy of the command line's, so
    that they share no classpath;
  - each window's native libraries in `/usr/lib/<package>`, where the FHS puts what depends on the
    architecture, and which skiko and LWJGL load them from rather than unpack their own copies at
    run time: skiko's and LWJGL's for `dog-vision`, LWJGL's alone for `dog-vision-swing`;
  - the launchers, `dog-vision`, `dog-vision-swing` and `dog-vision-cli`, in `/usr/bin`, on `PATH`;
  - each window's menu entry, named in the system's language, and its icons in the hicolor theme:
    `cz.loplex.dogvision.desktop` ("Dog Vision", "Psí vidění") and
    `cz.loplex.dogvision.swing.desktop` ("Dog Vision (Swing)", "Psí vidění (Swing)"), in
    `/usr/share/applications`.
- **FlatLaf's natives stay in its JAR** in `dog-vision-swing`: it loads them on Linux only for
  window decorations of its own, which the window does not use, and they link GTK 3.
- **Each launcher finds its Java** in `JAVA_HOME`, then on `PATH`, then the newest in
  `/usr/lib/jvm` and `/usr/lib64/jvm`, and says so where none is 17 or newer:
  the alternatives may point `java` at an older one on a machine that has a newer one as well.
  It is [`launcher.sh`](../build-logic/src/main/resources/cz/loplex/dogvision/packaging/launcher.sh),
  filled in by the build.
- **The tar.gz and the MSI bring a runtime of their own**, Temurin's JDK 25, which jlink cuts down
  to the modules their launchers use: each tar.gz the window's and `dog-vision-cli`, the MSI both
  windows' and `dog-vision-cli`.
  Temurin brings its own image and font libraries, which a distribution's OpenJDK takes from the
  system, so the tar.gz needs little more than glibc 2.28 or later, which LWJGL's natives ask for,
  X11 and ALSA, and for the Compose window fontconfig and the C++ runtime as well, which skiko
  links.
  It is to run as `dog-vision/bin/dog-vision` or `dog-vision-swing/bin/dog-vision-swing` without
  installing it.
- **The MSI, `dog-vision`, installs both windows and the command line** into
  `Program Files\dog-vision`, each a launcher on the one runtime: `dog-vision.exe`,
  `dog-vision-swing.exe` and `dog-vision-cli.exe`.
  The installer and the system's list of programs name it *Dog Vision*.
- **Each JAR is in `app` once**, as Gradle resolves it, rather than merged into an uber JAR per
  launcher: a JAR more than one launcher runs on, as the Kotlin standard library or LWJGL, is
  shared, and every other belongs to the one launcher that runs on it.
- **The installer offers each as a part of its own**, a Feature in its tree, under the runtime's,
  which cannot be left out; the table below lists them.
  Every part is selected by default, so a plain or a silent install (`msiexec /qn`, winget)
  installs them all.
  `ADDLOCAL` names fewer, as `msiexec /i dog-vision-0.1.0.msi ADDLOCAL=DogVision,SwingGui` installs
  the Swing window alone; Windows adds `DogVision` by itself, Wine does not.
  *Change* in the system's list of programs adds or removes parts later, as do `ADDLOCAL` and
  `REMOVE`.
  A later version installed over it keeps the parts installed, and a part added later or a later
  version goes into the folder chosen at the first install, which the MSI reads from the registry.

| Feature       | In the tree                 | Installs                                           |
|---------------|-----------------------------|----------------------------------------------------|
| `DogVision`   | Dog Vision                  | the runtime and the shared JARs                    |
| `ComposeGui`  | GUI (Compose Multiplatform) | `dog-vision.exe`, its JARs and its shortcuts       |
| `SwingGui`    | GUI (Java Swing)            | `dog-vision-swing.exe`, its JARs and its shortcuts |
| `CommandLine` | Command line                | `dog-vision-cli.exe`, and the folder on the `PATH` |

- **Each window gets a shortcut in the Start menu and on the desktop**, *Dog Vision* and
  *Dog Vision (Swing)*, the Start menu's in a group *Dog Vision*.
  The command line gets none, as started from one it would only print its usage, but
  `Program Files\dog-vision` goes at the end of the system's `PATH`, for the consoles opened after,
  and comes off it again when the MSI is removed.
- **It removes `%ProgramData%\Dog Vision`, where a window downloads ffmpeg, with the product**, and
  leaves it over an upgrade.
- **[`packaging/windows/dog-vision.wxs`](../packaging/windows/dog-vision.wxs) says what it does**,
  the WiX source the MSI is made of, with its upgrade code, which stays the same from one version
  to the next, so that a later version's MSI replaces the one installed.

### The Linux packages' dependencies

- **A Java that can open a window**, or a headless one for `dog-vision-cli`:
  - the deb, `default-jre (>= 2:1.17) | java17-runtime`: the distribution's default JRE where it is
    17 or newer, as the Debian Java Policy has it, and any other JRE that provides
    `java17-runtime`, Temurin's among them, where it is not (Ubuntu 20.04's and 22.04's are 11);
  - the rpm, `(jre-17 or jre-21 or jre-25)`, a rich dependency, as no one name is provided by every
    rpm JRE of 17 or newer and by no older one: Fedora's and Rocky's Java 8 provide `jre = 1:1.8.0`,
    whose epoch puts it above `jre >= 17`.
    A new long-term release joins the list when a distribution makes it its default.
- **The libraries the window's natives link against**, and libEGL and ffmpeg, which LWJGL opens only
  once it runs and the window runs apart for a video or the camera:
  - the deb names packages as Ubuntu 20.04 names them, which later releases keep or provide, so
    that the deb is the same wherever it is built; the table `debianPackages` in
    [`DebDepends.kt`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/DebDepends.kt)
    names them, and the build fails on a library it lacks;
  - the rpm names libraries and a file (`libX11.so.6()(64bit)`, `libEGL.so.1()(64bit)`,
    `/usr/bin/ffmpeg` and the like), as Fedora and openSUSE name their packages differently.
- **A font, for the rpm of `dog-vision-swing`**, `(font(:lang=en) or dejavu-fonts)`: Swing cannot
  start without one, and openSUSE's JRE brings none, where Debian's fontconfig does.
  Fedora's and Rocky's font packages provide `font(:lang=en)`, which openSUSE's do not.
  The Compose window draws its text through skiko and needs none.
- **Nothing else, and no scripts**: the menu entry and the icons are files of the package, which
  the desktop finds by itself.

The tasks that make them, [`DebPackage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/DebPackage.kt)
and [`RpmPackage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/RpmPackage.kt), call
`dpkg-deb` and `rpmbuild` themselves, in [`build-logic`](../build-logic), as jpackage always puts a
runtime into its packages.

### Trying the Linux packages

- **[`tools/test_deb.sh`](../tools/test_deb.sh) installs a deb in a bare container**, `dog-vision`,
  `dog-vision-swing` or `dog-vision-cli`, with the Java apt chooses for it, and checks that each
  runs from `PATH`, converts [`test_photo.jpg`](../tools/test_photo.jpg) and is removed with nothing
  left behind.
  - Of each window, it also opens the window under Xvfb, installed only after the deb's own
    dependencies so that its X libraries hide none the deb misses, and checks that skiko, LWJGL and
    FlatLaf unpacked no natives of their own into the home or `/tmp`.
  - It holds on Ubuntu 20.04, 22.04 and 24.04 and on Debian 12.
    Debian 11 is not among them: its support ended in August 2026, and its repositories moved to
    archive.debian.org.
- **[`tools/test_rpm.sh`](../tools/test_rpm.sh) does for an rpm what `test_deb.sh` does**, with
  dnf, zypper or microdnf; it holds on Fedora 42, openSUSE Leap 15.6 and Rocky Linux 9's minimal
  image, whose repositories lack ffmpeg.
- **With `--upgrade <a later package>`, each installs that one over the first** before removing
  it, as an update does, and checks that the later version is the one installed and does what the
  first did.
  The later package is the same build with `-PappVersion=0.1.1`, which the build writes beside the
  first, as the Linux job of [`ci.yml`](../.github/workflows/ci.yml) does.
  From a deb or an rpm of jpackage's, in `/opt/dog-vision` up to 0.1.x, the upgrade leaves nothing
  there, as tried on Ubuntu 20.04 and Fedora 42.
- **With `--temurin`, each installs Temurin's JRE from Adoptium's repository first**, and checks
  that the package takes it and no OpenJDK beside it, as it does on Ubuntu 24.04 and Fedora 42.

### The MSI, built under Wine or on Windows

jpackage builds an app image for Windows only on Windows, so the MSI is built either under Wine on
Linux, by `package_msi_on_linux.sh`, or on Windows, by `package_msi_on_windows.ps1`, the same MSI
both.

- **On Windows, [`tools/package_msi_on_windows.ps1`](../tools/package_msi_on_windows.ps1) builds
  it** with the jpackage of the JDK 25 it runs on and WiX 3.14, whose light.exe validates it.
- **Under Wine, [`tools/package_msi_on_linux.sh`](../tools/package_msi_on_linux.sh) builds it**,
  with the detours round Wine that it and `package_app_image_on_linux.sh` describe.
  [`tools/fetch_msi_tools_on_linux.sh`](../tools/fetch_msi_tools_on_linux.sh) downloads what it
  builds with, once: a Windows JDK 17, WiX 3.14 and Wine Mono, into `tools/cache`.
  [`tools/make_wine_prefix_on_linux.sh`](../tools/make_wine_prefix_on_linux.sh) makes the Wine
  prefix it builds in, with Wine Mono, on which WiX runs, in a few minutes: `mono_msi_builder`
  among winetricks' named prefixes, in `~/.local/share/wineprefixes`.
  Wine cannot validate it, so the workflow
  [`msi-under-wine.yml`](../.github/workflows/msi-under-wine.yml), started by hand, builds it on
  Linux and validates, installs, upgrades and removes it on Windows.
- **Under Wine, [`tools/package_app_image_on_linux.sh`](../tools/package_app_image_on_linux.sh)
  builds the app image first**, the folder the MSI installs, in `tools/build/app-image`, which
  `package_msi_on_linux.sh` runs it for.
  Its `.exe` run under Wine from there, without installing the MSI:
  `wine tools/build/app-image/dog-vision/dog-vision.exe`.
- **One app image holds the three launchers**, from one run of jpackage, of the arguments that
  `:packaging`'s `windowsJpackage` task writes into `packaging/build/windows/jpackage`.
  Each of `desktop`, `swing` and `cli` hands it its launcher through its `windowsLauncher` task:
  its classpath, the main class and the Java options, and the modules the runtime needs.
- **A JAR of one name is in the image once**, and the `windowsJpackage` task fails where two
  launchers hand it different bytes.
  Two JARs of the Compose window's share a name, JetBrains' forwarding JAR and androidx's, so each
  takes its module's group before its name, as in the deb.
  ANGLE's JAR is packed anew without its natives for Windows on ARM, as the uber JARs leave them
  out.
- **jpackage puts every JAR of the image on each launcher's classpath**, so the
  `windowsLauncherConfigs` task writes each launcher's `.cfg` with its own classpath, in its order,
  which the scripts copy into the image, the command line's zip's too.
- **Both take the runtime from Gradle**, so that it is the same on Windows and under Wine:
  `./gradlew :packaging:windowsRuntime` links it, of every module a launcher needs, on any system,
  as the command line's zip's above.
- **WiX makes the MSI of the project's own source**, not of jpackage's: the `windowsWix` task writes
  into `packaging/build/windows/wix`
  [`packaging/windows/dog-vision.wxs`](../packaging/windows/dog-vision.wxs), a component for each
  file of the app image, and the values the source takes, which the scripts hand candle.exe and
  light.exe.
  Each launcher's files, its `.exe`, its `.cfg` and the JARs on its classpath alone, are a component
  group of their own, `Launcher.dog_vision_swing`, which the source's Feature of that launcher
  takes; the rest is the group `Files`, in the runtime's Feature.
- **[`tools/test_msi_on_windows.ps1`](../tools/test_msi_on_windows.ps1) tries it on a Windows
  machine to throw away**: it installs the MSI, runs the command line, from the `PATH` too, looks
  at the shortcuts, installs a later version over it and removes that, and checks that the
  installation folder is on the `PATH` once and off it after.
  Then it installs the Swing window alone, by `ADDLOCAL=SwingGui`, into a folder of its own, adds
  the Compose window, and checks that the later version keeps both there, without the command line.
- **The licence dialog shows [`LICENSE`](../LICENSE) line for line**, from the RTF that the
  `windowsLicense` task writes, as the GPL has its lines broken by hand.
- **The dialogs show the dog**, on the bitmaps that the `windowsBitmaps` task draws from the icon in
  place of WiX's: the banner across the top of most of them, and a panel on the left of the first
  and the last, in the icon's background.
- **The MSI's code page is Windows-1250**, as the vendor's name has a ř that the Windows-1252 of
  WiX's own English strings lacks:
  [`packaging/windows/codepage.wxl`](../packaging/windows/codepage.wxl) says how.
