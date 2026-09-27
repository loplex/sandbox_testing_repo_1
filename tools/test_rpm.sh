#!/usr/bin/env bash
# Installs an rpm of the project's, dog-vision or dog-vision-cli, in a bare container, runs it and
# removes it, checking each step. With --upgrade, it installs a later rpm over the first before
# removing it, as an update does.
#
# A bare image has no desktop, and openSUSE's has no /etc/xdg/menus, which is what a headless
# install for the command line alone meets. dnf or zypper installs the rpm there with its
# dependencies, dog-vision-cli's Java among them, and microdnf those its repositories have; rpm
# removes it. Each check says whether it held, and every check runs, so that one failing does not
# hide the others; the script fails if any did.
#
# dog-vision-cli converts test_photo.jpg, beside this script, as tools/test_deb.sh says.
#
# A later rpm is the same build with another version, `-PappVersion=0.1.1`: `:cli:packageRpm` writes
# it beside the first, `:gui-compose:packageRpm` in the first one's place, so move that one away before.
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
    mounts+=(-v "$later:/rpm/later/package.rpm:ro")
    shift 2
fi
(( $# >= 1 && $# <= 2 )) || usage
rpm="$(realpath "$1")"
image="${2:-fedora:42}"
[[ -f "$rpm" ]] || die "$rpm does not exist" 2
photo="$(dirname "$(realpath "$0")")/test_photo.jpg"
[[ -f "$photo" ]] || die "$photo does not exist"
command -v docker >/dev/null || die "Missing command: docker"

# What runs in the container, as root, with the rpm at /rpm/package.rpm, the later one, if any, at
# /rpm/later/package.rpm, and the photo at /photo/test_photo.jpg.
docker run --rm -i -v "$rpm:/rpm/package.rpm:ro" -v "$photo:/photo/test_photo.jpg:ro" "${mounts[@]}" \
    "$image" sh -s <<'EOF'
package="$(rpm -qp --qf '%{NAME}' /rpm/package.rpm)"
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
    rpm -q "$package"
}
is_removed() {
    ! rpm -q "$package"
}
# One of the package, of version-release $1: rpm -i would put a second beside the first.
is_version() {
    [ "$(rpm -q --qf '%{VERSION}-%{RELEASE}\n' "$package")" = "$1" ]
}
requires_no_xdg_utils() {
    ! rpm -qR "$package" | grep -q xdg-utils
}
# rpm removes a folder of the package's with it, where it is empty.
owns_no_system_folder() {
    ! rpm -ql "$package" |
        grep -qxE '/|/opt|/usr|/usr/bin|/usr/share|/usr/share/applications|/usr/share/doc|/usr/share/licenses'
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

[ -e /usr/share/applications ] && echo "note: the image has /usr/share/applications already"
[ -e /etc/xdg/menus ] && echo "note: the image has /etc/xdg/menus already"

# Each installs the rpm $1, or upgrades the one installed to it.
if command -v zypper >/dev/null; then
    install_rpm() { zypper -q -n install --allow-unsigned-rpm "$1"; }
elif command -v dnf >/dev/null; then
    install_rpm() { dnf -q -y install "$1"; }
elif command -v microdnf >/dev/null; then
    # microdnf installs no file: what the rpm requires comes from the image's repositories, one at
    # a time, and the rpm itself through rpm, past what none of them has. Of a rich dependency,
    # (A or B), the first alternative a repository has.
    install_rpm() {
        rpm -qpR "$1" | grep -v '^rpmlib(' >/tmp/requires
        while IFS= read -r requirement; do
            case "$requirement" in
                "("*) alternatives="$(echo "$requirement" | sed 's/^(//; s/)$//; s/ or /\n/g')" ;;
                *) alternatives="$requirement" ;;
            esac
            provided=""
            for alternative in $alternatives; do
                rpm -q --whatprovides "$alternative" && provided=yes && break
            done
            [ -n "$provided" ] && continue
            for alternative in $alternatives; do
                microdnf -y install "$alternative" && provided=yes && break
            done
            [ -n "$provided" ] || unavailable="$unavailable $requirement"
        done </tmp/requires
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
install_rpm /rpm/package.rpm >/tmp/install.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/install.log
[ -n "$unavailable" ] && echo "note: installed without what no repository has:$unavailable"
check "the install exits with 0 (it exited with $status)" test "$status" -eq 0
check "$package is installed" is_installed
check "it does not require xdg-utils" requires_no_xdg_utils
check "it owns no folder of the system's" owns_no_system_folder
check_installed ""

# The new package's %pre and %post run with 2, then the old one's %preun and %postun with 1; the
# menu entry passes from the one to the other.
if [ -e /rpm/later/package.rpm ]; then
    version="$(rpm -qp --qf '%{VERSION}-%{RELEASE}' /rpm/later/package.rpm)"
    install_rpm /rpm/later/package.rpm >/tmp/upgrade.log 2>&1
    status=$?
    [ "$status" -eq 0 ] || tail -n 20 /tmp/upgrade.log
    check "the upgrade to $version exits with 0 (it exited with $status)" test "$status" -eq 0
    check "the one $package installed is $version" is_version "$version"
    check_installed " after the upgrade"
fi

rpm -e "$package" >/tmp/erase.log 2>&1
status=$?
[ "$status" -eq 0 ] || tail -n 20 /tmp/erase.log
check "rpm -e exits with 0 (it exited with $status)" test "$status" -eq 0
check "$package is removed" is_removed
check_removed

if [ "$failed" -eq 0 ]; then
    echo "Every check held."
else
    echo "$failed check(s) failed."
    exit 1
fi
EOF
