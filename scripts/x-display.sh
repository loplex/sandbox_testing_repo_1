# The X display of the scripts that run an IDE on a display of its own - see docs/development.md. Sourced by them.
#
# X_SERVER picks the display:
#   xvfb      a virtual one, which shows nothing (the default)
#   xephyr    a window on the desktop, $DISPLAY, to watch the IDE in
#   desktop   the desktop itself, $DISPLAY: nothing is started
# X_DISPLAY_NUMBER is the number of the display started, which is otherwise the first one free, and X_SCREEN its size
# (1920x1080 by default).
# X_HOST_INPUT, on (the default) or off, says whether the desktop's mouse and keyboard reach Xephyr's display.

x_display_fail() { echo "$*" >&2; exit 1; }

# Starts the display, and Openbox on it, logging to WORK_DIR; TITLE is the one of Xephyr's window.
# Sets X_DISPLAY to the display, such as :2, and writes it to WORK_DIR/display.
x_display_start() {
    local work=${1:?a directory to work in} title=${2:-IDE} kind=${X_SERVER:-xvfb} screen=${X_SCREEN:-1920x1080}
    local host_input=${X_HOST_INPUT:-on}
    case "$host_input" in
        on | off) ;;
        *) x_display_fail "X_HOST_INPUT is on or off, not $host_input" ;;
    esac
    if [ "$kind" = xephyr ] && [ "$host_input" = off ]; then
        command -v xinput >/dev/null || x_display_fail "xinput is missing, which X_HOST_INPUT=off needs"
    fi
    mkdir -p "$work"
    rm -f "$work/display" "$work/x-display.pids"
    if [ "$kind" = desktop ]; then
        X_DISPLAY=${DISPLAY:?the desktop is \$DISPLAY, which is not set}
        echo "$X_DISPLAY" >"$work/display"
        return
    fi

    local fixed=()
    if [ -n "${X_DISPLAY_NUMBER:-}" ]; then
        fixed=(":$X_DISPLAY_NUMBER")
    fi
    # The server writes the number of its display there once it takes connections.
    local ready="$work/display-ready"
    rm -f "$ready"
    case "$kind" in
        xvfb)
            command -v Xvfb >/dev/null || x_display_fail "Xvfb is missing"
            Xvfb "${fixed[@]}" -displayfd 3 -screen 0 "${screen}x24" -nolisten tcp 3>"$ready" \
                </dev/null >"$work/xvfb.log" 2>&1 &
            ;;
        xephyr)
            command -v Xephyr >/dev/null || x_display_fail "Xephyr is missing"
            [ -n "${DISPLAY:-}" ] || x_display_fail "Xephyr opens its window on \$DISPLAY, which is not set"
            # Without -no-host-grab, Ctrl+Shift over the window hands the whole desktop's input to Xephyr until the
            # next Ctrl+Shift - and a Wayland desktop was seen to stop answering altogether with Xephyr run that way.
            # Without -noreset, the server resets whenever its last client leaves, which undoes X_HOST_INPUT=off.
            Xephyr "${fixed[@]}" -displayfd 3 -screen "$screen" -resizeable -no-host-grab -noreset -title "$title" \
                3>"$ready" </dev/null >"$work/xephyr.log" 2>&1 &
            ;;
        *) x_display_fail "X_SERVER is xvfb, xephyr or desktop, not $kind" ;;
    esac
    local server=$! waited=0
    echo "$server" >>"$work/x-display.pids"
    until grep -qx '[0-9]\+' "$ready" 2>/dev/null; do
        # A display already taken, for one.
        kill -0 "$server" 2>/dev/null || x_display_fail "$kind did not start; see $work/$kind.log"
        sleep 0.2; waited=$((waited + 1))
        [ "$waited" -lt 50 ] || x_display_fail "$kind did not start in 10s; see $work/$kind.log"
    done
    X_DISPLAY=":$(cat "$ready")"
    rm -f "$ready"
    echo "$X_DISPLAY" >"$work/display"

    # Xephyr passes the desktop's mouse and keyboard on through devices of its own, so a mouse moved over the window
    # moves the pointer on the display too. Disabled, they leave only XTEST, through which a robot clicks and types.
    if [ "$kind" = xephyr ] && [ "$host_input" = off ]; then
        local device
        for device in 'Xephyr virtual mouse' 'Xephyr virtual keyboard'; do
            if ! DISPLAY="$X_DISPLAY" xinput disable "$device" ||
                ! DISPLAY="$X_DISPLAY" xinput list --short "$device" | grep -q 'floating slave'; then
                x_display_fail "could not disable $device on $X_DISPLAY"
            fi
        done
    fi

    # Without a window manager, nothing places or focuses the windows.
    if command -v openbox >/dev/null; then
        DISPLAY="$X_DISPLAY" openbox </dev/null >"$work/openbox.log" 2>&1 &
        echo "$!" >>"$work/x-display.pids"
    fi
}

# Stops what x_display_start started in WORK_DIR.
x_display_stop() {
    local work=${1:?the directory x_display_start worked in}
    if [ -f "$work/x-display.pids" ]; then
        while read -r pid; do
            kill "$pid" 2>/dev/null || true
        done <"$work/x-display.pids"
        rm -f "$work/x-display.pids"
    fi
    rm -f "$work/display"
}
