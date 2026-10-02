#!/usr/bin/env bash
# Makes the Wine prefix that tools/package_msi_on_linux.sh runs WiX 3's candle.exe and light.exe
# in, with Wine Mono, on which those .NET Framework programs run: WINEPREFIX's, or without it
# mono_msi_builder among winetricks' named prefixes, in WINE_PREFIXES or
# ~/.local/share/wineprefixes, where that script takes it from.
#
# Wine Mono is the MSI that tools/fetch_msi_tools_on_linux.sh downloads into tools/cache, or into
# the directory --tools names, installed by msiexec: wineboot would otherwise look for it, and
# where it finds none offer to download it in a dialog, which it waits on.
#
# A prefix that has Wine Mono already stays as it is, so a second run does nothing. Making one
# takes a few minutes.
#
# Needs Wine.
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
    die "usage: $0 [--tools <directory>]" 2
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
while (( $# > 0 )); do
    case "$1" in
        --tools) tools="${2:-}"; shift 2 || usage ;;
        *) usage ;;
    esac
done
[[ -n "$tools" ]] || usage

require "wine" "wine" "wine"
require "wineboot" "wine" "wine"
require "wineserver" "wine" "wine"
require "winepath" "wine" "wine"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi

export WINEPREFIX="${WINEPREFIX:-${WINE_PREFIXES:-${XDG_DATA_HOME:-$HOME/.local/share}/wineprefixes}/mono_msi_builder}"
# Wine Mono installs itself into the prefix's C:\windows\mono.
mono="$WINEPREFIX/drive_c/windows/mono/mono-2.0"
if [[ -d "$mono" ]]; then
    echo "$WINEPREFIX has Wine Mono"
    exit 0
fi
[[ ! -e "$WINEPREFIX" ]] ||
    die "$WINEPREFIX is there without Wine Mono: remove it, and this makes it again" 2

installers=("$tools"/wine-mono-*-x86.msi)
[[ -f "${installers[0]}" ]] ||
    die "$tools has no wine-mono-*-x86.msi: tools/fetch_msi_tools_on_linux.sh downloads it" 2
(( ${#installers[@]} == 1 )) || die "$tools has more than one wine-mono-*-x86.msi: ${installers[*]}" 2

echo "Making $WINEPREFIX with $(basename "${installers[0]}")"
# Wine makes the prefix's directory, but not the one it is in.
mkdir -p "$(dirname "$WINEPREFIX")"
export WINEARCH="${WINEARCH:-win64}" WINEDEBUG="${WINEDEBUG:--all}"
# Wine's menu builder would write shortcuts of what the installers install into the home.
overrides="winemenubuilder.exe=d${WINEDLLOVERRIDES:+;$WINEDLLOVERRIDES}"
# Without Mono and Gecko, whose installers wineboot otherwise looks for. For wineboot alone, as
# mscoree disabled would keep Mono from running.
WINEDLLOVERRIDES="mscoree,mshtml=;$overrides" wineboot --init
wineserver -w
test -f "$WINEPREFIX/drive_c/windows/system32/kernel32.dll"
WINEDLLOVERRIDES="$overrides" wine msiexec /i "$(winepath -w "${installers[0]}")" /qn
wineserver -w
[[ -d "$mono" ]] || die "msiexec left no Wine Mono in $mono"
