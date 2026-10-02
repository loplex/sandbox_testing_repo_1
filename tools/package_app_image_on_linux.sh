#!/usr/bin/env bash
# Builds the app images for Windows on x86-64 on Linux, through Wine: each the folder an MSI
# installs, with its launchers, its JAR and a runtime of its own, which runs under Wine as it is,
# without being installed. Without --window all three, one after the other; with --window compose
# dog-vision's, the Compose window's, with dog-vision.exe and dog-vision-cli.exe; with --window
# swing dog-vision-swing's, with dog-vision-swing.exe and dog-vision-cli.exe; with --window cli
# dog-vision-cli's, with dog-vision-cli.exe alone, which runs in a console.
#
# Each is written to the module's build/windows/app-image, as gui-compose/build/windows/app-image/
# dog-vision, and its folder printed. tools/package_msi_on_linux.sh makes the MSIs from them, and
# tools/package_cli_zip_on_linux.sh the command line's zip.
#
# jpackage makes an app image for Windows only on Windows, so this runs a Windows JDK 17's
# jpackage.exe under Wine, in the locale cs_CZ.UTF-8, as tools/package_msi_on_linux.sh says why,
# over the JAR that the module's windowsUberJar task assembles, or for the command line its
# uberJar task, and the runtime that its windowsRuntime task links. An app image needs neither WiX
# nor .NET.
#
# Needs:
# - Wine. Its prefix is WINEPREFIX's, or without it dot_net_msi_builder among winetricks' named
#   prefixes where that is there, which tools/make_wine_prefix_on_linux.sh makes for the MSIs, and
#   else Wine's own.
# - The locale cs_CZ.UTF-8.
# - --jdk: a Windows JDK 17, unpacked, for its bin/jpackage.exe; without it, the one that
#   tools/fetch_msi_tools_on_linux.sh downloads into tools/cache, or into the directory --tools
#   names.
#
# --app-version gives the launchers another version than gradle.properties' appVersion, as an
# MSI's test of an upgrade needs a later one; the application in it stays the same.
#
# Run it from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The icons and dog-vision-cli's properties, which both windows' images take.
packaging="$root/gui-compose/packaging"

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--tools <directory>] [--jdk <Windows JDK 17>] [--app-version <version>]
       [--window compose|swing|cli]" 2
}

# The path as Windows programs under Wine see it: through the drive Wine maps to the root, Z:.
windows_path() {
    winepath -w "$1"
}

# Notes a command this runs that the system lacks, with the Debian or Ubuntu package and the Fedora
# package that have it.
missing=()
require() {
    local command="$1" apt="$2" dnf="$3"
    command -v "$command" >/dev/null || missing+=("$command: apt install $apt, or dnf install $dnf")
}


# The arguments.

tools="$root/tools/cache"
jdk="" app_version="" window=""
while (( $# > 0 )); do
    case "$1" in
              --tools) tools="${2:-}";       shift 2 || usage ;;
                --jdk) jdk="${2:-}";         shift 2 || usage ;;
        --app-version) app_version="${2:-}"; shift 2 || usage ;;
             --window) window="${2:-}";      shift 2 || usage ;;
        *) usage ;;
    esac
done

# Without --window, this again for each, with the same options.
if [[ -z "$window" ]]; then
    options=(--tools "$tools")
    [[ -z "$jdk" ]] || options+=(--jdk "$jdk")
    [[ -z "$app_version" ]] || options+=(--app-version "$app_version")
    for each in compose swing cli; do
        "${BASH_SOURCE[0]}" "${options[@]}" --window "$each"
    done
    exit 0
fi

jdk="${jdk:-$tools/jdk17}"
msi_prefix="${WINE_PREFIXES:-${XDG_DATA_HOME:-$HOME/.local/share}/wineprefixes}/dot_net_msi_builder"
if [[ -z "${WINEPREFIX:-}" && -f "$msi_prefix/drive_c/windows/system32/kernel32.dll" ]]; then
    export WINEPREFIX="$msi_prefix"
fi

# What each window's image is made of. Compose's launchers pass the Java option, with which its
# application gives Swing the system's look. Each window's launcher is beside dog-vision-cli.exe,
# the command line alone, which runs in a console.
case "$window" in
    compose)
        name="dog-vision"
        module="gui-compose"
        jar_task=":gui-compose:windowsUberJar"
        jar_file="gui-compose/build/compose/jars/dog-vision-windows-x64-@VERSION@.jar"
        main_class="cz.loplex.dogvision.desktop.MainKt"
        description="How a dog or another animal sees a photo, a video or the camera"
        java_options=(--java-options "-Dcompose.application.configure.swing.globals=true")
        ;;
    swing)
        name="dog-vision-swing"
        module="gui-swing"
        jar_task=":gui-swing:windowsUberJar"
        jar_file="gui-swing/build/jars/dog-vision-swing-windows-x64-@VERSION@.jar"
        main_class="cz.loplex.dogvision.swing.MainKt"
        description="How a dog or another animal sees a photo, a video or the camera, in Java Swing"
        java_options=()
        ;;
    cli)
        name="dog-vision-cli"
        module="cli"
        jar_task=":cli:uberJar"
        jar_file="cli/build/jars/dog-vision-cli.jar"
        main_class="cz.loplex.dogvision.cli.MainKt"
        description="How a dog or another animal sees a photo, from the command line"
        java_options=()
        ;;
    *) usage ;;
esac
if [[ "$window" == cli ]]; then
    launcher_options=(--win-console)
else
    launcher_options=(--add-launcher "dog-vision-cli=$(windows_path "$packaging/dog-vision-cli.properties")")
fi

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi
locale -a | grep -ix 'cs_CZ\.utf-\?8' >/dev/null ||
    die "Missing locale cs_CZ.UTF-8: locale-gen cs_CZ.UTF-8, or dnf install glibc-langpack-cs"

[[ -f "$jdk/bin/jpackage.exe" ]] ||
    die "$jdk/bin/jpackage.exe does not exist: tools/fetch_msi_tools_on_linux.sh downloads it" 2
jpackage="$(realpath "$jdk")/bin/jpackage.exe"


# What jpackage takes in: the JAR and the runtime.

# The app's version, as gradle.properties gives it to the Linux packages and the JAR's name, unless
# --app-version gives another. The pattern stays unquoted after =~, where quotes would make it a
# plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || die "gradle.properties has no appVersion"
gradle_version="${BASH_REMATCH[2]}"
version="${app_version:-$gradle_version}"
"$root/gradlew" --quiet "$jar_task" ":$module:windowsRuntime"
jar="$root/${jar_file/@VERSION@/$gradle_version}"

windows="$root/$module/build/windows"
# jpackage takes every file in --input into the application, so the JAR goes there alone.
input="$windows/app-image-input"
rm -rf "${input:?}"
mkdir -p "$input"
cp -p "$jar" "$input/"
# jpackage refuses an image's folder that is there already.
destination="$windows/app-image"
rm -rf "${destination:?}/$name"
mkdir -p "$destination"


# jpackage, whose words go to the standard error, so that the image's folder is all this prints.

export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
wine "$jpackage" \
    --type "app-image" \
    --name "$name" \
    --app-version "$version" \
    --vendor "Martin Lopatář" \
    --description "$description" \
    --icon "$(windows_path "$packaging/dog-vision.ico")" \
    --input "$(windows_path "$input")" \
    --main-jar "$(basename "$jar")" \
    --main-class "$main_class" \
    "${java_options[@]}" \
    --runtime-image "$(windows_path "$windows/runtime")" \
    "${launcher_options[@]}" \
    --dest "$(windows_path "$destination")" >&2

echo "$destination/$name"
