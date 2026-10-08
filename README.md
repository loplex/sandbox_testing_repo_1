# Inspect Code with Filters

Filter what Inspect Code runs and reports in JetBrains IDEs: by severity, inspection group, changed lines and more.

An IntelliJ Platform plugin that adds **Inspect Code with Filters…** next to **Inspect Code…**.\
It runs the same inspections over the same scope, but reports only those of the severities, groups and kinds of
inspection chosen in its dialog.\
Of their problems it can also report only those on lines changed since the last commit, with a quick fix, or with a
given text.
For example:

- errors and warnings, without the weak warnings and typos around them;
- everything but Proofreading;
- only the batch-mode inspections, which the editor never shows;
- only the problems whose message mentions `Optional`;
- only the problems on the lines changed since the last commit.

The inspections left out are not run at all, so a filtered run is also a shorter one.\
The profile itself is not changed: the run uses a copy of it with the other inspections switched off.

- [Using it](docs/usage.md) — where the action is, the dialog, what each filter reports, and a known issue with
  Grazie in IntelliJ IDEA 2025.3.
- [Installing](#installing) — requirements, building the plugin archive.
- [Developing it](docs/development.md) — running, testing, UI tests in a real IDE, and checking it by hand in the
  sandbox IDE, on a display of its own.
- [CI and releasing](docs/releasing.md) — the workflows, the secrets they need, and cutting a release.
- [License](#license) — AGPL-3.0-or-later.

## Installing

The plugin needs an IntelliJ-based IDE of version 2025.3 (build 253) or later.\
It is built and tested against IntelliJ IDEA 2025.3.5.

It is not published to JetBrains Marketplace; build the archive from the sources:

```sh
./gradlew buildPlugin
```

Then install `build/distributions/ij-inspection-filter-<version>.zip` with
**Settings | Plugins | ⚙ | Install Plugin from Disk…**.

Gradle 9 itself needs a JDK 17 or later to run.\
The JDK 21 the plugin is compiled with is downloaded if none is found.

## License

Copyright (C) 2026 Martin Lopatář

GNU Affero General Public License, version 3 or any later version; see [LICENSE](LICENSE).\
The plugin uses only APIs of the open-source IntelliJ Platform,
[intellij-community](https://github.com/JetBrains/intellij-community), which is under the Apache License 2.0.
