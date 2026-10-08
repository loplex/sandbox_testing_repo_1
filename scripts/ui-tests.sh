#!/usr/bin/env bash
# Runs the UI tests, ./gradlew integrationTest, on a display of their own - see docs/development.md.
#
#   scripts/ui-tests.sh [GRADLE_ARGUMENTS...]     such as --tests '*FilterPopupUiTest*'
#
# X_SERVER, X_DISPLAY_NUMBER and X_SCREEN pick the display, as scripts/x-display.sh says; by default, the first virtual
# one free. The display is written to build/ui-tests/display, and its logs go there too.
# X_HOST_INPUT is off unless set: the desktop's mouse over Xephyr's window would move the pointer under the tests.
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
work="$root/build/ui-tests"
# shellcheck source=x-display.sh
source "$root/scripts/x-display.sh"

X_HOST_INPUT=${X_HOST_INPUT:-off}
trap 'x_display_stop "$work"' EXIT
x_display_start "$work" "UI tests"
echo "UI tests on display $X_DISPLAY"

# Without WAYLAND_DISPLAY, so that an IDE able to run on Wayland opens on the display chosen all the same.
cd "$root"
env -u WAYLAND_DISPLAY DISPLAY="$X_DISPLAY" ./gradlew integrationTest "$@"
