#!/usr/bin/env bash
# Installs the desktop window's rpm in a bare container, runs its command line and removes it,
# checking each step. With --upgrade, it installs a later rpm over the first before removing it, as
# an update does.
#
# A bare image has no desktop, and openSUSE's has no /etc/xdg/menus, which is what a headless
# install for the command line alone meets. dnf or zypper installs the rpm there with its
# dependencies, and microdnf those its repositories have; rpm removes it. Each check says whether
# it held, and every check runs, so that one failing does not hide the others; the script fails if
# any did.
#
# A later rpm is the same build with another version, `./gradlew :gui-compose:packageRpm
# -PappVersion=0.1.1`, which replaces the first rpm in its folder: move that one away before.
#
# Needs docker, or podman installed as docker. Usage:
#   tools/test_rpm.sh [--upgrade <a later rpm>] <the rpm> [image with dnf, zypper or microdnf,
#   fedora:42 by default]
set -euo pipefail

# Says why on the standard error and exits, with 1 or the status given.
die() {
    echo "$1" >&2
    exit "${2:-1}"
}

usage() {
    die "usage: $0 [--upgrade <a later rpm>] <the rpm> [image]" 2
}

mounts=()
if [[ "${1:-}" == --upgrade ]]; then
    (( $# >= 2 )) || usage
    later="$(realpath "$2")"
    [[ -f "$later" ]] || die "$later does not exist" 2
    mounts+=(-v "$later:/rpm/later/dog-vision.rpm:ro")
    shift 2
fi
(( $# >= 1 && $# <= 2 )) || usage
rpm="$(realpath "$1")"
image="${2:-fedora:42}"
[[ -f "$rpm" ]] || die "$rpm does not exist" 2
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the rpm at /rpm/dog-vision.rpm and the later one, if
# any, at /rpm/later/dog-vision.rpm.
docker run --rm -i -v "$rpm:/rpm/dog-vision.rpm:ro" "${mounts[@]}" "$image" sh -s <<'EOF'
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
is_installed() {
    rpm -q dog-vision
}
is_removed() {
    ! rpm -q dog-vision
}
# One dog-vision, of version-release $1: rpm -i would put a second beside the first.
is_version() {
    [ "$(rpm -q --qf '%{VERSION}-%{RELEASE}\n' dog-vision)" = "$1" ]
}
requires_no_xdg_utils() {
    ! rpm -qR dog-vision | grep -q xdg-utils
}
# rpm removes a folder of the package's with it, where it is empty.
owns_no_system_folder() {
    ! rpm -ql dog-vision | grep -qxE '/|/opt|/usr|/usr/share|/usr/share/applications|/usr/share/licenses'
}

[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"
[ -e /etc/xdg/menus ] && echo "note: the image has /etc/xdg/menus already"

# Each installs the rpm $1, or upgrades the one installed to it.
if command -v zypper >/dev/null; then
    install_rpm() { zypper -q -n install --allow-unsigned-rpm "$1"; }
elif command -v dnf >/dev/null; then
    install_rpm() { dnf -q -y install "$1"; }
elif command -v microdnf >/dev/null; then
    # microdnf installs no file: what the rpm requires comes from the image's repositories, one at
    # a time, and the rpm itself through rpm, past what none of them has.
    install_rpm() {
        for requirement in $(rpm -qpR "$1" | grep -v '^rpmlib('); do
            rpm -q --whatprovides "$requirement" || microdnf -y install "$requirement" ||
                unavailable="$unavailable $requirement"
        done
        if [ -z "$unavailable" ]; then
            rpm -U "$1"
        else
            rpm -U --nodeps "$1"
        fi
    }
else
    echo "FAILED: the image has none of dnf, zypper and microdnf"
    exit 1
fi
unavailable=""
# A scriptlet that fails leaves the package installed, and only the status tells.
install_rpm /rpm/dog-vision.rpm >/tmp/install.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/install.log
[ -n "$unavailable" ] && echo "note: installed without what no repository has:$unavailable"
check "the install exits with 0 (it exited with $status)" test "$status" -eq 0
check "dog-vision is installed" is_installed
check "it does not require xdg-utils" requires_no_xdg_utils
check "it owns no folder of the system's" owns_no_system_folder
check "the window is in the desktop menu folder" test -f /usr/share/applications/cz.loplex.dogvision.desktop
check "the command line runs" /opt/dog-vision/bin/dog-vision-cli --help

# The new package's %pre and %post run with 2, then the old one's %preun and %postun with 1; the
# menu entry passes from the one to the other.
if [ -e /rpm/later/dog-vision.rpm ]; then
    version="$(rpm -qp --qf '%{VERSION}-%{RELEASE}' /rpm/later/dog-vision.rpm)"
    install_rpm /rpm/later/dog-vision.rpm >/tmp/upgrade.log 2>&1
    status=$?
    [ "$status" -eq 0 ] || tail -n 20 /tmp/upgrade.log
    check "the upgrade to $version exits with 0 (it exited with $status)" test "$status" -eq 0
    check "the one dog-vision installed is $version" is_version "$version"
    check "the window is still in the desktop menu folder" \
        test -f /usr/share/applications/cz.loplex.dogvision.desktop
    check "the command line still runs" /opt/dog-vision/bin/dog-vision-cli --help
fi

rpm -e dog-vision >/tmp/erase.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/erase.log
check "rpm -e exits with 0 (it exited with $status)" test "$status" -eq 0
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
