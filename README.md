# dog-vision for Android

*The phone's camera, or a photo, shown with the colours a dog — or another animal — can tell apart.*

This is the Android version of [dog-vision](https://github.com/loplex/dog-vision), a desktop
program in Python.
It simulates the same species with the same model, and the desktop program's README explains that
model and the science behind it.

- [Using the app](#using-the-app) — the camera, a photo, the controls, saving, the language.
- [How it differs from the desktop program](#how-it-differs-from-the-desktop-program) — what is
  not there yet, and what works the Android way.
- [The model](#the-model) — where the explanation of the simulation lives.
- [Building it](#building-it) — the JDK, the Android SDK, `./gradlew`.
- [How the code is laid out](#how-the-code-is-laid-out) — `core`, `android`, the GPU renderer.
- [Checking it](#checking-it) — `:core:test`, `:android:connectedDebugAndroidTest`, `:android:lintDebug`,
  `tools/check_links.py`, `tools/reference_values.py`.
- [License](#license)

## Using the app

The app opens on the back camera, with the scene as it is and as a dog sees it.

- **The images are laid out to show them largest**: side by side on a wide screen, one above another
  on a tall one, each with a caption under it saying what it shows.
- **The controls are beside the images on a wide screen and under them on a tall one.** The button
  with three sliders over the images hides or shows them.
- **Each control and each fact has an info button** that says what it means, in a few paragraphs.

### The controls

The controls are grouped in sections, as in the desktop window, and a tap on a section's title opens
or closes it.

| Control                       | Does                                                     |
|-------------------------------|----------------------------------------------------------|
| *Species*                     | picks the animal                                         |
| *Selected species*            | its cones, their sources, its RNL factors and its acuity |
| *Adaptation to scene [%]*     | adapts the cones to the scene instead of to daylight     |
| *Simulation strength [%]*     | blends the simulation with the original                  |
| *Colour saturation*           | how saturated the colours come out                       |
| *Blur to the species' acuity* | removes the detail finer than the species resolves       |
| *Image spans [degrees]*       | how wide the image is in degrees, which sets the blur    |
| *Side by side*                | a second image beside the simulation, or not             |
| *Compared with*               | the original, or another species, in that second image   |
| *Map of differences*          | a third image marking where the first two differ         |
| *Reset*                       | every control back to where it started                   |
| *Language*                    | the app's language; *As the system* follows the phone    |

### A photo instead of the camera

- **The picture button opens a photo** through the system's photo picker, which needs no storage
  permission.
- **A photo is shown scaled down** to 1280 pixels on its longest side, so that the controls stay
  quick, and turned as its EXIF orientation says.
- **The camera button goes back to the camera.**
- **A photo can be opened without allowing the camera**, which the app asks for only to show it.

### Saving

Both kinds of file go to *Pictures/Dog vision*, where the gallery shows them:

- **The arrow button saves a snapshot** of the view as shown, at the size the images are rendered:
  the camera's resolution, or a photo's scaled-down view.
  It is called `dog-<species>-<time>.png`, or `dog-<left>-vs-<species>-<time>.png` when another
  species is compared.
- **The corners button saves the photo at its full size**, as the view shows it now, with the same
  name ending in `-full.png`.
  It is converted on the CPU, and a message says how far it has got.

Before Android 10, saving asks for the storage permission.

### Language

- **The app speaks English and Czech**, with the desktop program's Czech texts.
- **It starts in the phone's language**, and in English when the phone's is neither.
- **The *Language* choice at the end of the controls overrides it**, and so does the per-app
  language setting of Android 13 and later; Android remembers the choice.

## How it differs from the desktop program

### Not there yet

- **Video**: converting a video, and recording the view to one.
- **The front camera**: the app shows the back one only.

### The Android way

- **Files go to the gallery**, not to an output folder chosen in the app.
- **A photo converted at full size is named like a snapshot**, not `<name>.dog.png` after the
  photo: the system photo picker does not tell an app a photo's file name.
- **The second image's choice is called *Compared with***, not *Left image*: on a tall screen it is
  the top one.
- **Tooltips are info buttons**, since a touch screen has no pointer to rest.
- **There is no command line**, so *Reset* returns to the app's defaults, and `--info` has no
  counterpart.

## The model

The simulation is the desktop program's, ported to Kotlin in [`core`](core), and the desktop
README explains it:

- [How it works](https://github.com/loplex/dog-vision#how-it-works) — the model in five steps.
- [Species](https://github.com/loplex/dog-vision#species) — every species, its cone peaks and their
  sources.
- [Colour saturation](https://github.com/loplex/dog-vision#colour-saturation) — the fixed and the
  RNL scale.
- [Acuity](https://github.com/loplex/dog-vision#acuity) — the blur, and why it needs the field of
  view.
- [Map of differences](https://github.com/loplex/dog-vision#map-of-differences) — what the red
  means.
- [What it cannot show](https://github.com/loplex/dog-vision#what-it-cannot-show) — its limits.
- [References](https://github.com/loplex/dog-vision#references) — every source in full.

## Building it

The build needs:

- **a JDK 17 or newer**; Gradle itself is fetched by the wrapper;
- **the Android SDK with platform 36**, found through `ANDROID_HOME` or `sdk.dir` in
  `local.properties`, which Android Studio and IntelliJ IDEA write when they open the project.

```sh
./gradlew :android:assembleDebug     # android/build/outputs/apk/debug/android-debug.apk
./gradlew :android:installDebug      # onto the connected phone or emulator
```

The phone needs Android 8.0 (API 26) or later, and OpenGL ES 3.0.

### Caveat: the libraries wait for a stable SDK 37

The Compose BOM stays at 2026.06.01, `androidx.core` at 1.18 and `lifecycle` at 2.10, and the app
targets API 36: the releases after them need `compileSdk` 37, which has no stable SDK platform yet.
Android Lint says so in its warnings, beside one that AGP is not the newest, which stays at 9.1.1
for IntelliJ IDEA's Android plugin.

## How the code is laid out

- **[`core`](core)** is plain Kotlin on the JVM, with no Android in it: the species, the model, the
  image pipeline, the facts and the layout of the images.
  They are what the desktop program's `dog_vision.core` holds, less video and the window's session.
- **[`android`](android)** is the Android app: the camera, the photos, the GPU renderer, saving, and the
  Compose screens.

### The camera is rendered on the GPU, and a photo at full size on the CPU

- **The live view is drawn by OpenGL ES 3.0 shaders** in
  [`ViewRenderer`](android/src/main/kotlin/cz/loplex/dogvision/render/ViewRenderer.kt), which apply
  `core`'s matrix and blur.
  They decode and encode sRGB through `core`'s own lookup tables, so that they agree with it to the
  rounding of a float.
- **A photo saved at full size goes through `core`'s CPU pipeline**, a strip of rows at a time,
  which has no limit on the size of a texture.

## Checking it

- `./gradlew :core:test` runs the model's tests on the JVM: the desktop program's invariants, ported
  from its pytest suite, and a comparison with what the desktop program computes.
- `./gradlew :android:connectedDebugAndroidTest` runs
  [`ViewRendererTest`](android/src/androidTest/kotlin/cz/loplex/dogvision/render/ViewRendererTest.kt) on
  a connected phone or emulator, which holds the GPU renderer to `core`'s CPU pipeline on that
  device's GPU.
- `./gradlew :android:lintDebug` runs Android Lint.
- `python3 tools/check_links.py` checks that every relative link in the Markdown resolves, down to
  its anchor.

### The reference values come from the desktop program

[`tools/reference_values.py`](tools/reference_values.py) writes the files in
[`core/src/test/resources`](core/src/test/resources) from a checkout of the desktop program:
its matrices, RNL factors and neutral points, images it renders from a test pattern, and the facts
of every species.
Its docstring says how to run it; the tests fail when `core` stops matching them.

## License

dog-vision for Android is free software under the GNU General Public License, version 3 or any
later version (`GPL-3.0-or-later`), as the desktop program is; the full text is in
[`LICENSE`](LICENSE).
