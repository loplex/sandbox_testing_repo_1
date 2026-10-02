#!/usr/bin/env bash
# Builds the MSIs for Windows on x86-64 on Linux, through Wine: without --window all three, one
# after the other; with --window compose dog-vision's, the Compose window's; with --window swing
# dog-vision-swing's; with --window cli dog-vision-cli's, the command line's alone, which is no
# window but is made the same way.
#
# jpackage makes an installer only on the system the installer is for, so this runs a Windows JDK's
# jpackage.exe under Wine, with WiX Toolset 3 making the MSI, over the app image that
# tools/package_app_image_on_linux.sh builds first, which says what it holds: as the tar.gz has, a
# runtime of its own, the window's launcher, dog-vision.exe or dog-vision-swing.exe, and
# dog-vision-cli.exe, the command line alone, which runs in a console. It is written to
# gui-compose/build/compose/binaries/main/msi or gui-swing/build/packages/msi.
#
# What the MSI is made of, its arguments and its resource directory with jpackage's main.wxs and a
# fragment of the module's in it, the module's windowsJpackage task writes, which
# tools/package_msi_on_windows.ps1 hands jpackage on Windows as well; the task says what each holds.
#
# The command line's MSI holds dog-vision-cli.exe alone, with no shortcut, and puts its folder on the
# system's PATH, as cli/packaging/msi-path.xml says. It is written to cli/build/packages/msi.
#
# Three steps go round Wine 11.18, where they fail:
# - Wine's TransmitFile, handed a file where Windows expects a socket, fails with another error than
#   Windows's WSAENOTSOCK, which the JDK from 18 on takes for a failed copy ("transfer failed"). So
#   jpackage.exe comes from a JDK 17, which copies files without it, and the runtime is the
#   window's windowsRuntime task's, which Gradle links from Temurin's jmods for Windows of the
#   release in gradle/libs.versions.toml; package_app_image_on_linux.sh takes both the same way.
# - light.exe's validation of the MSI (ICE) fails in Wine's msi.dll with 0x65B, so light.exe runs a
#   second time without it (-sval), as electron-builder runs it off Windows; jpackage has no way to
#   pass the switch. .github/workflows/msi-under-wine.yml validates the MSI on Windows instead.
# - WiX 3 finds its own version through .NET's FileVersionInfo, which Wine Mono leaves empty, and
#   jpackage then does not recognise it: the prefix needs Microsoft's .NET Framework 4.8
#   (`winetricks dotnet48`).
#
# Needs:
# - Wine, and a prefix with .NET Framework 4.8: WINEPREFIX's, or without it dot_net_msi_builder
#   among winetricks' named prefixes, in WINE_PREFIXES or ~/.local/share/wineprefixes, which
#   tools/make_wine_prefix_on_linux.sh makes.
# - The locale cs_CZ.UTF-8, which Wine runs in: JDK 17's jpackage hands the vendor's name to WiX's
#   candle.exe on its command line, which Windows passes in its ANSI code page, which Wine takes from
#   the locale, and the vendor's ř is in Windows-1250, a Czech locale's, not in Windows-1252, an
#   English locale's or C's, where it becomes another letter in the MSI.
# - --jdk: a Windows JDK 17, unpacked, for its bin/jpackage.exe, which package_app_image_on_linux.sh
#   is given as well.
# - --wix: WiX Toolset 3.14's binaries, unpacked, the directory holding candle.exe and light.exe
#   (wix314-binaries.zip from github.com/wixtoolset/wix3). WiX 3 and 5 are free under the MS-RL;
#   from WiX 6 on, the binaries come under the Open Source Maintenance Fee's EULA, and JDK 17's
#   jpackage takes WiX 3 alone.
#
# tools/fetch_msi_tools_on_linux.sh downloads both into tools/cache, which git ignores. Each of --jdk
# and --wix left out is taken from there, or from the directory --tools names.
#
# --app-version gives the MSI and its image another version than gradle.properties' appVersion, as
# a test of an upgrade needs a later one; the application in it stays the same.
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
    die "usage: $0 [--tools <directory>] [--jdk <Windows JDK 17>] [--wix <WiX 3.14's binaries>]
       [--app-version <version>] [--window compose|swing|cli]" 2
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
jdk="" wix="" app_version="" window=""
while (( $# > 0 )); do
    case "$1" in
              --tools) tools="${2:-}";       shift 2 || usage ;;
                --jdk) jdk="${2:-}";         shift 2 || usage ;;
                --wix) wix="${2:-}";         shift 2 || usage ;;
        --app-version) app_version="${2:-}"; shift 2 || usage ;;
             --window) window="${2:-}";      shift 2 || usage ;;
        *) usage ;;
    esac
done

# Without --window, this again for each, with the same options.
if [[ -z "$window" ]]; then
    options=(--tools "$tools")
    [[ -z "$jdk" ]] || options+=(--jdk "$jdk")
    [[ -z "$wix" ]] || options+=(--wix "$wix")
    [[ -z "$app_version" ]] || options+=(--app-version "$app_version")
    for each in compose swing cli; do
        "${BASH_SOURCE[0]}" "${options[@]}" --window "$each"
    done
    exit 0
fi

# What tools/fetch_msi_tools_on_linux.sh downloads, where an option does not name it.
jdk="${jdk:-$tools/jdk17}"
wix="${wix:-$tools/wix}"
export WINEPREFIX="${WINEPREFIX:-${WINE_PREFIXES:-${XDG_DATA_HOME:-$HOME/.local/share}/wineprefixes}/dot_net_msi_builder}"

# Each window's module, whose build script says what its MSI is made of, the MSI's name, and where
# it is written.
case "$window" in
    compose) module="gui-compose" name="dog-vision"       output="$root/gui-compose/build/compose/binaries/main/msi" ;;
      swing) module="gui-swing"   name="dog-vision-swing" output="$root/gui-swing/build/packages/msi" ;;
        cli) module="cli"     name="dog-vision-cli"   output="$root/cli/build/packages/msi" ;;
          *) usage ;;
esac

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi
locale -a | grep -ix 'cs_CZ\.utf-\?8' >/dev/null ||
    die "Missing locale cs_CZ.UTF-8: locale-gen cs_CZ.UTF-8, or dnf install glibc-langpack-cs"

fetch_hint="tools/fetch_msi_tools_on_linux.sh downloads it"
[[ -f "$jdk/bin/jpackage.exe" ]] || die "$jdk/bin/jpackage.exe does not exist: $fetch_hint" 2
jpackage="$(realpath "$jdk")/bin/jpackage.exe"
[[ -f "$wix/light.exe" ]] || die "$wix has no light.exe: $fetch_hint" 2
wix="$(realpath "$wix")"

[[ -f "$WINEPREFIX/drive_c/windows/system32/kernel32.dll" ]] ||
    die "$WINEPREFIX is no Wine prefix: tools/make_wine_prefix_on_linux.sh makes it" 2


# What jpackage takes in: the app image, and the arguments and the resource directory that the
# module's windowsJpackage task wrote for it, as package_app_image_on_linux.sh ran it.

# The app's version, as gradle.properties gives it to the Linux packages, unless --app-version gives
# another. The pattern stays unquoted after =~, where quotes would make it a plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || die "gradle.properties has no appVersion"
version="${app_version:-${BASH_REMATCH[2]}}"
image_options=(--jdk "$jdk" --window "$window")
[[ -z "$app_version" ]] || image_options+=(--app-version "$app_version")
image="$("$root/tools/package_app_image_on_linux.sh" "${image_options[@]}")"
arguments="$root/$module/build/windows/jpackage"
resources="$arguments/resources"

staging="$root/$module/build/windows-msi"
rm -rf "${staging:?}"
mkdir -p "$staging"

mkdir -p "$output"
msi="$output/$name-$version.msi"
rm -f "$msi"



# jpackage, which reads the files of arguments as UTF-8, as windowsJpackage writes them, only when
# told to: JDK 17's default charset is the system's.

# jpackage finds WiX on the PATH, which Wine takes from WINEPATH.
WINEPATH="$(windows_path "$wix")"
export WINEPATH
export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
temp="$staging/temp"

if log="$(wine "$jpackage" \
    -J-Dfile.encoding=UTF-8 \
    "@$(windows_path "$arguments/package-arguments")" \
    "@$(windows_path "$arguments/msi-arguments")" \
    --type "msi" \
    --app-image "$(windows_path "$image")" \
    --temp "$(windows_path "$temp")" \
    --dest "$(windows_path "$output")" 2>&1)"; then
    status=0
else
    status=$?
fi


# light.exe a second time, where jpackage's failed.

if (( status != 0 )); then
    if [[ "$log" == *"Download WiX"* ]]; then
        die "jpackage finds no WiX: the prefix needs .NET Framework 4.8 (winetricks dotnet48)"
    fi
    if [[ "$log" != *"light.exe"* ]]; then
        printf '%s\n' "$log" >&2
        die "jpackage failed with $status before light.exe"
    fi

    # The resource directory's localizations before jpackage's own strings, where jpackage hands
    # light.exe its own first: light.exe takes the MSI's code page from the first, which is then
    # the resource directory's.
    localizations=()
    for wxl in "$resources"/*.wxl; do
        localizations+=(-loc "$(windows_path "$wxl")")
    done

    # The objects jpackage left in its temp directory.
    objects=()
    for object in "$temp"/wixobj/*.wixobj; do
        objects+=("$(windows_path "$object")")
    done

    # As jpackage ran it, but without the MSI's validation, and where jpackage runs it: the files the
    # objects name are relative to the application's image.
    cd "$temp/images/win-msi.image/$name"
    wine "$wix/light.exe" \
        -nologo -spdb -sval \
        -ext "WixUtilExtension" \
        -ext "WixUIExtension" \
        -out "$(windows_path "$msi")" \
        -b "$(windows_path "$temp/config")" \
        -sice:ICE27 \
        "${localizations[@]}" \
        -loc "$(windows_path "$temp/config/MsiInstallerStrings_en.wxl")" \
        -cultures:en-us \
        "${objects[@]}"
fi

echo "$msi"
