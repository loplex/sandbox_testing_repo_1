#!/usr/bin/env bash
# Deletes everything the build has made in the repository, so that the next build starts over: each
# module's build directory, build-logic's too, which the root build's clean leaves, as it is a build
# of its own, and in both builds .gradle, Gradle's project cache, with the configuration cache, for
# which Gradle has no task, and .kotlin, the Kotlin daemon's session files. Gradle makes .gradle
# again on the next build. tools/build too, what the scripts in tools build, which no Gradle task
# owns; tools/cache, what they download, it leaves.
#
# It leaves Gradle's build cache in the home, from which the next build takes back each task's
# outputs; --no-build-cache or --rerun-tasks on that build runs the tasks all the same. It leaves
# the downloaded dependencies and JDKs there as well.
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
