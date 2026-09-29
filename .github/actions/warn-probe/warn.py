#!/usr/bin/env python3
"""Say on a published release that publishing it somewhere else did not complete, or take that back once it has.

Someone who has the release's files in hand looks at the release, not through the runs of a workflow, so a
publish that went red is said where they will see it: a warning at the top of the notes, linking the run. The
next run whose publish completes takes that warning off again, and only it - the notes around it are the
release's own.

The warning sits between two HTML comments, which GitHub keeps in the notes as they were written and does not
show. They are what the warning is found by, rather than its wording, so that a warning written by another
version of this action, in other words, is still the one taken off, and a warning the notes carry of their own
is never taken for it. Nor is a marker the notes show as code: one counts only on a line of its own, from its
very first column, outside a fenced code block - which leaves out every line a Markdown renderer shows as code.

Driven by environment rather than by arguments, because that is how a composite action hands its inputs over -
and kept out of action.yml so that it can be run against a stub `gh` in a test.

Reads: OUTCOME TAG TO RUN
       GH_TOKEN GH_REPO, by `gh` rather than by anything here
"""

import json
import os
import re
import subprocess
import sys
import tempfile

START = "<!-- release-flow/warn -->"
END = "<!-- /release-flow/warn -->"


def fail(message):
    print(f"::error::{message}")
    sys.exit(1)


# A line and its end, the ends being those CommonMark knows: str.splitlines would also end a line at a form
# feed or a Unicode line separator, which Markdown reads as text.
LINE = re.compile(r"[^\r\n]*(?:\r\n|\r|\n)|[^\r\n]+\Z")

# CommonMark's code fence: three or more backticks or tildes, indented by at most three spaces. A backtick
# fence's info string may hold no backtick, or the line is text with inline code in it rather than a fence.
FENCE = re.compile(r" {0,3}(`{3,}(?=[^`]*$)|~{3,})")


def code_lines(lines):
    """Which of the lines sit inside a fenced code block, the fences included.

    Closed, as CommonMark closes one, by a fence of the same character, at least as long, with nothing after it
    but spaces and tabs; one never closed runs to the end of the notes. An indented code block needs no finding
    here: its lines are indented by four spaces or a tab, and a marker counts only from the first column.
    """
    inside, fence = [], None
    for line in lines:
        text = line.rstrip("\r\n")
        if fence is None:
            opened = FENCE.match(text)
            if opened:
                fence = opened.group(1)
            inside.append(fence is not None)
        else:
            inside.append(True)
            closed = re.fullmatch(r" {0,3}(`+|~+)[ \t]*", text)
            if closed and closed.group(1)[0] == fence[0] and len(closed.group(1)) >= len(fence):
                fence = None
    return inside


def without_warning(notes):
    """The notes with every warning between the markers taken out, each with the blank line that follows it.

    Line ends are kept as they were: GitHub keeps notes as they were sent, CRLF included, so taking the warning
    off gives back the notes as they stood before it, and one put on is written in their line ends.
    """
    lines = LINE.findall(notes)
    code = code_lines(lines)

    def is_marker(at, marker):
        return not code[at] and lines[at].rstrip("\r\n").rstrip(" \t") == marker

    kept, index = [], 0
    while index < len(lines):
        if is_marker(index, END):
            fail(f"the notes of {TAG} close a warning that nothing opens; they are left as they are")
        if not is_marker(index, START):
            kept.append(lines[index])
            index += 1
            continue
        end = next((at for at in range(index + 1, len(lines)) if is_marker(at, END)), None)
        if end is None or any(is_marker(at, START) for at in range(index + 1, end)):
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
        f"> What {TO} serves for this version may not be what this release carries.",
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
