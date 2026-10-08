# Developing Inspect Code with Filters

| Command                  | Does                                                                |
|--------------------------|---------------------------------------------------------------------|
| `./gradlew runIde`       | starts a separate IDE with the plugin installed, in its own sandbox |
| `./gradlew test`         | runs the tests, which include real **Inspect Code** runs            |
| `scripts/ui-tests.sh`    | runs the UI tests in a real IDE                                     |
| `scripts/sandbox.sh`     | runs the sandbox IDE on a display of its own, to check it by hand   |
| `./gradlew verifyPlugin` | checks binary compatibility with the recommended IDE builds         |

`verifyPlugin` downloads each IDE build it checks.

## UI tests in a real IDE

`scripts/ui-tests.sh` runs `./gradlew integrationTest`: the tests in `src/integrationTest`, which start the IDE with the
plugin installed and drive it with JetBrains'
[Starter and Driver](https://plugins.jetbrains.com/docs/intellij/integration-tests-intro.html).\
They check what the tests of `./gradlew test` cannot reach, such as a key pressed in a popup of the dialog.\
[Remote Robot](https://github.com/JetBrains/intellij-ui-test-robot), the older way to do this, is deprecated in their
favor.

```sh
scripts/ui-tests.sh
X_SERVER=xephyr scripts/ui-tests.sh --tests '*FilterPopupUiTest*'
```

- The IDE opens on [a display of its own](#the-display-of-the-scripts), a virtual one unless `X_SERVER` says otherwise.
- In CI too, the script is all it takes: it starts the virtual display itself.
- Arguments go on to Gradle.
- Where `$DISPLAY` is already the one to use, `./gradlew integrationTest` runs the tests just as well.
- `FilterPopupUiTest` starts one IDE for all its tests, and that takes a minute or so.
- Before each of its tests, it forgets the filter stored in the project, so that each starts from nothing chosen.
- The IDE is the one the plugin is built against, run from where Gradle unpacked it: Starter downloads none.
- The IDE's configuration, logs and reports go to `build/ui-tests`.

## Checking it by hand in the sandbox IDE

`scripts/sandbox.sh` runs the sandbox IDE of `runIde` on [a display of its own](#the-display-of-the-scripts).\
There it can be driven with `xdotool` and looked at in screenshots without taking over the desktop - by an agent, for
instance.

It needs `xdotool`, `wmctrl` and ImageMagick.

```sh
scripts/sandbox.sh start build/manual-test/some-project
export DISPLAY=$(cat build/manual-test/display)
scripts/sandbox.sh shot results 1880x340+36+735
scripts/sandbox.sh stop
```

- **start** opens the project and maximizes the IDE's window, and ends with the `export DISPLAY=…` to drive it with.
  It refuses while another sandbox IDE runs, as the two would share one configuration directory.
- **shot** saves a screenshot, cropped when given a geometry, to `build/manual-test/shots`.
- **stop** closes the IDE as **File | Exit** does, and then the display.
- **shot** and **stop** find the display in `build/manual-test/display`; logs go to `build/manual-test` too.

## The display of the scripts

Both scripts start an X display for the IDE, and `X_SERVER` picks which:

| `X_SERVER`         | The IDE opens on                       | Meant for                         |
|--------------------|----------------------------------------|-----------------------------------|
| `xvfb`, by default | a virtual display, which shows nothing | a run, on a desktop or in CI      |
| `xephyr`           | a window of its own on the desktop     | watching what the IDE does        |
| `desktop`          | the desktop itself, `$DISPLAY`         | a run with nothing else on screen |

- The display started is the first one free, or `:X_DISPLAY_NUMBER` when that is set.
- Either way, it is written to `display` in the script's directory under `build`, such as `build/ui-tests/display`.
- `X_SCREEN` picks its size, 1920x1080 by default.
- `X_HOST_INPUT=off` keeps the desktop's mouse and keyboard off Xephyr's display, which a mouse moved over its window
  would otherwise drive too. Only XTEST, through which the UI tests click and type, reaches it then. It needs `xinput`.
- `scripts/ui-tests.sh` has `X_HOST_INPUT` off unless it is set, `scripts/sandbox.sh` on, so the sandbox IDE can be
  used by hand.
- Xephyr runs with `-no-host-grab`: Ctrl+Shift over its window does not take over the whole desktop's mouse and
  keyboard.
- It needs Xvfb or Xephyr; Xephyr opens its window on the desktop's `$DISPLAY`.
- Openbox, when installed, is started on it as the window manager: without one, nothing places or focuses the windows.
- The display's logs go to the same directory.
- The IDE starts without `WAYLAND_DISPLAY`, so that one able to run on Wayland opens on the display chosen all the same.
- `scripts/x-display.sh` is where both scripts start it.

### What gets in the way when driving it

- **The project to open**: a copy under `build/manual-test`, such as `git clone --depth 1 file://…`, can be changed
  freely - to give **On lines changed since the last commit** something to report, for instance.
  One on a tmpfs, where `/tmp` often is, did not import: Maven reported invalid virtual files and found no modules.
- **Opening the action**: from the **Code** menu. **Find Action** answers slowly while the project is indexed.
- **Clicking**: a dialog does not always open at the same place, so take a screenshot before clicking into it.
- **Popup menus**: an item, such as **XML** under **Export** in the results, takes a click only after the pointer has
  rested on it. Clicked straight away, the menu just closes.
- **Typing**: keys go wherever the focus is when the expected dialog has not opened. In the project tree, **Enter**
  opens the files selected.
- **Reading the results**: export them to XML from the results' toolbar, and read the file of each inspection, such as
  `unused.xml`. That is exact, where reading the tree off a screenshot is not.
- **The error after an export**: in IntelliJ IDEA 2025.3.5, every XML export logs "Descriptions are missed for tools"
  for database inspections, and the IDE reports an error. Plain **Inspect Code** does the same, so it is not the
  plugin's.
- **The plugin icon in the other theme**: **Settings | Plugins** keeps the variant of the theme the IDE started in, so
  after switching the theme, restart the IDE before looking. In a light theme, the selected row also blends the icon
  into its background, so compare the colors in a row that is not selected.

For what a test can check on its own, see [UI tests in a real IDE](#ui-tests-in-a-real-ide).
