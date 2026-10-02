#!/usr/bin/env bash
# Makes the Wine prefix that tools/package_msi_on_linux.sh runs jpackage in, with Microsoft's
# .NET Framework 4.8, which WiX 3 finds its own version through, as that script says: WINEPREFIX's,
# or without it dot_net_msi_builder among winetricks' named prefixes, in WINE_PREFIXES or
# ~/.local/share/wineprefixes, where that script takes it from.
#
# A prefix that has .NET Framework 4.8 already stays as it is, so a second run does nothing.
# Making one takes a few minutes, under 4 on GitHub's runner, and an X display, as the installers
# open windows: on a machine without one, under Xvfb, as .github/workflows/msi-under-wine.yml runs
# it.
#
# Needs Wine, winetricks and cabextract, which winetricks unpacks .NET with.
#
# Run it from anywhere.
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

(( $# == 0 )) || die "usage: $0" 2

# Notes a command this runs that the system lacks, with the Debian or Ubuntu package and the Fedora
# package that have it.
missing=()
require() {
    local command="$1" apt="$2" dnf="$3"
    command -v "$command" >/dev/null || missing+=("$command: apt install $apt, or dnf install $dnf")
}

require "wineboot" "wine" "wine"
require "wineserver" "wine" "wine"
require "winetricks" "winetricks" "winetricks"
require "cabextract" "cabextract" "cabextract"
if (( ${#missing[@]} > 0 )); then
    die "$(printf '%s\n' "Missing commands:" "${missing[@]/#/  }")"
fi

export WINEPREFIX="${WINEPREFIX:-${WINE_PREFIXES:-${XDG_DATA_HOME:-$HOME/.local/share}/wineprefixes}/dot_net_msi_builder}"
# winetricks notes each verb it has installed into the prefix in its winetricks.log.
if grep -qx "dotnet48" "$WINEPREFIX/winetricks.log" 2>/dev/null; then
    echo "$WINEPREFIX has .NET Framework 4.8"
    exit 0
fi

echo "Making $WINEPREFIX with .NET Framework 4.8"
# Wine makes the prefix's directory, but not the one it is in.
mkdir -p "$(dirname "$WINEPREFIX")"
export WINEARCH="${WINEARCH:-win64}" WINEDEBUG="${WINEDEBUG:--all}"
# Wine's menu builder would write shortcuts of what the installers install into the home.
overrides="winemenubuilder.exe=d${WINEDLLOVERRIDES:+;$WINEDLLOVERRIDES}"
# Without Mono and Gecko, whose installer wineboot otherwise waits on, and leaves the prefix without
# its DLLs; .NET Framework 4.8 replaces Mono anyway. For wineboot alone, as mscoree disabled would
# keep .NET from running.
WINEDLLOVERRIDES="mscoree,mshtml=;$overrides" wineboot --init
wineserver -w
test -f "$WINEPREFIX/drive_c/windows/system32/kernel32.dll"
WINEDLLOVERRIDES="$overrides" winetricks -q dotnet48
wineserver -w
