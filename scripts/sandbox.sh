#!/usr/bin/env bash
# Runs the sandbox IDE of `./gradlew runIde` on a display of its own, to check the plugin by hand there - see
# docs/development.md.
#
#   scripts/sandbox.sh start PROJECT_DIR     starts the display and the IDE with PROJECT_DIR open
#   scripts/sandbox.sh shot NAME [GEOMETRY]  saves a screenshot, cropped to GEOMETRY (such as 800x600+0+0)
#   scripts/sandbox.sh stop                  closes the IDE as File | Exit does, then the display
#
# X_SERVER, X_DISPLAY_NUMBER and X_SCREEN pick the display at start, as scripts/x-display.sh says; by default, the
# first virtual one free. The display is written to build/manual-test/display, where shot and stop read it. Logs,
# screenshots and process ids go to build/manual-test too.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work="$root/build/manual-test"
# shellcheck source=x-display.sh
source "$root/scripts/x-display.sh"

die() { echo "$*" >&2; exit 1; }

# The display start put the IDE on.
use_display() {
    [ -f "$work/display" ] || die "no sandbox IDE was started; run start first"
    DISPLAY=$(cat "$work/display")
    export DISPLAY
}

# The id of the IDE's main window: the one whose title names the open project.
main_window() { wmctrl -l 2>/dev/null | awk -v name="$1" 'index($0, name) { print $1; exit }'; }

start() {
    local project
    project=$(cd "${1:?a project directory to open}" && pwd)
    for tool in xdotool wmctrl import convert; do
        command -v "$tool" >/dev/null || die "$tool is missing"
    done
    # The sandbox has one configuration directory, which a second IDE would fight over.
    if pgrep -f '[c]onfig_runIde' >/dev/null; then
        die "a sandbox IDE is already running; close it first"
    fi
    mkdir -p "$work/shots"

    x_display_start "$work" "Sandbox IDE"
    export DISPLAY="$X_DISPLAY"

    # Without WAYLAND_DISPLAY, so that an IDE able to run on Wayland opens on the display chosen all the same.
    # All of its output to the log, or a caller reading this script's output would wait for the IDE to exit.
    (cd "$root" && env -u WAYLAND_DISPLAY ./gradlew runIde --args="$project") </dev/null >"$work/runIde.log" 2>&1 &
    echo $! >"$work/runIde.pid"
    basename "$project" >"$work/project-name"

    local name window="" waited=0
    name=$(basename "$project")
    while [ -z "$window" ] && [ "$waited" -lt 300 ]; do
        sleep 2; waited=$((waited + 2))
        kill -0 "$(cat "$work/runIde.pid")" 2>/dev/null || die "runIde ended; see $work/runIde.log"
        window=$(main_window "$name")
    done
    if [ -n "$window" ]; then
        wmctrl -i -r "$window" -b add,maximized_vert,maximized_horz
        echo "IDE is up on $DISPLAY after ${waited}s."
    else
        echo "No window named after $name after ${waited}s; a dialog may be waiting - take a shot."
    fi
    wmctrl -l
    echo "Run: export DISPLAY=$DISPLAY"
}

shot() {
    use_display
    local file="$work/shots/${1:?a name for the screenshot}.png"
    mkdir -p "$work/shots"
    import -window root "$file"
    if [ -n "${2:-}" ]; then
        convert "$file" -crop "$2" +repage "$file"
    fi
    echo "$file"
}

stop() {
    use_display
    local name window waited=0
    if [ -f "$work/project-name" ]; then
        name=$(cat "$work/project-name")
        window=$(main_window "$name")
        if [ -n "$window" ]; then
            wmctrl -i -c "$window"
            sleep 2
            # Asked only when the IDE is set to confirm before exiting.
            if wmctrl -a 'Confirm Exit' 2>/dev/null; then
                sleep 0.5
                xdotool key Return
            fi
        fi
    fi
    if [ -f "$work/runIde.pid" ]; then
        while kill -0 "$(cat "$work/runIde.pid")" 2>/dev/null && [ "$waited" -lt 60 ]; do
            sleep 1; waited=$((waited + 1))
        done
        kill -0 "$(cat "$work/runIde.pid")" 2>/dev/null && die "the IDE has not exited after ${waited}s; close it and run stop again"
    fi
    x_display_stop "$work"
    rm -f "$work/runIde.pid" "$work/project-name"
    echo "Stopped."
}

case "${1:-}" in
    start) shift; start "$@" ;;
    shot) shift; shot "$@" ;;
    stop) stop ;;
    *) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
