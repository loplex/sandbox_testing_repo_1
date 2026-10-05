#!/usr/bin/env bash
# Probe: installs the rpms given, one after another, in a bare openSUSE Leap 15.6, opening each
# one's window under Xvfb after its install, and dumps what fontconfig and Java see of the fonts at
# each step. Then, where the last window failed, tries it again after fc-cache -f and with a
# fresh fontconfig cache. Usage: tools/probe_leap_fonts.sh <rpm> [<rpm>...]
set -uo pipefail
mounts=()
names=()
for rpm in "$@"; do
    mounts+=(-v "$(realpath "$rpm"):/rpm/$(basename "$rpm"):ro")
    names+=("$(basename "$rpm")")
done
docker run --rm -i -e "RPMS=${names[*]}" -v "$(realpath dist):/rpm/beside:ro" \
    -v "$(realpath tools/test_photo.jpg):/photo/test_photo.jpg:ro" "${mounts[@]}" opensuse/leap:15.6 bash -s <<'EOF'
dump() {
    echo "::group::fonts after $1"
    echo "--- rpm -qa (font|java|fontconfig|freetype)"
    rpm -qa | grep -i -E 'font|java|freetype' | sort
    echo "--- fc-list : file fontformat lang?"
    fc-list : file fontformat 2>&1 | sort
    echo "--- fc-match sans / serif / monospace / Dialog"
    for f in sans serif monospace Dialog; do fc-match "$f" file fontformat 2>&1; done
    echo "--- /var/cache/fontconfig"
    ls -la /var/cache/fontconfig 2>&1
    echo "--- /etc/fonts/conf.d"
    ls /etc/fonts/conf.d 2>&1 | tr '\n' ' '; echo
    echo "--- mtimes of font dirs"
    find /usr/share/fonts -maxdepth 1 -type d -exec stat -c '%y %n' {} \; 2>&1
    echo "::endgroup::"
}
window() {
    [ -e /tmp/.X11-unix/X99 ] || { Xvfb :99 -screen 0 1280x800x24 >/tmp/xvfb.log 2>&1 & }
    for _ in 1 2 3 4 5 6 7 8 9 10; do [ -e /tmp/.X11-unix/X99 ] && break; sleep 1; done
    rm -rf /tmp/home && mkdir /tmp/home
    DISPLAY=:99 HOME=/tmp/home "$@" /photo/test_photo.jpg >/tmp/window.log 2>&1 &
    pid=$!
    shown=""
    for _ in $(seq 60); do
        if DISPLAY=:99 xwininfo -root -tree 2>/dev/null | grep -q '"Dog Vision"'; then shown=yes; break; fi
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    kill "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
    if [ -n "$shown" ]; then echo "RESULT: $* shown"; else
        echo "RESULT: $* NOT shown"; grep -v '^\s*at ' /tmp/window.log | head -40
        echo "--- ~/.java/fonts"; find /tmp/home/.java -type f -exec sh -c 'echo "== $1"; cat "$1"' _ {} \; 2>&1 | head -80
    fi
    [ -n "$shown" ]
}
zypper -q -n install xorg-x11-server-Xvfb xwininfo >/tmp/display.log 2>&1 || tail /tmp/display.log
dump "the image and Xvfb"
last=""
for rpm in $RPMS; do
    echo "=== install $rpm"
    zypper -n install --allow-unsigned-rpm --force-resolution "/rpm/$rpm" 2>&1 | tail -n 40
    dump "$rpm"
    name="$(rpm -qp --qf '%{NAME}' "/rpm/$rpm")"
    case "$name" in
        dog-vision-compose | dog-vision-swing | dog-vision) last="$name"; window "$name" && last="" ;;
    esac
done
if [ -n "$last" ]; then
    echo "=== $last failed: java's fontconfig view"
    java -XshowSettings:properties -version 2>&1 | grep -i -E 'java.home|java.version|font|awt'
    ls "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"/lib | grep -i -E 'font|awt'
    echo "=== again"; window "$last"
    echo "=== after fc-cache -f"; fc-cache -f; window "$last"
    echo "=== after rm -rf /var/cache/fontconfig/* and fc-cache -fs"; rm -rf /var/cache/fontconfig/*; fc-cache -fs; window "$last"
    echo "=== with dejavu-fonts installed"; zypper -q -n install dejavu-fonts >/dev/null 2>&1; window "$last"
    exit 1
fi
EOF
