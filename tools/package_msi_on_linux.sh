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
# The command line's MSI holds dog-vision-cli.exe alone, with no shortcut, and puts its folder on the
# system's PATH: jpackage's main.wxs, from the JDK's own jpackage, takes the fragment in
# cli/packaging/msi-path.xml, which says what it does. It is written to cli/build/packages/msi.
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
# - The locale cs_CZ.UTF-8, which Wine runs in: JDK 17's jpackage reads its arguments in Windows's
#   ANSI code page, which Wine takes from the locale, and the vendor's ř is in Windows-1250, a Czech
#   locale's, not in Windows-1252, an English locale's or C's, where it becomes "?" and jpackage
#   fails on it.
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
# The icon and the code page's file, which every MSI takes.
packaging="$root/gui-compose/packaging"

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

# What each window's MSI is made of. Every later version's MSI replaces the one installed with the
# same upgrade code, as Windows Installer tells versions of one product apart by it: never change
# either, nor give both windows one, as installing the one would then remove the other.
case "$window" in
    compose)
        name="dog-vision"
        module="gui-compose"
        output="$root/gui-compose/build/compose/binaries/main/msi"
        description="How a dog or another animal sees a photo, a video or the camera"
        upgrade_uuid="602aa86b-3230-4786-8460-ba08bca42e45"
        ;;
    swing)
        name="dog-vision-swing"
        module="gui-swing"
        output="$root/gui-swing/build/packages/msi"
        description="How a dog or another animal sees a photo, a video or the camera, in Java Swing"
        upgrade_uuid="acf6164b-f4f9-4430-b4f0-939242f187fb"
        ;;
    cli)
        name="dog-vision-cli"
        module="cli"
        output="$root/cli/build/packages/msi"
        description="How a dog or another animal sees a photo, from the command line"
        upgrade_uuid="bedc25f5-bde3-4837-86b0-26c5291beed3"
        ;;
    *) usage ;;
esac

require "wine" "wine" "wine"
require "winepath" "wine" "wine"
require "jimage" "openjdk-25-jdk-headless" "java-25-openjdk-devel"
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


# What jpackage takes in: the app image, and the licence and the dialogs' bitmaps that the module's
# build/windows holds.

# The app's version, as gradle.properties gives it to the Linux packages, unless --app-version gives
# another. The pattern stays unquoted after =~, where quotes would make it a plain string.
version_pattern=$'(^|\n)appVersion=([^\n]+)'
[[ "$(<"$root/gradle.properties")" =~ $version_pattern ]] || die "gradle.properties has no appVersion"
version="${app_version:-${BASH_REMATCH[2]}}"
image_options=(--jdk "$jdk" --window "$window")
[[ -z "$app_version" ]] || image_options+=(--app-version "$app_version")
image="$("$root/tools/package_app_image_on_linux.sh" "${image_options[@]}")"
"$root/gradlew" --quiet ":$module:windowsLicense" ":$module:windowsBitmaps"

staging="$root/$module/build/windows-msi"
rm -rf "${staging:?}"
mkdir -p "$staging"

mkdir -p "$output"
msi="$output/$name-$version.msi"
rm -f "$msi"

# The resource directory holds MsiInstallerCodepage_en.wxl, for the code page the vendor's name
# needs, and the main.wxs of the JDK's jpackage with WiX's dialog bitmaps set to the module's, and
# for the command line msi-path.xml in it as well. The build fails where that main.wxs has not
# exactly one </Product>, or for the command line one reference to Files and one </Wix>.
resources="$staging/resources"
cp -r "$packaging/windows" "$resources"
jimage extract --dir "$staging/jimage" --include "regex:.*/jdk/jpackage/internal/resources/main\.wxs" \
    "$(realpath "$jdk")/lib/modules"
main_wxs="$(find "$staging/jimage" -name main.wxs)"
[[ -f "$main_wxs" ]] || die "$jdk's jpackage has no main.wxs"
files_reference='<ComponentGroupRef Id="Files"/>'
anchors=("</Product>")
[[ "$window" != cli ]] || anchors+=("$files_reference" "</Wix>")
for anchor in "${anchors[@]}"; do
    (( $(grep -c -F "$anchor" "$main_wxs") == 1 )) || die "$jdk's main.wxs has not one $anchor"
done
text="$(<"$main_wxs")"
bitmaps="<WixVariable Id=\"WixUIBannerBmp\" Value=\"$(windows_path "$root/$module/build/windows/banner.bmp")\"/>
  <WixVariable Id=\"WixUIDialogBmp\" Value=\"$(windows_path "$root/$module/build/windows/dialog.bmp")\"/>"
text="${text/"</Product>"/"$bitmaps"$'\n  </Product>'}"
if [[ "$window" == cli ]]; then
    text="${text/"$files_reference"/"$files_reference"$'\n      <ComponentGroupRef Id="DogVisionCliPath"/>'}"
    text="${text/"</Wix>"/"$(<"$root/cli/packaging/msi-path.xml")"$'\n</Wix>'}"
fi
printf '%s\n' "$text" >"$resources/main.wxs"

# Each window's launcher has a shortcut in the Start menu, in a group of the package's name rather
# than jpackage's "Unknown", and on the desktop; dog-vision-cli.exe beside it asks for none, which
# JDK 17's jpackage gives it all the same. The command line's MSI asks for no shortcut at all, as
# dog-vision-cli.exe started from one only prints its usage.
if [[ "$window" == cli ]]; then
    shortcut_options=()
else
    shortcut_options=(--win-menu --win-menu-group "$name" --win-shortcut)
fi


# jpackage.

# jpackage finds WiX on the PATH, which Wine takes from WINEPATH.
WINEPATH="$(windows_path "$wix")"
export WINEPATH
export WINEDEBUG="${WINEDEBUG:--all}"
export LC_ALL="cs_CZ.UTF-8"
temp="$staging/temp"

if log="$(wine "$jpackage" \
    --type "msi" \
    --name "$name" \
    --app-version "$version" \
    --vendor "Martin Lopatář" \
    --description "$description" \
    --license-file "$(windows_path "$root/$module/build/windows/LICENSE.rtf")" \
    --icon "$(windows_path "$packaging/dog-vision.ico")" \
    --app-image "$(windows_path "$image")" \
    "${shortcut_options[@]}" \
    --resource-dir "$(windows_path "$resources")" \
    --win-dir-chooser \
    --win-upgrade-uuid "$upgrade_uuid" \
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
