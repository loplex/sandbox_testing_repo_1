#!/usr/bin/env bash
# Installs the desktop window's deb in a bare container, runs its command line and removes it,
# checking each step. With --upgrade, it installs a later deb over the first before removing it, as
# an update does.
#
# A bare image has no desktop and none of its folders, /usr/share/applications among them, which
# is what a headless install for the command line alone meets. apt installs the deb there with its
# dependencies. Each check says whether it held, and every check runs, so that one failing does not
# hide the others; the script fails if any did.
#
# A later deb is the same build with another version, `./gradlew :gui-compose:packageDeb
# -PappVersion=0.1.1`, which replaces the first deb in its folder: move that one away before.
#
# Needs docker, or podman installed as docker. Usage:
#   tools/test_deb.sh [--upgrade <a later deb>] <the deb> [image, ubuntu:20.04 by default]
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--upgrade <a later deb>] <the deb> [image]" 2
}

mounts=()
if [[ "${1:-}" == --upgrade ]]; then
    (( $# >= 2 )) || usage
    later="$(realpath "$2")"
    [[ -f "$later" ]] || die "$later does not exist" 2
    mounts+=(-v "$later:/deb/later/dog-vision.deb:ro")
    shift 2
fi
(( $# >= 1 && $# <= 2 )) || usage
deb="$(realpath "$1")"
image="${2:-ubuntu:20.04}"
[[ -f "$deb" ]] || die "$deb does not exist" 2
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the deb at /deb/dog-vision.deb and the later one, if
# any, at /deb/later/dog-vision.deb.
docker run --rm -i -v "$deb:/deb/dog-vision.deb:ro" "${mounts[@]}" "$image" sh -s <<'EOF'
failed=0
check() {
    what="$1"
    shift
    if "$@" >/dev/null 2>&1; then
        echo "ok: $what"
    else
        echo "FAILED: $what"
        failed=$((failed + 1))
    fi
}
# dpkg's state of the package: installed, half-configured, config-files, not-installed and the like.
state() {
    dpkg-query -W -f='${db:Status-Status}' dog-vision 2>/dev/null || echo not-installed
}
is_installed() {
    [ "$(state)" = installed ]
}
is_removed() {
    [ "$(state)" = not-installed ] || [ "$(state)" = config-files ]
}
is_version() {
    [ "$(dpkg-query -W -f='${Version}' dog-vision)" = "$1" ]
}

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"

apt-get install -y -qq /deb/dog-vision.deb >/tmp/install.log 2>&1 || tail -n 20 /tmp/install.log
check "dog-vision installs and is configured" is_installed
check "the window is in the desktop menu folder" test -f /usr/share/applications/cz.loplex.dogvision.desktop
check "the command line runs" /opt/dog-vision/bin/dog-vision-cli --help

# The old package's prerm and postrm run with upgrade, the new one's preinst with upgrade and its
# postinst with configure and the old version; the menu entry passes from the one to the other.
if [ -e /deb/later/dog-vision.deb ]; then
    version="$(dpkg-deb -f /deb/later/dog-vision.deb Version)"
    apt-get install -y -qq /deb/later/dog-vision.deb >/tmp/upgrade.log 2>&1 || tail -n 20 /tmp/upgrade.log
    check "dog-vision $version installs over it and is configured" is_installed
    check "the installed version is $version" is_version "$version"
    check "the window is still in the desktop menu folder" \
        test -f /usr/share/applications/cz.loplex.dogvision.desktop
    check "the command line still runs" /opt/dog-vision/bin/dog-vision-cli --help
fi

apt-get remove -y -qq dog-vision >/tmp/remove.log 2>&1 || tail -n 20 /tmp/remove.log
check "dog-vision is removed" is_removed
check "its menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.desktop
check "/opt/dog-vision is gone" test ! -e /opt/dog-vision

if [ "$failed" -eq 0 ]; then
    echo "Every check held."
else
    echo "$failed check(s) failed."
    exit 1
fi
EOF
