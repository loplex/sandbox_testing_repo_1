#!/usr/bin/env bash
# Downloads what tools/package_msi_on_linux.sh builds the MSI with into one tools directory, from
# which that script then takes it without arguments:
# - jdk17: a Windows JDK 17, Temurin's latest at the time, for its jpackage.exe, which
#   tools/package_app_image_on_linux.sh runs.
# - wix: WiX Toolset 3.14's binaries.
#
# The tools directory is tools/cache, which git ignores, unless --tools names another.
# What is in it already stays, so a second run downloads nothing.
# Each download is unpacked beside its place first and renamed into it once whole, so that one
# stopped halfway is downloaded again on the next run.
#
# The runtime is no download of this script's: the windowsRuntime tasks of :packaging and :cli link
# it, from Temurin's jmods for Windows, which Gradle downloads.
#
# Needs curl and unzip.
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


# The arguments.

tools="$root/tools/cache"
while (( $# > 0 )); do
    case "$1" in
        --tools) tools="${2:-}"; shift 2 || usage ;;
        *) usage ;;
    esac
done
[[ -n "$tools" ]] || usage


# The downloads.

mkdir -p "$tools"
tools="$(realpath "$tools")"

# The download under way, which an exit before it is whole deletes.
download=""
trap '[[ -z "$download" ]] || rm -rf "$download"' EXIT

# Downloads the zip at the URL and unpacks it into the tools directory under the name, unless that
# is there already: the zip's one top directory, or all of it where it has more.
fetch() {
    local name="$1" url="$2"
    local target="$tools/$name"
    if [[ -d "$target" ]]; then
        echo "$target is there"
        return
    fi
    echo "Downloading $name from $url"
    download="$(mktemp -d "$tools/.download-XXXXXX")"
    curl -sSfL -o "$download/archive.zip" "$url"
    unzip -q "$download/archive.zip" -d "$download/unpacked"
    local entries=("$download/unpacked"/*)
    if (( ${#entries[@]} == 1 )) && [[ -d "${entries[0]}" ]]; then
        mv "${entries[0]}" "$target"
    else
        mv "$download/unpacked" "$target"
    fi
    rm -rf "$download"
    download=""
}

fetch "jdk17" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
fetch "wix" "https://github.com/wixtoolset/wix3/releases/download/wix3141rtm/wix314-binaries.zip"

