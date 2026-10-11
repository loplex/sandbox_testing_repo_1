#!/usr/bin/env bash
# Deletes everything the build has made in the repository, so that the next build starts over:
# every module's build directory, and build-logic's too, which the root build's clean leaves alone
# because build-logic is a separate build;
# in both builds, .gradle, Gradle's project cache, which holds the configuration cache that no Gradle task clears,
# and .kotlin, the Kotlin daemon's session files.
# Gradle recreates .gradle on the next build.
# It also deletes tools/build, where the scripts in tools put what they build, which no Gradle task owns,
# but leaves tools/cache, where those scripts keep their downloads.
#
# It leaves Gradle's build cache in the home directory, and the next build restores each task's outputs from it;
# pass --no-build-cache or --rerun-tasks to that build to run the tasks anyway.
# It leaves the downloaded dependencies and JDKs there as well.
#
# Run it from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# gradle clean - but without the configuration cache
#"${root:?}/gradlew" --project-dir "$root" --quiet --no-configuration-cache 'clean' ':build-logic:clean'

# find '.../build' dirs
declare -a build_dirs; readarray -t build_dirs < <(
    find "${root:?}" -type f -name 'build.gradle.kts' -printf '%h/build\n'
)

rm -rf "${build_dirs[@]}" "${root:?}"{,/build-logic}/{.gradle,.kotlin} "${root:?}/tools/build"
