#!/usr/bin/env python3
"""Say on a published release that publishing it somewhere else did not complete, or take that back once it has.

Someone who has the release's files in hand looks at the release, not through the runs of a workflow, so a
publish that went red is said where they will see it: a warning at the top of the notes, linking the run. The
next run whose publish completes takes that warning off again, and only it - the notes around it are the
release's own.

The warning sits between two HTML comments, which GitHub keeps in the notes as they were written and does not
show. They are what the warning is found by, rather than its wording, so that a warning written by another
version of this action, in other words, is still the one taken off, and a warning the notes carry of their own
is never taken for it.

Driven by environment rather than by arguments, because that is how a composite action hands its inputs over -
and kept out of action.yml so that it can be run against a stub `gh` in a test.

Reads: OUTCOME TAG TO RUN
       GH_TOKEN, by `gh` rather than by anything here
"""

import json
import os
import subprocess
import sys
import tempfile

START = "<!-- release-flow/warn -->"
END = "<!-- /release-flow/warn -->"


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


def is_marker(line, marker):
    return line.rstrip("\r\n").strip() == marker


def without_warning(notes):
    """The notes with every warning between the markers taken out, each with the blank line that follows it.

    Line ends are kept as they were: GitHub keeps notes as they were sent, CRLF included, so taking the warning
    off gives back the notes as they stood before it, and one put on is written in their line ends.
    """
    lines = notes.splitlines(keepends=True)
    kept, index = [], 0
    while index < len(lines):
        line = lines[index]
        if is_marker(line, END):
            fail(f"the notes of {TAG} close a warning that nothing opens; they are left as they are")
        if not is_marker(line, START):
            kept.append(line)
            index += 1
            continue
        end = next((at for at in range(index + 1, len(lines)) if is_marker(lines[at], END)), None)
        if end is None or any(is_marker(lines[at], START) for at in range(index + 1, end)):
            fail(f"the notes of {TAG} open a warning that nothing closes; they are left as they are")
        index = end + 1
        if index < len(lines) and not lines[index].strip():
            index += 1
    return "".join(kept)


def with_warning(notes):
    """The notes with the warning at their top, in place of any they carried: a run that fails again, or a
    later version of this action, says it once and says it the way it says it now."""
    rest = without_warning(notes)
    newline = "\r\n" if "\r\n" in notes else "\n"
    block = [
        START,
        "> [!WARNING]",
        # One sentence to a line: GitHub shows a line end in release notes as a line break.
        f"> Publishing this release to {TO} did not complete: see [the run]({RUN}).",
        "> What it serves for this version may not be what this release carries.",
        END,
    ]
    written = "".join(line + newline for line in block)
    return written + newline + rest if rest else written


OUTCOME, TAG, TO, RUN = (os.environ.get(name, "") for name in ("OUTCOME", "TAG", "TO", "RUN"))

# The outcome is a step's, handed over as `steps.<id>.outcome`. An id that names no step hands over nothing,
# which is refused rather than read as a failure: every release would be said to have failed to publish.
if OUTCOME not in ("success", "failure", "skipped"):
    fail(f"outcome is '{OUTCOME}', where success, failure or skipped was expected: does steps.<id> name the "
         f"step that publishes?")
if not TAG:
    fail("tag names no release")
if not TO.strip() or "\n" in TO or "\r" in TO:
    fail("to has to say, on one line, what the release is published to")

# Read as JSON rather than through `--jq`: jq ends what it prints with a line end, which the notes need not.
viewed = subprocess.run(["gh", "release", "view", TAG, "--json", "body"], capture_output=True, text=True)
if viewed.returncode != 0:
    print(viewed.stderr, file=sys.stderr, end="")
    fail(f"the notes of {TAG} could not be read")
notes = json.loads(viewed.stdout)["body"] or ""

# `skipped` warns as `failure` does: the publish step not running is a publish that did not complete, the step
# before it having failed - taking the archive off the release, say.
wanted = without_warning(notes) if OUTCOME == "success" else with_warning(notes)
if wanted == notes:
    print(f"The notes of {TAG} already say what this run would; they are left as they are.")
    sys.exit(0)

with tempfile.NamedTemporaryFile("w", encoding="utf-8", newline="", suffix=".md", delete=False) as written:
    written.write(wanted)
try:
    edited = subprocess.run(["gh", "release", "edit", TAG, "--notes-file", written.name])
finally:
    os.unlink(written.name)
if edited.returncode != 0:
    fail(f"the notes of {TAG} could not be written")
if OUTCOME == "success":
    print(f"The warning is off {TAG}: publishing it has completed.")
else:
    print(f"{TAG} now says that publishing it to {TO} did not complete.")
