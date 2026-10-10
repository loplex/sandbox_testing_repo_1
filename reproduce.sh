#!/usr/bin/env bash
# Builds project/ twice in one Gradle daemon, as a developer or a CI job that builds commit after commit would:
#   build 1: `check` on project/ as it is; :a:detektMainJvm reads lib's jar.
#   then:    lib gains repro.lib.New, and b depends on lib and uses New (the files of step2/).
#   build 2: `check` again; :b:detektMainJvm should resolve New from lib's rebuilt jar.
# The builds run in a copy, build/work, with a build cache and a daemon of their own; the logs go to build/logs.
#
# Usage: ./reproduce.sh [--second-only] [--clean-between] [--keep-daemon] [gradle argument...]
#   --second-only    skips build 1: build 2 alone, in a fresh daemon.
#   --clean-between  removes the build's outputs before build 2 (build/, .gradle/, .kotlin/), as `git clean` would.
#   --keep-daemon    leaves the daemon running; otherwise it is stopped at the end.
# Every other argument goes to both builds, e.g. --no-daemon or -Pdetekt.use.worker.api=true.
set -u

here=$(cd "$(dirname "$0")" && pwd)
second_only=false clean_between=false keep_daemon=false
gradle_args=()
for arg in "$@"; do
    case $arg in
        --second-only) second_only=true ;;
        --clean-between) clean_between=true ;;
        --keep-daemon) keep_daemon=true ;;
        *) gradle_args+=("$arg") ;;
    esac
done

work=$here/build/work
logs=$here/build/logs
rm -rf "$work" "$logs"
mkdir -p "$work" "$logs"
cp -R "$here/project/." "$work/"
export REPRO_BUILD_CACHE=$here/build/work-build-cache
rm -rf "$REPRO_BUILD_CACHE"

# A JVM argument no other build has, so that the builds get a daemon of their own and share it.
token=$(date +%s)-$$
sed -i.bak "s/^org.gradle.jvmargs=.*/& -Drepro.run=$token/" "$work/gradle.properties" && rm "$work/gradle.properties.bak"

step2() {
    cp -R "$here/step2/." "$work/"
}

build() {
    local name=$1
    (cd "$work" && ./gradlew check --continue --init-script "$here/repro.init.gradle" ${gradle_args[@]+"${gradle_args[@]}"}) \
        > "$logs/$name.log" 2>&1
    local status=$?
    local probe errors
    probe=$(grep -o 'REPRO-PROBE .*' "$logs/$name.log" | tail -n 1)
    errors=$(grep -c 'compiler errors found during analysis' "$logs/$name.log")
    echo "$name: exit=$status ${probe#REPRO-PROBE } detekt-tasks-with-compiler-errors=$errors" | tee -a "$logs/summary.txt"
}

if [ "$second_only" = false ]; then
    build build-1
    step2
    if [ "$clean_between" = true ]; then
        rm -rf "$work"/build "$work"/*/build "$work"/.gradle "$work"/.kotlin
    fi
else
    step2
fi
build build-2

# What lib's jar held at the end, and the compiler errors build 2's detekt tasks reported.
for jar in "$work"/lib/build/libs/*.jar; do
    [ -f "$jar" ] && (cd "$(dirname "$jar")" && ls -l "$(basename "$jar")" && unzip -l "$(basename "$jar")") \
        >> "$logs/build-2.log" 2>&1
done
grep -E "There were [0-9]+ compiler errors|error: unresolved reference|FAILED" "$logs/build-2.log" | sed 's/^/  /'

if [ "$keep_daemon" = false ]; then
    grep -ho 'REPRO-PROBE pid=[0-9]*' "$logs"/build-*.log | sort -u | while read -r _ pid; do
        kill "${pid#pid=}" 2> /dev/null
    done
fi

if grep -q 'compiler errors found during analysis' "$logs/build-2.log"; then
    echo "RESULT: reproduced: detekt found compiler errors in build 2" | tee -a "$logs/summary.txt"
else
    echo "RESULT: not reproduced" | tee -a "$logs/summary.txt"
fi
