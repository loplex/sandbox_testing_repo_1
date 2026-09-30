#!/usr/bin/env python3
"""Every tracked file keeps to the line length .editorconfig sets for it, but for the lines `overlong` lets through.

Markdown is read by cmarkgfm, the Python binding of GitHub's own cmark-gfm. Run with
`python3 -m unittest discover -s .github` from the repository's root, with the packages in `.github/requirements.txt`
installed.
"""

import fnmatch
import html
import re
import subprocess
import unittest
from html.parser import HTMLParser
from pathlib import Path

import cmarkgfm
from cmarkgfm.cmark import Options

ROOT = Path(__file__).resolve().parent.parent


def limits(editorconfig):
    """Each section's pattern and `max_line_length`, in file order: a later section that matches wins.

    Only a pattern `fnmatch` reads the way EditorConfig does is taken, one with no `/`, `**`, braces or
    backslash, matched against the file's name; any other section is refused rather than matched wrongly. A key
    is read regardless of case, as the specification has it, and `off` or `unset` is no limit. A limit standing
    before any section applies to nothing, and is refused."""
    sections, pattern = [], None
    for line in editorconfig.splitlines():
        line = line.strip()
        if not line or line.startswith(("#", ";")):
            continue
        if line.startswith("["):
            pattern = line[1:-1]
            if not line.endswith("]") or re.search(r"/|\*\*|[{}\\]", pattern):
                raise ValueError(f"a section this test cannot match: {line}")
            continue
        key, _, value = line.partition("=")
        if key.strip().lower() == "max_line_length":
            if pattern is None:
                raise ValueError(f"a limit before any section: {line}")
            value = value.strip().lower()
            sections.append((pattern, None if value in ("off", "unset") else int(value)))
    return sections


def rendered(text, options=0):
    return cmarkgfm.github_flavored_markdown_to_html(text, options=options)


class Blocks(HTMLParser):
    """Which lines of a Markdown text are code and which a table, and where each second-level heading stands, as
    cmark-gfm's source positions give them."""

    def __init__(self, text):
        super().__init__(convert_charrefs=True)
        self.code, self.table, self.headings, self.heading = set(), set(), [], None
        self.feed(rendered(text, Options.CMARK_OPT_SOURCEPOS))

    def handle_starttag(self, tag, attrs):
        position = dict(attrs).get("data-sourcepos")
        if not position:
            return
        first, last = (int(end.split(":")[0]) for end in position.split("-"))
        if tag == "pre":
            self.code.update(range(first, last + 1))
        elif tag == "table":
            self.table.update(range(first, last + 1))
        elif tag == "h2":
            self.heading = (first, [])

    def handle_data(self, data):
        if self.heading:
            self.heading[1].append(data)

    def handle_endtag(self, tag):
        if tag == "h2" and self.heading:
            self.headings.append((self.heading[0], "".join(self.heading[1]).strip()))
            self.heading = None


def let_through(line, previous, width):
    """Whether a line of Markdown prose past `width` is let through, as cmark-gfm reads the line: a link definition,
    on one line or with its destination on the next; or a line whose first content, after any list or quote marker,
    is a link too long for a line of its own, whatever sticks to it included, with one word after it at most."""
    if not rendered(line.lstrip()).strip():
        return True
    if previous.strip() and not rendered(previous.lstrip() + "\n" + line.lstrip()).strip():
        return True
    body = re.sub(r"^(?:\s*<(?:ul|ol|li|blockquote|p)\b[^>]*>)*\s*", "", rendered(line.lstrip()))
    if not body.startswith("<a "):
        return False
    after = html.unescape(re.sub(r"<[^>]+>", "", body[body.index("</a>") + len("</a>"):]))
    words = re.sub(r"^\S*", "", after).split()
    if len(words) > 1:
        return False
    kept = line.rstrip()
    if words:
        kept = kept[:kept.rfind(" ")]
    return len(kept) > width


def let_through_in_code(line, width):
    """Whether a line of code past `width` is let through: one whose first word, after any comment marker, is a URL
    too long for a line of its own, with one word after it at most."""
    body = re.sub(r"^\s*(?:(?:#+|//)\s*)?", "", line)
    first, _, rest = body.partition(" ")
    return "://" in first and len(line) - len(body) + len(first) > width and len(rest.split()) <= 1


def limit_for(name, sections):
    found = None
    for pattern, value in sections:
        if fnmatch.fnmatchcase(Path(name).name, pattern):
            found = value
    return found


def overlong(name, text, sections):
    """The lines past the file's limit, as (line number, length).

    - A line opening with a link too long for a line of its own, one word after it at most, is let through, and
      so is a link definition, on one line or two. A link short enough goes to a line of its own instead.
    - In Markdown, a table row is as wide as its widest cell, and is let through too.
    - A block of code in Markdown, fenced or indented, is held to the limit of `[*]` rather than that of prose.
    - Every line of CHANGELOG.md from the first section below `[Unreleased]` on is let through: those are the
      sections releases close, `check-release changelog` refuses any edit to one once its release is tagged, and
      the link definitions ending the file are let through anyway.

    A length is counted in characters, which for the text here is the columns an editor shows."""
    limit, code, lines, found = limit_for(name, sections), dict(sections).get("*"), text.splitlines(), []
    blocks = Blocks(text) if name.endswith(".md") else None
    released = min((number for number, heading in blocks.headings if not re.match(r"\[?Unreleased\b", heading)),
                   default=None) if name == "CHANGELOG.md" else None
    for number, line in enumerate(lines, 1):
        in_code = blocks is None or number in blocks.code
        width = code if blocks and in_code else limit
        if width is None or len(line) <= width:
            continue
        if blocks and (number in blocks.table or (released and number >= released)):
            continue
        if in_code and let_through_in_code(line, width):
            continue
        if not in_code and let_through(line, lines[number - 2] if number > 1 else "", width):
            continue
        found.append((number, len(line)))
    return found


class LineLength(unittest.TestCase):

    SECTIONS = [("*", 120), ("*.md", 100)]

    def test_no_tracked_file_runs_past_its_limit(self):
        sections = limits((ROOT / ".editorconfig").read_text(encoding="utf-8"))
        names = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, check=True, capture_output=True,
                               text=True).stdout.split("\0")[:-1]
        self.assertTrue(names)
        self.assertEqual([name for name in names if Path(name).name == ".editorconfig" and name != ".editorconfig"],
                         [], "a nested .editorconfig, which this test does not read")
        for name in filter(lambda name: (ROOT / name).is_file(), names):
            text = (ROOT / name).read_text(encoding="utf-8")
            for number, length in overlong(name, text, sections):
                self.fail(f"{name}:{number} is {length} columns, past {limit_for(name, sections)}")

    def test_markdown_prose_is_held_to_its_own_limit(self):
        self.assertEqual(overlong("README.md", "x" * 100 + "\n" + "y" * 101, self.SECTIONS), [(2, 101)])
        self.assertEqual(overlong("a.sh", "x" * 101, self.SECTIONS), [])
        self.assertEqual(overlong("a.sh", "x" * 121, self.SECTIONS), [(1, 121)])

    def test_a_table_row_a_link_too_long_for_a_line_and_a_link_definition_are_let_through(self):
        url = "https://example.com/" + "x" * 150
        for name, line in (("README.md", "| a |\n|---|\n| " + "x" * 150 + " |"),
                           ("README.md", f"[a guide to {'x' * 20}]({url}).\\"),
                           ("README.md", f"- [x]({url}) says"), ("README.md", f"[x]({url}), says"),
                           ("README.md", f'[x]({url} "a title")'),
                           ("README.md", f"<{url}>"), ("README.md", f"[x]: {url}"),
                           ("README.md", f"[x]: <../{'x' * 100}.md> 'a title'"), ("a.yml", f"# {url}"),
                           ("README.md", f"> 1. [x]({url})"), ("README.md", f"[x]: <../a b {'x' * 100}.md>")):
            with self.subTest(line[:30]):
                self.assertEqual(overlong(name, line, self.SECTIONS), [])
        for name, line in (("README.md", f"[x]:\n  {url}"), ("README.md", f"[x]:\n  ../{'x' * 100}.md")):
            with self.subTest(line[:30]):
                self.assertEqual(overlong(name, line, self.SECTIONS), [])

    def test_a_label_with_nothing_after_it_is_no_definition(self):
        text = "[Note]:\n" + "words " * 30
        self.assertEqual(overlong("README.md", text, self.SECTIONS), [(2, len("words " * 30))])

    def test_a_link_that_a_rewrap_could_move_does_not_excuse_its_line(self):
        """Text before the link could go to the line above, more than a word after it to the line below, and a link
        short enough for a line of its own belongs on one."""
        long = f"[the guide to {'x' * 20}](https://example.com/{'y' * 60})"
        for line in (f"See {long}.", f"{long} says so.", f"[a short one](README.md) and then {'words ' * 20}",
                     f"[a short one](README.md) {'x' * 90}",
                     f"https://example.com/ and then {'words ' * 20}"):
            with self.subTest(line[:30]):
                self.assertEqual(overlong("README.md", line, self.SECTIONS), [(1, len(line))])

    def test_a_block_of_code_is_held_to_the_limit_of_code(self):
        text = "```yaml\n" + "x" * 120 + "\n" + "y" * 121 + "\n```\n" + "z" * 101
        self.assertEqual(overlong("README.md", text, self.SECTIONS), [(3, 121), (5, 101)])
        indented = "Prose.\n\n    " + "x" * 116 + "\n    " + "y" * 117
        self.assertEqual(overlong("README.md", indented, self.SECTIONS), [(4, 121)])

    def test_a_backtick_fence_with_a_backtick_after_it_opens_no_block(self):
        text = "``` `code` in prose\n" + "x" * 101
        self.assertEqual(overlong("README.md", text, self.SECTIONS), [(2, 101)])

    def test_a_fence_closes_only_the_way_commonmark_closes_it(self):
        """Neither a fence with an info string nor one indented four spaces closes a block, and a shorter one does
        not close a longer; a line of prose after any of them would otherwise be held to the limit of code."""
        for fence, inside in (("```", "```yaml"), ("```", "    ```"), ("````", "```"), ("```", "~~~")):
            with self.subTest(inside):
                text = f"{fence}\n{inside}\n" + "x" * 101 + f"\n{fence}\n" + "y" * 101
                self.assertEqual(overlong("README.md", text, self.SECTIONS), [(5, 101)])

    def test_only_the_released_sections_of_the_changelog_are_let_through(self):
        text = "## [Unreleased]\n" + "x" * 101 + "\n## [1.0.0] - 2026-01-01\n" + "y" * 101
        self.assertEqual(overlong("CHANGELOG.md", text, self.SECTIONS), [(2, 101)])
        inside = "## [Unreleased]\n" + "x" * 101 + "\n## [1.0.0] - 2026-01-01\n[x]: ../a\n" + "y" * 101
        self.assertEqual(overlong("CHANGELOG.md", inside, self.SECTIONS), [(2, 101)])
        self.assertEqual(overlong("docs/CHANGELOG.md", text, self.SECTIONS), [(2, 101), (4, 101)])

    def test_a_section_the_test_cannot_match_is_refused(self):
        for header in ("[{a,b}.md]", "[docs/*.md]", "[**.md]", "[\\*.md]"):
            with self.subTest(header), self.assertRaises(ValueError):
                limits(f"root = true\n{header}\nmax_line_length = 80\n")

    def test_a_limit_before_any_section_is_refused(self):
        with self.assertRaises(ValueError):
            limits("root = true\nmax_line_length = 80\n[*]\nmax_line_length = 100\n")

    def test_a_later_section_wins(self):
        sections = limits("[*]\nmax_line_length = 120\n\n[*.md]\nMAX_LINE_LENGTH = 100\n\n"
                          "[*.txt]\nmax_line_length = off\n\n[*.cfg]\nmax_line_length = unset\n")
        self.assertEqual([limit_for(f"a.{ext}", sections) for ext in ("md", "py", "txt", "cfg")],
                         [100, 120, None, None])


if __name__ == "__main__":
    unittest.main()
