#!/usr/bin/env bash
# Installs a deb of the project's, dog-vision or dog-vision-cli, in a bare container, runs it and
# removes it, checking each step. With --upgrade, it installs a later deb over the first before
# removing it, as an update does.
#
# A bare image has no desktop and none of its folders, /usr/share/applications among them, which
# is what a headless install for the command line alone meets. apt installs the deb there with its
# dependencies, dog-vision-cli's Java among them. Each check says whether it held, and every check
# runs, so that one failing does not hide the others; the script fails if any did.
#
# dog-vision-cli converts test_photo.jpg, beside this script, which ffmpeg made:
#   ffmpeg -f lavfi -i testsrc2=size=160x120:rate=1 -frames:v 1 -q:v 4 test_photo.jpg
#
# A later deb is the same build with another version, `-PappVersion=0.1.1`: `:cli:packageDeb` writes
# it beside the first, `:gui-compose:packageDeb` in the first one's place, so move that one away before.
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
    mounts+=(-v "$later:/deb/later/package.deb:ro")
    shift 2
fi
(( $# >= 1 && $# <= 2 )) || usage
deb="$(realpath "$1")"
image="${2:-ubuntu:20.04}"
[[ -f "$deb" ]] || die "$deb does not exist" 2
photo="$(dirname "$(realpath "$0")")/test_photo.jpg"
[[ -f "$photo" ]] || die "$photo does not exist"
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the deb at /deb/package.deb, the later one, if any, at
# /deb/later/package.deb, and the photo at /photo/test_photo.jpg.
docker run --rm -i -v "$deb:/deb/package.deb:ro" -v "$photo:/photo/test_photo.jpg:ro" "${mounts[@]}" \
    "$image" sh -s <<'EOF'
package="$(dpkg-deb -f /deb/package.deb Package)"
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
    dpkg-query -W -f='${db:Status-Status}' "$package" 2>/dev/null || echo not-installed
}
is_installed() {
    [ "$(state)" = installed ]
}
is_removed() {
    [ "$(state)" = not-installed ] || [ "$(state)" = config-files ]
}
is_version() {
    [ "$(dpkg-query -W -f='${Version}' "$package")" = "$1" ]
}
# dog-vision-cli converts the photo to a PNG beside it, as the command line does with a photo alone.
converts() {
    rm -rf /tmp/photo && mkdir /tmp/photo && cp /photo/test_photo.jpg /tmp/photo/ &&
        dog-vision-cli /tmp/photo/test_photo.jpg &&
        [ "$(od -An -tx1 -N8 /tmp/photo/test_photo.dog.png | tr -d ' \n')" = 89504e470d0a1a0a ]
}
# What the package does once installed; $1 is added to each check's name.
check_installed() {
    case "$package" in
        dog-vision)
            check "the window is in the desktop menu folder$1" \
                test -f /usr/share/applications/cz.loplex.dogvision.desktop
            check "the command line runs$1" /opt/dog-vision/bin/dog-vision-cli --help
            ;;
        dog-vision-cli)
            echo "note: java is $(readlink -f "$(command -v java)")"
            check "dog-vision-cli runs from PATH$1" dog-vision-cli --help
            check "it converts a JPEG to a PNG$1" converts
            ;;
        *)
            echo "FAILED: no checks for the package $package"
            failed=$((failed + 1))
            ;;
    esac
}
# What the package leaves behind once removed: nothing of its own.
check_removed() {
    case "$package" in
        dog-vision)
            check "its menu entry is gone" test ! -e /usr/share/applications/cz.loplex.dogvision.desktop
            check "/opt/dog-vision is gone" test ! -e /opt/dog-vision
            ;;
        dog-vision-cli)
            check "/usr/bin/dog-vision-cli is gone" test ! -e /usr/bin/dog-vision-cli
            check "/usr/share/dog-vision-cli is gone" test ! -e /usr/share/dog-vision-cli
            ;;
    esac
}

export DEBIAN_FRONTEND=noninteractive
apt-get update -qq >/dev/null
[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"

apt-get install -y -qq /deb/package.deb >/tmp/install.log 2>&1 || tail -n 20 /tmp/install.log
check "$package installs and is configured" is_installed
check_installed ""

# The old package's prerm and postrm run with upgrade, the new one's preinst with upgrade and its
# postinst with configure and the old version; the menu entry passes from the one to the other.
if [ -e /deb/later/package.deb ]; then
    version="$(dpkg-deb -f /deb/later/package.deb Version)"
    apt-get install -y -qq /deb/later/package.deb >/tmp/upgrade.log 2>&1 || tail -n 20 /tmp/upgrade.log
    check "$package $version installs over it and is configured" is_installed
    check "the installed version is $version" is_version "$version"
    check_installed " after the upgrade"
fi

apt-get remove -y -qq "$package" >/tmp/remove.log 2>&1 || tail -n 20 /tmp/remove.log
check "$package is removed" is_removed
check_removed

if [ "$failed" -eq 0 ]; then
    echo "Every check held."
else
    echo "$failed check(s) failed."
    exit 1
fi
EOF
