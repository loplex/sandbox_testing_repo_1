#!/usr/bin/env bash
# Render the release's change notes, the section of CHANGELOG.md for its version, as HTML for the build.
# cmarkgfm renders them, installed from PyPI with the hashes lib/requirements.txt pins.
# It goes into a directory of its own under RUNNER_TEMP, so the calling job's Python environment is left as it was.
# Kept out of action.yml so that it can be run in a test.
#
# Reads:  PYTHON RUNNER_TEMP GITHUB_OUTPUT
# Writes: html  (to GITHUB_OUTPUT)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
packages="$RUNNER_TEMP/release-ci-change-notes"
"$PYTHON" -m pip install --quiet --disable-pip-version-check --require-hashes --target "$packages" \
  -r "$here/../../lib/requirements.txt"
# -X utf8 writes the notes as UTF-8 whatever the locale.
html=$(PYTHONPATH="$packages" "$PYTHON" -X utf8 "$here/change-notes.py")

# A delimiter nobody can have written into the notes, as GitHub advises for an output of several lines.
delimiter="change-notes-$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
{ echo "html<<$delimiter"; printf '%s\n' "$html"; echo "$delimiter"; } >> "$GITHUB_OUTPUT"

# Shown in the log with workflow commands stopped: a line of the notes starting with `::` is not run as one.
echo "::stop-commands::$delimiter"
printf '%s\n' "$html"
echo "::$delimiter::"
