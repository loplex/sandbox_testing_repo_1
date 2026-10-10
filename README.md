# detekt sees a rebuilt project jar as an earlier build in the same Gradle daemon wrote it

A reproducer for detekt 2.0.0-alpha.6 (`dev.detekt`), Kotlin Gradle plugin 2.4.20, Gradle 9.8.0.

- [What it does](#what-it-does)
- [Running it](#running-it) — `./reproduce.sh`, and what it prints.
- [Results on GitHub Actions](#results-on-github-actions) — which variants fail and which pass.
- [Why, as far as the sources tell](#why-as-far-as-the-sources-tell) — `kotlin.environment.keepalive`,
  `CoreJarFileSystem`.

## What it does

`project/` is three `kotlin("jvm")` modules with Gradle's and the Kotlin Gradle plugin's defaults:
`lib`, `a`, which depends on `lib`, and `b`, which does not yet.\
`reproduce.sh` builds it twice in one Gradle daemon:

1. Build 1: `./gradlew check`; `:a:detektMain` analyses `a` with `lib/build/libs/lib.jar` on its classpath.
2. Between the builds, the files of `step2/` are copied in:
   `lib` gains the class `repro.lib.New`, and `b` depends on `lib` and uses `New` and `repro.lib.Old`.
3. Build 2: `./gradlew check` again.
   `:b:compileKotlin` passes; `:b:detektMain` reports `New` as unresolved, while `Old`, in the jar since build 1,
   resolves.

The classpath detekt prints in debug mode holds `lib.jar`, and the jar on disk holds `New.class`.

## Running it

```
./reproduce.sh [--second-only] [--clean-between] [--keep-daemon] [gradle argument...]
```

- The builds run in a copy, `build/work`, in a Gradle daemon of their own, which the script stops at the end.
- Each build's whole output is in `build/logs/build-1.log` and `build/logs/build-2.log`.
- `-Prepro.detektDebug=true` turns on detekt's `debug`, which prints the classpath and the compiler errors.
- Any other argument goes to both builds: `--no-daemon`, `-Pdetekt.use.worker.api=true`, `--configuration-cache`.

A failing run prints:

```
build-1: exit=0 pid=2147 kotlin.environment.keepalive=true detekt-tasks-with-compiler-errors=0
build-2: exit=0 pid=2147 kotlin.environment.keepalive=true detekt-tasks-with-compiler-errors=1
  There were 3 compiler errors found during analysis. This affects accuracy of reporting.
RESULT: reproduced: detekt found compiler errors in build 2
```

- `pid` is the JVM each build ran in, so the same number twice means one daemon.
- `kotlin.environment.keepalive` is that JVM's system property at the end of the build, printed by `repro.init.gradle`.
- With `-Prepro.detektDebug=true`, the errors are named:
  `B.kt:3:1: error: unresolved reference 'New'.` and two more in the function that uses it.

### Caveat: the configuration cache hides it

With the configuration cache on, build 2 passes (see [Results](#results-on-github-actions)).\
A user-wide `org.gradle.configuration-cache=true` in `~/.gradle/gradle.properties` wins over the project's settings,
so on such a machine pass `--no-configuration-cache`; the script says so when build 1 ends without the property set.

## Results on GitHub Actions

Each run is one job on a fresh `ubuntu-24.04` runner with Temurin 25, from
[`.github/workflows/reproduce.yml`](.github/workflows/reproduce.yml); the counts are over three runs of it,
[38058670198](https://github.com/loplex/sandbox_testing_repo_1/actions/runs/38058670198),
[38058863884](https://github.com/loplex/sandbox_testing_repo_1/actions/runs/38058863884) and
[38059137887](https://github.com/loplex/sandbox_testing_repo_1/actions/runs/38059137887).

| Arguments                                           | Build 2 fails |
|-----------------------------------------------------|---------------|
| none                                                | 9 of 9        |
| `-Prepro.detektDebug=true`                          | 3 of 3        |
| `-Pkotlin.compiler.execution.strategy=in-process`   | 2 of 2        |
| `--build-cache`                                     | 2 of 2        |
| `--no-daemon`                                       | 0 of 3        |
| `--second-only`                                     | 0 of 3        |
| `-Pdetekt.use.worker.api=true`                      | 0 of 5        |
| `--configuration-cache`                             | 0 of 2        |
| `--clean-between`                                   | 0 of 5        |
| as the original project builds (below)              | 0 of 2        |

- "none" counts the first run's jobs whose arguments only restated the defaults (`--no-build-cache`,
  `--no-configuration-cache`, `-Pkotlin.compiler.execution.strategy=daemon`).
- "As the original project builds" is how the project the bug was found in is built, commit after commit in one
  clone: `--clean-between --configuration-cache --build-cache -Pkotlin.compiler.execution.strategy=in-process`.
- Why `--clean-between` passes is not known.

### detekt's main branch

[`.github/workflows/detekt-main.yml`](.github/workflows/detekt-main.yml) builds detekt at
[`641d9b3150`](https://github.com/detekt/detekt/commit/641d9b31501776eb9a5c5b86141a313d83fc540c),
publishes it to Maven Local as `main-SNAPSHOT`, and points `project/` at it.\
In [38059137939](https://github.com/loplex/sandbox_testing_repo_1/actions/runs/38059137939),
build 2 failed in both jobs without arguments and passed with `--no-daemon`.

## Why, as far as the sources tell

What was observed above, and what was read in the sources, without a debugger:

- Without the Worker API, detekt's Gradle plugin runs detekt inside the Gradle daemon, in a class loader it keeps
  for the daemon's lifetime, so the Kotlin compiler's static state outlives a build.
- detekt opens a `StandaloneAnalysisAPISession` per task and disposes it at the end.
  The sessions share one application environment, `KotlinCoreEnvironment.getOrCreateApplicationEnvironment`, which is
  disposed when the last project closes, unless `kotlin.environment.keepalive` was `true` when that project was
  created.
- The Kotlin Gradle plugin sets `kotlin.environment.keepalive=true` in every compile task,
  `AbstractKotlinCompile.execute` (`AbstractKotlinCompile.kt:249` in 2.4.20).
  Without the configuration cache that is the daemon's system property, which detekt's copy of the compiler reads too;
  with it, the plugin keeps the value in a map of its own, and the property stays unset.
- So, with the configuration cache off, the application environment outlives the build
  when the last detekt task to close was created after a compile task had set the property.
- The Analysis API finds a library jar through the environment's `CoreJarFileSystem`, which keeps one
  `CoreJarHandler` per path; the handler reads the jar's entries once, when it is created.
  When the environment survives, it is not cleared: `idleCleanup()` clears only the `FastJarFileSystem`.
  Whether that handler or `KotlinStandaloneIndexCache`, keyed by the jar's root, is what serves the old entries
  was not checked.
- With the Worker API, detekt runs in a worker process, where the Kotlin Gradle plugin does not set the property.
