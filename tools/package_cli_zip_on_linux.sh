#!/usr/bin/env bash
# Builds the command line's zip for Windows on x86-64 on Linux, through Wine: the same zip as
# tools/package_cli_zip_on_windows.ps1 builds on Windows, which says what it holds, written to
# cli/build/packages/zip.
#
# A Windows JDK's jpackage.exe makes the app image under Wine, as tools/package_msi_on_linux.sh
# makes an MSI, which says why that JDK is a 17 and why Wine runs in a Czech locale. The runtime is
# cli's windowsRuntime task's, linked here from Temurin's jmods for Windows; an app image needs
# neither WiX nor .NET.
#
# Needs:
# - Wine; WINEPREFIX chooses the prefix, as it does for Wine.
# - The locale cs_CZ.UTF-8.
# - zip.
# - --jdk: a Windows JDK 17, unpacked, for its bin/jpackage.exe.
#
# Run it from anywhere.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cli="$root/cli"

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 --jdk <Windows JDK 17>" 2
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

jdk=""
while (( $# > 0 )); do
    case "$1" in
        --jdk) jdk="${2:-}"; shift 2 || usage ;;
        *) usage ;;
    esac
done
[[ -n "$jdk" ]] || usage

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
require "zip" "zip" "zip"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi
locale -a | grep -ix 'cs_CZ\.utf-\?8' >/dev/null ||
    die "Missing locale cs_CZ.UTF-8: locale-gen cs_CZ.UTF-8, or dnf install glibc-langpack-cs"

jpackage="$(realpath "$jdk")/bin/jpackage.exe"
[[ -f "$jpackage" ]] || die "$jpackage does not exist" 2


# What jpackage takes in: the JAR and the runtime.

# The pattern stays unquoted after =~, where quotes would make it a plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || die "gradle.properties has no appVersion"
version="${BASH_REMATCH[2]}"
"$root/gradlew" --quiet :cli:uberJar :cli:windowsRuntime

staging="$cli/build/windows-zip"
rm -rf "${staging:?}"

# jpackage takes every file in --input into the application, so the JAR goes there alone.
mkdir -p "$staging/input"
cp -p "$cli/build/jars/dog-vision-cli.jar" "$staging/input/"


# jpackage, with package_cli_zip_on_windows.ps1's options, and the zip of its image.

export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
image="$staging/image"
wine "$jpackage" \
    --type "app-image" \
    --name "dog-vision-cli" \
    --app-version "$version" \
    --vendor "Martin Lopatář" \
    --description "How a dog or another animal sees a photo, from the command line" \
    --icon "$(windows_path "$root/gui-compose/packaging/dog-vision.ico")" \
    --input "$(windows_path "$staging/input")" \
    --main-jar "dog-vision-cli.jar" \
    --main-class "cz.loplex.dogvision.cli.MainKt" \
    --runtime-image "$(windows_path "$cli/build/windows/runtime")" \
    --win-console \
    --dest "$(windows_path "$image")"

cp "$root/LICENSE" "$image/dog-vision-cli/"
output="$cli/build/packages/zip"
mkdir -p "$output"
zip="$output/dog-vision-cli-$version-windows-x64.zip"
rm -f "$zip"
# The image's folder at the zip's root, so that it unpacks into a folder of its own.
(cd "$image" && zip -qr "$zip" "dog-vision-cli")

echo "$zip"
