# Building dog-vision

How to build each program in this repository, and the desktop packages.\
[Using it](using.md) says what each program does.

- [What the build needs](#what-the-build-needs) — the JDK, the Android SDK, Chrome, Python 3.
- [Every artifact at once](#every-artifact-at-once) — `./gradlew packageAll`, and the folder each artifact lands in.
- [Starting over](#starting-over) — `tools/clean_all.sh`, which also deletes `.gradle` and the configuration cache.
- [The Android app](#the-android-app), [the web page](#the-web-page), [the command line](#the-command-line) and [the desktop window](#the-desktop-window) —
  the `./gradlew` tasks and what they write.
- [The command line's zip for Windows](#the-command-lines-zip-for-windows) —
  `dog-vision-cli.exe` with its own runtime, built by jpackage on Windows or under Wine.
- [The desktop packages](#the-desktop-packages) — the deb, rpm and tar.gz of each window,
  the web page's deb and rpm, and a single MSI with both windows, the command line and the web page:
  the tools they need, what they install, their dependencies, and the scripts that test them.

## What the build needs

- **A JDK 17 or newer.**\
  The wrapper downloads Gradle itself,
  and the Kotlin Gradle plugin downloads Node.js, which runs `core`'s tests in JavaScript.
- **The Android SDK with platform 37**, found through `ANDROID_HOME` or `sdk.dir` in `local.properties`,
  which Android Studio and IntelliJ IDEA write when they open the project.
- **Google Chrome** for the web page's tests, found on the `PATH` as `google-chrome` or through `CHROME_BIN`.
- **Python 3** for the Markdown's link check in `./gradlew check`, run as `python3`.

## Every artifact at once

```sh
./gradlew packageAll
```

Gradle runs the task of that name in every module that has one.\
A module's build script marks each task that builds one of its artifacts with [`artifact()`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/Artifact.kt),
which adds it to that module's `packageAll`.\
Each artifact lands here:

| Program        | Artifacts                    | Folder                                       |
|----------------|------------------------------|----------------------------------------------|
| Android app    | the debug APK                | `android/build/outputs/apk/debug`            |
| web page       | the page                     | `web/build/dist/js/productionExecutable`     |
| web page       | the deb, the rpm             | `packaging/build/distributions`              |
| command line   | the JAR                      | `cli/build/jars`                             |
| command line   | the deb, the rpm             | `packaging/build/distributions`              |
| JVM programs   | the shared deb and rpm       | `packaging/build/distributions`              |
| Compose window | the Linux and Windows JARs   | `gui-compose/build/compose/jars`             |
| Compose window | the deb, the rpm, the tar.gz | `packaging/build/distributions`              |
| Swing window   | the Linux and Windows JARs   | `gui-swing/build/jars`                       |
| Swing window   | the deb, the rpm, the tar.gz | `packaging/build/distributions`              |

`./gradlew checkArtifactFolders`, which `check` runs, keeps this table's folders true:
every file that a task of `packageAll` declares, other than its work files, lies in one of these folders,
and every folder holds at least one.

The MSI and the command line's zip for Windows are not in this table,
because jpackage builds an installer or a native launcher only on the system it is for:
[The MSI, built under Wine or on Windows](#the-msi-built-under-wine-or-on-windows) and [The command line's zip for Windows](#the-command-lines-zip-for-windows) say how to build them.

## Starting over

```sh
tools/clean_all.sh
```

- **It deletes everything the build has made in the repository**:
  - every module's `build` directory, `build-logic`'s included,
    which `./gradlew clean` leaves alone because `build-logic` is a separate build;
  - in both builds, `.gradle`, Gradle's project cache, which holds the configuration cache that no Gradle task clears,
    and `.kotlin`, the Kotlin daemon's session files;
  - `tools/build`, what the scripts in `tools` build.
- **It leaves Gradle's build cache in your home directory**, and the next build restores each task's outputs from it:
  pass `--no-build-cache` or `--rerun-tasks` to that build to run the tasks anyway.

## The Android app

```sh
./gradlew :android:assembleDebug     # android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:installDebug      # onto the connected phone or emulator
```

The phone needs Android 8.0 (API 26) or later, and OpenGL ES 3.0.

## The web page

```sh
./gradlew :web:jsBrowserDevelopmentRun   # serves the page; with -t, rebuilds it on every change
./gradlew :web:jsBrowserDistribution     # web/build/dist/js/productionExecutable
tools/run_web_on_linux.sh                # serves that page on localhost:8000 and opens it in the browser
```

Its deb and rpm, `dog-vision-web`, are described with [the desktop packages](#the-desktop-packages).

## The command line

```sh
./gradlew :cli:uberJar           # cli/build/jars/dog-vision-cli.jar, with everything it needs
./gradlew :cli:installJvmDist    # or a start script and its JARs, in cli/build/install/dog-vision-cli-jvm
tools/run_cli_on_linux.sh photo.jpg
                                 # runs the JAR that the uberJar task built
```

### The command line's zip for Windows

```sh
tools/package_cli_zip_on_linux.sh
                                 # tools/build/zip/dog-vision-cli-0.1.0-windows-x64.zip
pwsh tools/package_cli_zip_on_windows.ps1
                                 # on Windows: the same
```

- **You unpack it and run `dog-vision-cli\dog-vision-cli.exe` without installing anything**, on Windows on x86-64.\
  It adds itself to neither the `PATH` nor the Start menu;
  the MSI, described in [What each package holds](#what-each-package-holds), does put it on the `PATH`.
- **It contains jpackage's app image**: `dog-vision-cli.exe`, a native launcher that runs in a console,
  its JARs in `app`, its own runtime in `runtime`, and the licences:
  `LICENSE`, and `THIRD-PARTY-LICENSES.txt`, which lists everything in it that is not the project's own.
- **Its runtime is Temurin's JDK 25, cut down by jlink to `java.base` and `java.desktop`**, which ImageIO needs.\
  `./gradlew :packaging:windowsCliRuntime` links it on any system,
  from Temurin's jmods for Windows, which Gradle downloads from Adoptium's releases on GitHub.\
  The jmods are always of the same release as the JDK that links them, the Temurin 25 that Gradle packages with,
  so the zip's runtime is updated along with that JDK.
- **jpackage builds the launcher only on Windows**, so each script runs a Windows jpackage on the runtime and the JARs:
  - [`tools/package_cli_zip_on_windows.ps1`](../tools/package_cli_zip_on_windows.ps1) runs the JDK 25 in `JAVA_HOME`;
  - [`tools/package_cli_zip_on_linux.sh`](../tools/package_cli_zip_on_linux.sh) zips the image
    that [`package_app_image_on_linux.sh`](../tools/package_app_image_on_linux.sh) builds under Wine, and needs `zip`.
- **[`tools/test_cli_zip_on_windows.ps1`](../tools/test_cli_zip_on_windows.ps1) tests it on Windows**:
  it unpacks the zip into a folder with a space in its name,
  and runs `dog-vision-cli` from the `PATH` with `--help`, on a photo, and with an unknown option.\
  The Windows zip job of [`ci.yml`](../.github/workflows/ci.yml) builds and tests it on every push,
  and [`msi-under-wine.yml`](../.github/workflows/msi-under-wine.yml) tests the zip built under Wine.

## The desktop window

```sh
./gradlew :gui-compose:run                                   # the camera, /dev/video0
./gradlew :gui-compose:run --args="photo.jpg"                # a photo, or a video played over and over
./gradlew :gui-compose:linuxUberJar                          # in gui-compose/build/compose/jars:
                                                             # dog-vision-compose-linux-x64-0.1.0.jar
./gradlew :gui-compose:windowsUberJar                        # in gui-compose/build/compose/jars:
                                                             # dog-vision-compose-windows-x64-0.1.0.jar
./gradlew :gui-swing:runJvm                                  # the Swing window, on the camera
./gradlew :gui-swing:runJvm --args="photo.jpg"               # the Swing window, on a photo or a video
./gradlew :gui-swing:linuxUberJar                            # gui-swing/build/jars/dog-vision-swing-linux-x64-0.1.0.jar
./gradlew :gui-swing:windowsUberJar                          # gui-swing/build/jars/dog-vision-swing-windows-x64-0.1.0.jar
tools/run_compose_on_linux.sh photo.jpg                      # runs the Linux JAR, on the camera without a file
tools/run_swing_on_linux.sh photo.jpg                        # the same for the Swing window
```

- **`gui-compose` is the Compose window and `gui-swing` the Swing one**, each with its own JARs and packages.
- **Each JAR contains everything the window needs**, including the natives for its system on x86-64.\
  It runs as `java -jar dog-vision-compose-linux-x64-0.1.0.jar` on a JDK 17 or newer, with the window's options.
- **A file that two of the merged JARs both contain must be identical in both**,
  or the JAR's task fails and names the JARs that contain it.\
  All the uber JARs, the command line's included, are made by [`uberJar()`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/jvm/UberJar.kt),
  whose comment lists the files it leaves out rather than checks.
- **Each uber JAR, the command line's included, has `META-INF/THIRD-PARTY-LICENSES.txt`**:
  every library it merges and what their natives contain, each with its licence,
  and the full text of every licence, as the packages list them.
- **The Windows JAR can be built on any machine**, Linux included,
  with the Windows natives and ANGLE in place of this machine's.\
  ANGLE's DLLs come from Nucleus's build of it (`dev.nucleusframework:nucleus.angle-natives`), as Google ships none.

## The desktop packages

```sh
./gradlew :packaging:packageDeb  # packaging/build/distributions: dog-vision-compose_0.1.0_amd64.deb,
                                 # dog-vision-swing_0.1.0_amd64.deb, dog-vision-cli_0.1.0_all.deb,
                                 # dog-vision-common_0.1.0_all.deb, dog-vision-web_0.1.0_all.deb,
                                 # dog-vision-web-sourcemap_0.1.0_all.deb, dog-vision_0.1.0_amd64.deb
./gradlew :packaging:packageRpm  # packaging/build/distributions: dog-vision-compose-0.1.0-1.x86_64.rpm,
                                 # dog-vision-swing-0.1.0-1.x86_64.rpm, dog-vision-cli-0.1.0-1.noarch.rpm,
                                 # dog-vision-common-0.1.0-1.noarch.rpm, dog-vision-web-0.1.0-1.noarch.rpm,
                                 # dog-vision-web-sourcemap-0.1.0-1.noarch.rpm, dog-vision-0.1.0-1.x86_64.rpm
./gradlew :packaging:packageTarGz
                                 # packaging/build/distributions: dog-vision-compose-0.1.0-linux-x64.tar.gz,
                                 # dog-vision-swing-0.1.0-linux-x64.tar.gz
tools/fetch_msi_tools_on_linux.sh
                                 # once: what the MSI is built with, into tools/cache
tools/make_wine_prefix_on_linux.sh
                                 # once: its Wine prefix, ~/.local/share/wineprefixes/mono_msi_builder
tools/package_app_image_on_linux.sh
                                 # the folders the MSI and the zip hold: tools/build/app-image/dog-vision-compose, …
tools/package_msi_on_linux.sh    # tools/build/msi/dog-vision-0.1.0.msi
pwsh tools/package_msi_on_windows.ps1
                                 # on Windows: the same
```

Each deb and rpm can also be built alone, by the task of `:packaging` named after it:
`packageDogVisionComposeDeb`, `packageDogVisionSwingDeb`, `packageDogVisionCliDeb`, `packageDogVisionCommonDeb`,
`packageDogVisionWebDeb`, `packageDogVisionWebSourcemapDeb` and `packageDogVisionDeb`, and the same with `Rpm`.

Besides what the build itself needs, making them takes these tools; on Ubuntu they come from the packages named:

| Package | Tools                                      | Ubuntu's packages | Run by                                           |
|---------|--------------------------------------------|-------------------|--------------------------------------------------|
| deb     | `dpkg-deb`                                 | dpkg              | `package…Deb`                                    |
| deb     | `readelf`                                  | binutils          | `…DebDepends`                                    |
| rpm     | `rpmbuild` 4.13 or later, `rpm`, `elfdeps` | rpm               | `…RpmLibraryRequires`, `package…Rpm`             |
| MSI     | Wine, a Windows JDK 17, WiX 3.14           | wine              | `tools/package_msi_on_linux.sh`, which says more |

- **Gradle downloads Temurin 25 on the first build that needs it**, unless one is installed,
  so that build needs the network.\
  jpackage runs from this JDK, and jlink cuts it down into the tar.gzs' runtime.\
  Its jlink also links the MSI's runtime, from Temurin's jmods for Windows,
  which Gradle also downloads, for the exact release named in that JDK's `release` file.\
  The feature release, 25, is `temurin` in [`libs.versions.toml`](../gradle/libs.versions.toml).\
  Both tar.gzs are made by [`AppImage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/linux/AppImage.kt) in `build-logic`,
  which `packaging` runs for each window, because `packaging` takes both the windows and the command line,
  and each tar.gz holds the command line's launcher next to the window's.\
  The deb and the rpm need no Temurin, because they run on the system's Java.
- **The tar.gz and the JARs need no other tools.**
- **The version, 0.1.0 in the names above, is `appVersion` in [`gradle.properties`](../gradle.properties)**,
  which is also the Android app's `versionName`.\
  `-PappVersion=<version>` builds the packages with a different one,
  and the MSI scripts take `--app-version` and `-AppVersion` for the same.

### What each package holds

- **The debs and rpms of the windows and the command line run on the system's Java**, 17 or newer,
  and depend on it: the distribution keeps it updated, and a machine that already has a JRE downloads little more.\
  There are four of them:
  - `dog-vision-compose`, the Compose window;
  - `dog-vision-swing`, the Swing window, which can be installed alongside `dog-vision-compose`;
  - `dog-vision-cli`, the command line alone, which needs no display and so only a headless Java;
    both windows recommend it, and it recommends ffmpeg, which videos need;
  - `dog-vision-common`, the JARs the command line runs on, which both windows run on as well;
    the other three depend on it at their own version, and it depends on no Java, as it starts nothing.
- **Their files go where Debian and Fedora put Java applications**:
  - `dog-vision-common`'s JARs in `/usr/share/dog-vision-common/lib`,
    and each window's other JARs in `/usr/share/<package>/lib`;
  - each window's native libraries in `/usr/lib/<package>`, where the FHS puts files that depend on the architecture;
    skiko and LWJGL load them from there instead of unpacking their own copies at run time:
    skiko's and LWJGL's for `dog-vision-compose`, only LWJGL's for `dog-vision-swing`;
  - the launchers, `dog-vision-compose`, `dog-vision-swing` and `dog-vision-cli`, in `/usr/bin`, on the `PATH`;
  - one manual page for all three, [`dog-vision.1`](../packaging/man/dog-vision.1), which `dog-vision-common` installs in `/usr/share/man/man1`,
    and a link to it under each command's name, such as `dog-vision-cli.1.gz`, in that command's package;
  - each window's menu entry, named in the system's language, and its icons in the hicolor theme:
    `cz.loplex.dogvision.compose.desktop` ("Dog Vision (Kotlin Compose)", "Jak vidí pes (Kotlin Compose)")
    and `cz.loplex.dogvision.swing.desktop` ("Dog Vision (Java Swing)", "Jak vidí pes (Java Swing)"),
    in `/usr/share/applications`;
  - the menu finds each entry by its name and by the words of `desktop_keywords` in [`texts/strings`](../texts/strings),
    such as "dog" and "colour blindness", also in the system's language.
- **Each deb's `/usr/share/doc/<package>/copyright` is in Debian's machine-readable format**:
  - the project's own files under `GPL-3+`, whose text is Debian's `/usr/share/common-licenses/GPL-3`;
  - a paragraph for each JAR and native library in the package that is not the project's own,
    with its copyright holders and licences;
  - each library's licence as its POM names it, and what a native library contains besides its own code,
    such as Skia and the libraries Skia builds in, from the table in [`ThirdParty.kt`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/licenses/ThirdParty.kt);
  - Apache-2.0's text is Debian's too;
    the text of every other licence, which names its holder, comes from [`packaging/licenses`](../packaging/licenses).
- **Each deb has `/usr/share/doc/<package>/changelog.gz`**, as Debian Policy asks of every package:
  one entry, for its version, dated by the last commit, so that a deb built again from the same commit is identical.
- **Each deb passes lintian with no errors, no warnings and no informational tags** (`lintian -I`);
  CI checks the debs for errors and warnings only.\
  These tags are overridden in `/usr/share/lintian/overrides/<package>`, each with the reason:
  - Compose's JARs that contain no classes;
  - skiko's library: unstripped, with zlib, libjpeg, FreeType and expat linked in, and misspelt words in its strings;
  - LWJGL's class files for Java 25, which lintian does not know;
  - LWJGL's natives: a `.comment` section, linked without `-z now`, and `liblwjgl.so` with no fortified functions;
  - ICU's licence, which gives the FSF's old address;
  - the architecture-independent JARs,
    which make up most of `dog-vision-compose`, `dog-vision-swing` and `dog-vision`, packages of one architecture;
  - the manual page `dog-vision.1`, which is not named after any command;
  - the page's `index.html`, which lintian takes for documentation.
- **Each rpm names the same licences**: its `License` is the SPDX expression of the project's licence and theirs,
  and `THIRD-PARTY-LICENSES.txt`, which lists each part with the full text of every licence,
  is a `%license` file next to `LICENSE` in `/usr/share/licenses/<package>`.
- **The web page's deb and rpm, `dog-vision-web`, need no Java** and contain no natives,
  so one package serves every architecture:
  - the page in `/usr/share/dog-vision-web`:
    `index.html`, `dog-vision.js` and `styles.css`, without the script's source map;
  - a command, `/usr/bin/dog-vision-web`, which opens that `index.html` in the system's browser through `xdg-open`,
    with its own manual page, [`dog-vision-web.1`](../packaging/man/dog-vision-web.1);
  - its menu entry, `cz.loplex.dogvision.web.desktop` ("Dog Vision (web)", "Jak vidí pes (web)"),
    which runs that command and is found by the same words as the windows' entries, and its icons.
- **The script's source map is a separate package, `dog-vision-web-sourcemap`**:
  `dog-vision.js.map` in `/usr/share/dog-vision-web`, next to the script installed by `dog-vision-web` or `dog-vision`,
  with which a browser's developer tools show the Kotlin sources.\
  It is only for debugging and is larger than the script, so the page's own packages leave it out.
- **`dog-vision` combines the five others in one package**,
  all but the source map, for the command line, both windows and the page at once:
  - their files, each where its own package installs it,
    so `dog-vision` has the same commands, menu entries and folders, except the licence's folder;
  - it conflicts with each of `dog-vision-common`,
    `dog-vision-cli`, `dog-vision-compose`, `dog-vision-swing` and `dog-vision-web`, and each of them with it,
    so that either it or they are installed, never both;
  - the debs also replace one another, as [Debian Policy 7.6.2](https://www.debian.org/doc/debian-policy/ch-relationships.html#replacing-whole-packages-forcing-their-removal) describes for a package that takes another's place,
    so `apt install` of one removes the others by itself;
  - the rpm does not obsolete them, as that would replace them with it on every update,
    so dnf installs it in place of the others with `--allowerasing`,
    and zypper asks which to remove, or removes them with `--force-resolution`;
  - `dog-vision-web-sourcemap` installs alongside it, as it does alongside `dog-vision-web`.
- **FlatLaf's natives stay inside its JAR** in `dog-vision-swing`:
  on Linux it loads them only for its own window decorations, which the window does not use, and they link GTK 3.
- **Each launcher looks for Java** in `JAVA_HOME`, then on the `PATH`,
  then the newest in `/usr/lib/jvm` and `/usr/lib64/jvm`, and says so when none of them is 17 or newer:
  the alternatives may point `java` at an older one on a machine that has a newer one as well.\
  The launcher is [`launcher.sh`](../build-logic/src/main/resources/cz/loplex/dogvision/packaging/linux/launcher.sh), filled in by the build.
- **A window's launcher skips a headless Java**, one without `lib/libawt_xawt.so`:
  if `dog-vision-cli` brought in a headless Java and a window a full one of another version,
  `java` may be the headless one.
- **The tar.gz and the MSI bring their own runtime**, Temurin's JDK 25,
  which jlink cuts down to the modules their launchers use:
  each tar.gz to those of the window and `dog-vision-cli`, the MSI to those of both windows and `dog-vision-cli`.\
  Temurin brings its own image and font libraries, which a distribution's OpenJDK takes from the system,
  so the tar.gz needs little more than glibc 2.28 or later, which LWJGL's natives require, X11 and ALSA,
  and for the Compose window also fontconfig and the C++ runtime, which skiko links.\
  You run it as `dog-vision-compose/bin/dog-vision-compose` or `dog-vision-swing/bin/dog-vision-swing`,
  without installing it.\
  The runtime keeps its own licence and notices in its `legal` folder.
- **Each tar.gz and the MSI contain `LICENSE` and `THIRD-PARTY-LICENSES.txt` in the app's folder**:
  the libraries, what their natives contain and, in the MSI, the web page's script, each with its licence,
  and the full text of every licence, as the Linux packages list them.
- **The MSI, `dog-vision`, installs both windows, the command line and the web page** into `Program Files\dog-vision`,
  each program a launcher on one shared runtime:
  `dog-vision-compose.exe`, `dog-vision-swing.exe` and `dog-vision-cli.exe`;
  the page, the same as the deb's, goes in a `web` folder there, and the script's source map is a separate part.\
  The installer and the system's list of programs call it *Dog Vision*.
- **Each JAR is in `app` once**, as Gradle resolves it, instead of being merged into an uber JAR per launcher:
  a JAR that more than one launcher runs on, such as the Kotlin standard library or LWJGL, is shared,
  and every other one belongs to the one launcher that runs on it.
- **The installer offers each window, the command line, the web page and the source map as a separate part**:
  a Feature in its tree under the runtime's Feature, which itself cannot be left out; the table below lists them.\
  Every part except the source map is selected by default,
  so a plain or silent install (`msiexec /qn`, winget) installs everything but the source map.\
  `ADDLOCAL` selects fewer:
  `msiexec /i dog-vision-0.1.0.msi ADDLOCAL=DogVision,SwingGui` installs only the Swing window;
  Windows adds `DogVision` by itself, Wine does not.\
  *Change* in the system's list of programs adds or removes parts later, as do `ADDLOCAL` and `REMOVE`.\
  A later version installed over it keeps the installed parts,
  and a part added later, or a later version, goes into the folder chosen at the first install,
  which the MSI reads from the registry.

| Feature        | In the tree                 | Installs                                             |
|----------------|-----------------------------|------------------------------------------------------|
| `DogVision`    | Dog Vision                  | the runtime and the shared JARs                      |
| `ComposeGui`   | GUI (Compose Multiplatform) | `dog-vision-compose.exe`, its JARs and its shortcuts |
| `SwingGui`     | GUI (Java Swing)            | `dog-vision-swing.exe`, its JARs and its shortcuts   |
| `WebGui`       | GUI (web browser)           | the folder `web`, and its shortcut                   |
| `WebSourceMap` | Source map, under the above | `web\dog-vision.js.map`, not selected by default     |
| `CommandLine`  | Command line                | `dog-vision-cli.exe`, and the folder on the `PATH`   |

- **Each window gets a shortcut in the Start menu and on the desktop**,
  *Dog Vision (Kotlin Compose)* and *Dog Vision (Java Swing)*, those in the Start menu in a *Dog Vision* group.\
  The command line gets none, as started from a shortcut it would only print its usage;
  instead `Program Files\dog-vision` is added to the end of the system's `PATH`, for consoles opened afterwards,
  and removed from it again when the MSI is uninstalled.
- **The web page gets a shortcut only in the Start menu's group**, *Dog Vision (web)*, with the app's icon,
  which opens `web\index.html` in the system's browser.
- **Uninstalling removes `%ProgramData%\Dog Vision`, where a window downloads ffmpeg**,
  and an upgrade keeps it.
- **[`packaging/windows/dog-vision.wxs`](../packaging/windows/dog-vision.wxs), the WiX source the MSI is made from, says what it does**,
  including its upgrade code, which stays the same from one version to the next,
  so that a later version's MSI replaces the installed one.

### The Linux packages' dependencies

- **A Java that can open a window**, or a headless one for `dog-vision-cli`:
  - the deb, `default-jre (>= 2:1.17) | java17-runtime`: the distribution's default JRE where it is 17 or newer,
    as the Debian Java Policy has it, and otherwise any JRE that provides `java17-runtime`,
    Temurin's included (Ubuntu 20.04's and 22.04's default is 11);
  - the rpm, `(jre-17 or jre-21 or jre-25)`, a rich dependency,
    because no single name is provided by every rpm JRE of 17 or newer and by no older one:
    Fedora's and Rocky's Java 8 provide `jre = 1:1.8.0`, whose epoch puts it above `jre >= 17`.\
    A new long-term release joins the list when a distribution makes it its default.
- **The libraries that the window's natives link against**, and libEGL, which LWJGL opens only at run time,
  and ffmpeg, which the window runs as a separate program for a video or the camera:
  - the deb names packages as Ubuntu 20.04 names them, which later releases keep or provide,
    so that the deb is the same wherever it is built;
    the table `debianPackages` in [`DebDepends.kt`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/linux/DebDepends.kt) names them, and the build fails on a library missing from it;
  - the rpm names libraries and a file (`libX11.so.6()(64bit)`, `libEGL.so.1()(64bit)`, `/usr/bin/ffmpeg` and the like),
    because Fedora and openSUSE name their packages differently.
- **A font, for the rpm of `dog-vision-swing`**, `(dejavu-sans-fonts or dejavu-fonts)`:
  Swing cannot start without one, and openSUSE's JRE brings none, while on Debian fontconfig brings one.\
  It names DejaVu as Fedora and Rocky or openSUSE package it, rather than `font(:lang=en)`:
  openSUSE's `xorg-x11-fonts-core` provides that one with bitmap fonts only, which Java does not read.\
  The Compose window draws its text through skiko and needs no font package.
- **Only `xdg-utils`, for `dog-vision-web`**, whose command runs `xdg-open`:
  the browser is whichever one the desktop has, and the windows' packages require no desktop either.
- **The others' dependencies together, for `dog-vision`**: those of both windows and of the page;
  a Java that can open a window serves the command line too.\
  It does not depend on `dog-vision-common`, whose JARs it contains.
- **`dog-vision-web` or `dog-vision` of its own version, for `dog-vision-web-sourcemap`**,
  as the map describes that one script, which either of them installs.
- **ffmpeg, which `dog-vision-cli` recommends** for videos:
  `ffmpeg` in the deb, `/usr/bin/ffmpeg` in the rpm, as the windows' rpms name it.
- **Nothing else, and no install scripts**:
  the menu entry and the icons are plain files of the package, which the desktop finds by itself.

The tasks that build them, [`DebPackage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/linux/DebPackage.kt) and [`RpmPackage`](../build-logic/src/main/kotlin/cz/loplex/dogvision/packaging/linux/RpmPackage.kt) in [`build-logic`](../build-logic),
call `dpkg-deb` and `rpmbuild` directly, because jpackage always puts a runtime into its packages.

### Trying the Linux packages

- **[`tools/test_deb.sh`](../tools/test_deb.sh) installs a deb in a bare container**,
  checks what it does there, and checks that removing it leaves nothing behind:
  - for `dog-vision-compose`, `dog-vision-swing` and `dog-vision-cli`, installed with whichever Java apt picks,
    that each runs from the `PATH`, and that the command line converts [`test_photo.jpg`](../tools/test_photo.jpg);
  - for each window, also that it opens on that photo under Xvfb,
    which is installed only after the deb's own dependencies, so that its X libraries cannot hide one the deb lacks,
    and that skiko, LWJGL and FlatLaf unpacked no natives of their own into the home or `/tmp`;
  - for `dog-vision-web`, which no browser opens there,
    that its menu entry is valid according to `desktop-file-validate`,
    that `dog-vision-web` runs from the `PATH`, and that the file it opens with `xdg-open` exists;
  - for `dog-vision-web-sourcemap`, that the map lies next to the script, and that removing it leaves the page;
  - for `dog-vision`, all of the above for the five packages it contains.
  - The project's own debs that a deb depends on at its own version are in no repository,
    `dog-vision-web` for `dog-vision-web-sourcemap`, the first of its alternatives,
    and `dog-vision-common` for the command line and both windows,
    so the script installs them together with the deb, from the deb's own folder.
  - It passes on Ubuntu 20.04, 22.04 and 24.04 and on Debian 12.\
    Debian 11 is left out: its support ended in August 2026, and its repositories moved to archive.debian.org.
- **[`tools/test_rpm.sh`](../tools/test_rpm.sh) does the same for an rpm**, with dnf, zypper or microdnf;
  it passes on Fedora 42, openSUSE Leap 15.6 and Rocky Linux 9's minimal image, whose repositories lack ffmpeg.
- **With `--upgrade <a later package>`, each script installs that package over the first** before removing it,
  as an update does, and checks that the later version is the one installed and that it does what the first did.\
  The later package is the same build with `-PappVersion=0.1.1`,
  which the build writes next to the first; the Linux job of [`ci.yml`](../.github/workflows/ci.yml) builds it that way.
- **With `--switch <another package>`, each script then installs the other package after the first**:
  in its place where the two conflict, as `dog-vision` and the five it contains do;
  alongside it otherwise, as `dog-vision-compose` alongside `dog-vision-cli`,
  or `dog-vision` alongside `dog-vision-web-sourcemap`, since `dog-vision` contains the page the map is for.
  - It checks that the package manager removed every package the other one conflicts with,
    and that the only files of the first package left are those an installed package owns.
  - It then tests the other package as it tested the first, and the first again where it stays installed,
    as `dog-vision-web-sourcemap` does alongside `dog-vision`,
    and removes both at the end, the first one first, checking what each leaves behind.
  - `test_rpm.sh` switches with dnf's `--allowerasing` and zypper's `--force-resolution`, but not with microdnf,
    which never removes one package to install another.
- **With `--temurin`, each script first installs Temurin's JRE from Adoptium's repository**,
  and checks that the package uses it and installs no OpenJDK beside it, as it does on Ubuntu 24.04 and Fedora 42.

### The MSI, built under Wine or on Windows

jpackage builds an app image for Windows only on Windows, so the MSI is built either under Wine on Linux,
by `package_msi_on_linux.sh`, or on Windows, by `package_msi_on_windows.ps1`; both build the same MSI.

- **On Windows, [`tools/package_msi_on_windows.ps1`](../tools/package_msi_on_windows.ps1) builds it**
  with the jpackage of the JDK 25 it runs on, and with WiX 3.14, whose light.exe validates it.
- **Under Wine, [`tools/package_msi_on_linux.sh`](../tools/package_msi_on_linux.sh) builds it**,
  with the workarounds for Wine that it and `package_app_image_on_linux.sh` describe.\
  [`tools/fetch_msi_tools_on_linux.sh`](../tools/fetch_msi_tools_on_linux.sh) downloads what it builds with, once:
  a Windows JDK 17, WiX 3.14 and Wine Mono, into `tools/cache`.\
  [`tools/make_wine_prefix_on_linux.sh`](../tools/make_wine_prefix_on_linux.sh) makes the Wine prefix it builds in, with Wine Mono for WiX to run on,
  in a few minutes: `mono_msi_builder`, in `~/.local/share/wineprefixes`, where winetricks keeps its named prefixes.\
  Wine cannot validate the MSI, so the workflow [`msi-under-wine.yml`](../.github/workflows/msi-under-wine.yml), started by hand,
  builds it on Linux, and validates, installs, upgrades and removes it on Windows.
- **Under Wine, [`tools/package_app_image_on_linux.sh`](../tools/package_app_image_on_linux.sh) builds the app image first**,
  the folder the MSI installs, in `tools/build/app-image`; `package_msi_on_linux.sh` runs it to get that folder.\
  Its `.exe` files run under Wine from there, without installing the MSI:
  `wine tools/build/app-image/dog-vision-compose/dog-vision-compose.exe`.
- **One app image holds all three launchers**, from a single run of jpackage,
  with the arguments that `:packaging`'s `windowsJpackage` task writes into `packaging/build/windows/jpackage`.\
  Each of `gui-compose`, `gui-swing` and `cli` hands it its launcher through its `windowsLauncher` task:
  its classpath, its main class, its Java options, and the modules the runtime needs.
- **A JAR of a given name is in the image only once**,
  and the `windowsJpackage` task fails if two launchers hand it different bytes under that name.\
  Two of the Compose window's JAR names are each used twice, by JetBrains' forwarding JAR and by androidx's,
  so each of these JARs gets its module's group in front of its name, as in the deb.\
  ANGLE's JAR is packed again without its natives for Windows on ARM, which the uber JARs leave out as well.
- **jpackage puts every JAR of the image on each launcher's classpath**,
  so the `windowsLauncherConfigs` task writes each launcher's `.cfg` with its own classpath, in its own order,
  and the scripts copy them into the image, including the image in the command line's zip.
- **Both scripts take the runtime from Gradle**, so that it is the same on Windows and under Wine:
  `./gradlew :packaging:windowsRuntime` links it on any system,
  with every module a launcher needs, like the runtime of the command line's zip above.
- **WiX makes the MSI from the project's own source**, not from jpackage's:
  the `windowsWix` task writes [`packaging/windows/dog-vision.wxs`](../packaging/windows/dog-vision.wxs) into `packaging/build/windows/wix`,
  with a component for each file of the app image, and the values the source takes,
  which the scripts pass to candle.exe and light.exe.\
  Each launcher's files, its `.exe`, its `.cfg` and the JARs on its classpath that no other launcher uses,
  are a component group of their own, such as `Launcher.dog_vision_swing`, which that launcher's Feature takes;
  everything else is the group `Files`, in the runtime's Feature.\
  The web page, which jpackage's image does not contain, comes from Gradle as for the deb,
  into the group `Web`, and its script's source map into `Web.SourceMap`.
- **[`tools/test_msi_on_windows.ps1`](../tools/test_msi_on_windows.ps1) tests it on a throwaway Windows machine**:
  it installs the MSI, runs the command line, also from the `PATH`,
  checks the shortcuts, including the web page's target, installs a later version over it and removes that,
  and checks that the installation folder is on the `PATH` once, and off it afterwards.\
  Then it installs only the Swing window, with `ADDLOCAL=SwingGui`, into a folder of its own, adds the Compose window,
  and checks that the later version keeps both there, without the command line and the web page.
- **The licence dialog shows [`LICENSE`](../LICENSE) with its own line breaks**,
  from the RTF that the `windowsLicense` task writes, since the GPL's lines are broken by hand.
- **The dialogs show the dog**,
  on bitmaps that the `windowsBitmaps` task draws from the icon to replace WiX's:
  the banner across the top of most dialogs,
  and a panel on the left of the first and the last one, in the icon's background colour.
- **The MSI's code page is Windows-1250**,
  because the vendor's name has an ř, which the Windows-1252 of WiX's own English strings lacks:
  [`packaging/windows/codepage.wxl`](../packaging/windows/codepage.wxl) says how.
