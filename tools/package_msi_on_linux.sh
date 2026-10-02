#!/usr/bin/env bash
# Builds the MSI for Windows on x86-64 on Linux, through Wine, into tools/build/msi, which git
# ignores, as tools/build/msi/dog-vision-0.1.0.msi. It installs the app image that
# tools/package_app_image_on_linux.sh builds first as dog-vision, with the Compose window's
# dog-vision.exe, the Swing window's dog-vision-swing.exe and the command line's dog-vision-cli.exe.
#
# WiX Toolset 3's candle.exe and light.exe make it of what :packaging's windowsWix task writes:
# packaging/windows/dog-vision.wxs, which says what the MSI does, and the app image's files.
# tools/package_msi_on_windows.ps1 makes the same MSI on Windows.
#
# One step goes round Wine 11.18, where it fails: light.exe's validation of the MSI (ICE) fails in
# Wine's msi.dll with 0x65B, so light.exe runs without it (-sval), as electron-builder runs it off
# Windows. .github/workflows/msi-under-wine.yml validates the MSI on Windows instead.
#
# candle.exe and light.exe are .NET Framework programs, which run here in a prefix with
# Microsoft's .NET Framework 4.8 (`winetricks dotnet48`).
#
# Needs:
# - Wine, and a prefix: WINEPREFIX's, or without it dot_net_msi_builder among winetricks' named
#   prefixes, in WINE_PREFIXES or ~/.local/share/wineprefixes, which
#   tools/make_wine_prefix_on_linux.sh makes with .NET Framework 4.8.
# - What package_app_image_on_linux.sh needs, which --tools and --jdk are handed to: the locale
#   cs_CZ.UTF-8, and a Windows JDK 17.
# - --wix: WiX Toolset 3.14's binaries, unpacked, the directory holding candle.exe and light.exe
#   (wix314-binaries.zip from github.com/wixtoolset/wix3). WiX 3 and 5 are free under the MS-RL;
#   from WiX 6 on, the binaries come under the Open Source Maintenance Fee's EULA.
#
# tools/fetch_msi_tools_on_linux.sh downloads the JDK and WiX into tools/cache, which git ignores.
# Each of --jdk and --wix left out is taken from there, or from the directory --tools names.
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
       [--app-version <version>]" 2
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
jdk="" wix="" app_version=""
while (( $# > 0 )); do
    case "$1" in
              --tools) tools="${2:-}";       shift 2 || usage ;;
                --jdk) jdk="${2:-}";         shift 2 || usage ;;
                --wix) wix="${2:-}";         shift 2 || usage ;;
        --app-version) app_version="${2:-}"; shift 2 || usage ;;
        *) usage ;;
    esac
done

# What tools/fetch_msi_tools_on_linux.sh downloads, where an option does not name it.
wix="${wix:-$tools/wix}"
export WINEPREFIX="${WINEPREFIX:-${WINE_PREFIXES:-${XDG_DATA_HOME:-$HOME/.local/share}/wineprefixes}/dot_net_msi_builder}"

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi

[[ -f "$wix/light.exe" ]] || die "$wix has no light.exe: tools/fetch_msi_tools_on_linux.sh downloads it" 2
wix="$(realpath "$wix")"
[[ -f "$WINEPREFIX/drive_c/windows/system32/kernel32.dll" ]] ||
    die "$WINEPREFIX is no Wine prefix: tools/make_wine_prefix_on_linux.sh makes it" 2


# What WiX takes in: the app image, and what :packaging's windowsWix task writes of it.

# The app's version, as gradle.properties gives it to the Linux packages, unless --app-version gives
# another. The pattern stays unquoted after =~, where quotes would make it a plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || die "gradle.properties has no appVersion"
version="${app_version:-${BASH_REMATCH[2]}}"
image_options=(--tools "$tools" --image dog-vision)
[[ -z "$jdk" ]] || image_options+=(--jdk "$jdk")
gradle_options=()
if [[ -n "$app_version" ]]; then
    image_options+=(--app-version "$app_version")
    gradle_options+=("-PwindowsAppVersion=$app_version")
fi
image="$("$root/tools/package_app_image_on_linux.sh" "${image_options[@]}")"
"$root/gradlew" --quiet ":packaging:windowsWix" "-PwindowsAppImage=$image" "${gradle_options[@]}"
source="$root/packaging/build/windows/wix"

staging="$root/tools/build/staging/dog-vision-msi"
rm -rf "${staging:?}"
mkdir -p "$staging"

output="$root/tools/build/msi"
mkdir -p "$output"
msi="$output/dog-vision-$version.msi"
rm -f "$msi"


# candle.exe and light.exe. light.exe takes the MSI's code page from the first localization it is
# handed, so codepage.wxl comes before WiX's own English strings, which -cultures takes.

export WINEDEBUG="${WINEDEBUG:--all}"
extensions=(-ext "WixUtilExtension" -ext "WixUIExtension")
wine "$wix/candle.exe" -nologo -arch x64 "${extensions[@]}" \
    -out "$(windows_path "$staging")\\" \
    "$(windows_path "$source/dog-vision.wxs")" \
    "$(windows_path "$source/files.wxs")" >&2
wine "$wix/light.exe" -nologo -spdb -sval "${extensions[@]}" \
    -loc "$(windows_path "$source/codepage.wxl")" \
    -cultures:en-us \
    -out "$(windows_path "$msi")" \
    "$(windows_path "$staging/dog-vision.wixobj")" \
    "$(windows_path "$staging/files.wixobj")" >&2

echo "$msi"
