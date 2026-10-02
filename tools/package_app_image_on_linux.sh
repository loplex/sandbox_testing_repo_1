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
# jpackage.exe under Wine, in the locale cs_CZ.UTF-8, as tools/package_msi_on_linux.sh runs it,
# with the arguments that the module's windowsJpackage task writes, which
# tools/package_msi_on_windows.ps1 hands jpackage on Windows as well: what the package is, and
# what the image runs, its JAR and the runtime that the module's windowsRuntime task links. An app
# image needs neither WiX nor .NET.
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

# Each window's module, whose build script says what its image is made of, and the image's name.
case "$window" in
    compose) module="gui-compose" name="dog-vision" ;;
      swing) module="gui-swing"   name="dog-vision-swing" ;;
        cli) module="cli"     name="dog-vision-cli" ;;
          *) usage ;;
esac

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi
locale -a | grep -ix 'cs_CZ\.utf-\?8' >/dev/null ||
    die "Missing locale cs_CZ.UTF-8: locale-gen cs_CZ.UTF-8, or dnf install glibc-langpack-cs"

[[ -f "$jdk/bin/jpackage.exe" ]] ||
    die "$jdk/bin/jpackage.exe does not exist: tools/fetch_msi_tools_on_linux.sh downloads it" 2
jdk="$(realpath "$jdk")"
jpackage="$jdk/bin/jpackage.exe"
# windowsJpackage writes the paths as Wine sees them through Z:, which Wine maps to the root.
[[ "$(winepath -u "Z:\\" 2>/dev/null)" == "/" ]] || die "Wine maps no drive Z: to /, which the paths need"


# What jpackage takes in: the arguments, with the JAR and the runtime they name.

gradle_options=("-PjpackageJdk=$jdk")
[[ -z "$app_version" ]] || gradle_options+=("-PwindowsAppVersion=$app_version")
"$root/gradlew" --quiet ":$module:windowsJpackage" "${gradle_options[@]}"
windows="$root/$module/build/windows"
arguments="$windows/jpackage"
# jpackage refuses an image's folder that is there already.
destination="$windows/app-image"
rm -rf "${destination:?}/$name"
mkdir -p "$destination"


# jpackage, whose words go to the standard error, so that the image's folder is all this prints. It
# reads the files of arguments as UTF-8, as windowsJpackage writes them, only when told to: JDK 17's
# default charset is the system's.

export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
wine "$jpackage" \
    -J-Dfile.encoding=UTF-8 \
    "@$(windows_path "$arguments/package-arguments")" \
    "@$(windows_path "$arguments/image-arguments")" \
    --type "app-image" \
    --dest "$(windows_path "$destination")" >&2

echo "$destination/$name"
