#!/usr/bin/env python3
"""Tests for what the action does with its inputs, run against a repository built for the purpose:
- which checks it runs;
- how it reads the prefix it is given;
- what it says `version` answered.

Run with `python3 -m unittest discover -s check-release` from the repository root.
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

CHECK_SH = Path(__file__).resolve().parent.parent / "check.sh"


def git(*arguments, cwd):
    return subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@t", *arguments], cwd=cwd, check=True,
                          capture_output=True, text=True)


class TheAction(unittest.TestCase):
    """A repository with one release, 0.1.0, tagged the way the test asks.

    Its commit records what release-flow/prepare records, `-SNAPSHOT` the suffix, unless `recorded` is false."""

    def stage(self, tag="v0.1.0", recorded=True):
        self.work = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        git("init", "-q", "-b", "main", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text("# Changelog\n\n## [0.1.0] - 2026-09-01\n\n- A\n", encoding="utf-8")
        git("add", "-A", cwd=self.work)
        message = "0.1.0"
        if recorded:
            message = (f"chore(release): 0.1.0\n\nRelease-CI-Version: 0.1.0\nRelease-CI-Version-Source: "
                       f"gradle.properties\nRelease-CI-Tag-Prefix: {tag.removesuffix('0.1.0') or '^none'}\n"
                       "Release-CI-Snapshot-Suffix: -SNAPSHOT\n")
        git("commit", "-qm", message, cwd=self.work)
        git("tag", tag, cwd=self.work)

    def setUp(self):
        self.stage()

    def check(self, checks, tag_prefix="v", version="", source="tags", skip_unreadable_releases=None,
              default_branch=None, snapshot_suffix="", html_notes=None, read_command=""):
        """With `html_notes`, `PYTHON` stands for a Python that has cmarkgfm already, and skips pip, which it names in
        self.pip."""
        scratch = Path(self.enterContext(tempfile.TemporaryDirectory()))
        written = scratch / "output.txt"
        written.write_text("", encoding="utf-8")
        skip = {} if skip_unreadable_releases is None else {"SKIP_UNREADABLE_RELEASES": skip_unreadable_releases}
        branch = {} if default_branch is None else {"DEFAULT_BRANCH": default_branch}
        python = sys.executable
        notes = {}
        if html_notes is not None:
            self.pip = scratch / "pip.txt"
            python = scratch / "python-stub"
            python.write_text(f'#!/usr/bin/env bash\n[[ $1 == -m ]] && {{ echo "$*" >> {self.pip}; exit 0; }}\n'
                              f'exec "{sys.executable}" "$@"\n', encoding="utf-8")
            python.chmod(0o755)
            notes = {"HTML_NOTES": html_notes, "RUNNER_TEMP": str(scratch)}
        done = subprocess.run(
            ["bash", str(CHECK_SH)], cwd=self.work, capture_output=True, text=True,
            env={**os.environ, "PYTHON": str(python), "VERSION_SOURCE": source, "TAG_PREFIX": tag_prefix,
                 "CHECKS": checks, "VERSION": version, "SNAPSHOT_SUFFIX": snapshot_suffix,
                 "VERSION_READ_COMMAND": read_command,
                 "GITHUB_OUTPUT": str(written), **skip, **branch, **notes},
        )
        outputs = dict(
            line.split("=", 1) for line in written.read_text(encoding="utf-8").splitlines() if "=" in line
        )
        return outputs, done

    def test_the_checks_asked_for_run_and_pass(self):
        _, done = self.check("changelog ancestry")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("Compared 1 released section(s)", done.stdout)
        self.assertIn("All 1 released tag(s) are reachable", done.stdout)

    def test_a_release_yet_to_land_warns_and_passes(self):
        """0.2.0 is published from its release branch, and main has yet to take it in, as before merge-back lands it.
        A commit checked in that window passes with a warning; any other failure still fails the run."""
        git("switch", "-qc", "release/0.2.0", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text("# Changelog\n\n## [0.2.0] - 2026-09-02\n\n- B\n\n"
                                                "## [0.1.0] - 2026-09-01\n\n- A\n", encoding="utf-8")
        git("commit", "-qam", "0.2.0", cwd=self.work)
        git("tag", "v0.2.0", cwd=self.work)
        git("switch", "-q", "main", cwd=self.work)
        _, done = self.check("changelog ancestry")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        for check in ("changelog", "ancestry"):
            self.assertIn(f"::warning::check-release {check} found a published release that has yet to land here",
                          done.stdout)
        self.assertNotIn("::error::", done.stdout)
        _, done = self.check("ancestry version", version="0.4.0")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("::warning::check-release ancestry found", done.stdout)
        self.assertIn("::error::check-release version said no", done.stdout)

        # Declared as the version just released, as main does until merge-back lands the commit prepare started the
        # next one in.
        (self.work / "gradle.properties").write_text("version = 0.2.0-SNAPSHOT\n", encoding="utf-8")
        outputs, done = self.check("version", tag_prefix="", source="gradle.properties", snapshot_suffix="-SNAPSHOT")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("::warning::check-release version found a published release that has yet to land here",
                      done.stdout)
        self.assertEqual(outputs, {}, "a version that only warns has not passed, and writes no output")

    def test_a_release_landed_after_the_commit_checked_warns_given_the_default_branch(self):
        """`window` was pushed while merge-back ran, and is checked only once 0.2.0 landed on origin's main.

        As actions/checkout leaves a run on a push to main: origin/main is the commit of the event, not origin's main.
        """
        (self.work / "window.txt").write_text("w\n", encoding="utf-8")
        git("add", "window.txt", cwd=self.work)
        git("commit", "-qm", "window", cwd=self.work)
        git("switch", "-qc", "release/0.2.0", "v0.1.0", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text("# Changelog\n\n## [0.2.0] - 2026-09-02\n\n- B\n\n"
                                                "## [0.1.0] - 2026-09-01\n\n- A\n", encoding="utf-8")
        git("commit", "-qam", "0.2.0", cwd=self.work)
        git("tag", "v0.2.0", cwd=self.work)
        git("switch", "-qc", "landed", "main", cwd=self.work)
        git("merge", "-q", "--no-ff", "-m", "carry 0.2.0 back", "release/0.2.0", cwd=self.work)
        origin = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        git("init", "-q", "--bare", str(origin), cwd=self.work)
        git("remote", "add", "origin", str(origin), cwd=self.work)
        git("push", "-q", "origin", "landed:refs/heads/main", cwd=self.work)
        git("switch", "-q", "main", cwd=self.work)
        git("update-ref", "refs/remotes/origin/main", "main", cwd=self.work)
        git("branch", "-qD", "landed", "release/0.2.0", cwd=self.work)
        _, done = self.check("changelog ancestry", default_branch="main")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        for check in ("changelog", "ancestry"):
            self.assertIn(f"::warning::check-release {check} found a published release that has yet to land here, "
                          f"or landed after this commit's history left the default branch", done.stdout)
        for left_out in (None, ""):
            _, done = self.check("ancestry", default_branch=left_out)
            self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
            self.assertIn("::error::check-release ancestry said no", done.stdout)

    def test_a_default_branch_that_cannot_be_fetched_is_named_and_the_checks_still_run(self):
        _, done = self.check("ancestry", default_branch="main")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("::notice::main could not be fetched again: a release that landed on it after this commit "
                      "may fail", done.stdout)
        self.assertIn("All 1 released tag(s) are reachable", done.stdout)

    def test_a_shallow_clone_does_not_fetch_the_default_branch_and_is_refused(self):
        (self.work / "next.txt").write_text("n\n", encoding="utf-8")
        git("add", "next.txt", cwd=self.work)
        git("commit", "-qm", "next", cwd=self.work)
        shallow = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve() / "shallow"
        git("clone", "-q", "--depth", "1", f"file://{self.work}", str(shallow), cwd=self.work)
        git("remote", "set-url", "origin", str(shallow / "gone"), cwd=shallow)
        self.work = shallow
        _, done = self.check("ancestry", default_branch="main")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertNotIn("could not be fetched", done.stdout)
        self.assertIn("ancestry refuses a shallow clone: check out with fetch-depth: 0", done.stdout + done.stderr)

    def test_a_check_that_says_no_fails_the_run_and_is_named(self):
        outputs, done = self.check("version", version="0.4.0")
        self.assertEqual(done.returncode, 1)
        self.assertIn("::error::check-release version said no", done.stdout)
        self.assertEqual(outputs, {})

    def test_a_tag_holding_a_line_not_read_fails_changelog_unless_skipped(self):
        """The tag cannot be edited, so only skip-unreadable-releases gets the check past it; empty is false."""
        git("tag", "-d", "v0.1.0", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text("# Changelog\n\n## [0.1.0] - 2026-09-01\n\n> A\n", encoding="utf-8")
        git("commit", "-qam", "quoted", cwd=self.work)
        git("tag", "v0.1.0", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text("# Changelog\n\n## [0.1.0] - 2026-09-01\n\n- A\n", encoding="utf-8")
        git("commit", "-qam", "listed", cwd=self.work)
        for skip in (None, "false", ""):
            with self.subTest(skip=skip):
                _, done = self.check("changelog", skip_unreadable_releases=skip)
                self.assertEqual(done.returncode, 1)
                self.assertIn("line 5 holds a block quote", done.stdout)
        _, done = self.check("changelog ancestry", skip_unreadable_releases="true")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("Not compared: 0.1.0 - CHANGELOG.md line 5 holds a block quote", done.stdout)

    def test_html_notes_refuse_raw_html_under_unreleased_with_cmarkgfm_installed_by_its_hashes(self):
        (self.work / "CHANGELOG.md").write_text("# Changelog\n\n## [Unreleased]\n\n- A <kbd>B</kbd>\n\n"
                                                "## [0.1.0] - 2026-09-01\n\n- A\n", encoding="utf-8")
        git("commit", "-qam", "kbd", cwd=self.work)
        for html_notes, status in (("true", 1), ("false", 0), ("", 0)):
            with self.subTest(html_notes=html_notes):
                _, done = self.check("changelog", html_notes=html_notes)
                self.assertEqual(done.returncode, status, done.stdout + done.stderr)
                said = "[Unreleased] holds raw HTML other than a comment, which notes --html would leave out"
                self.assertEqual(said in done.stdout, status == 1, done.stdout)
                self.assertEqual("--require-hashes" in (self.pip.read_text(encoding="utf-8")
                                                        if self.pip.exists() else ""), status == 1)

    def test_html_notes_need_the_changelog_check_and_are_true_or_false(self):
        for checks, html_notes, says in (("ancestry", "true", "html-notes is read by the changelog check alone"),
                                         ("changelog", "yes", "html-notes is 'yes', which is neither true nor false")):
            with self.subTest(checks=checks, html_notes=html_notes):
                _, done = self.check(checks, html_notes=html_notes)
                self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
                self.assertIn(says, done.stdout)
                self.assertNotIn("reachable", done.stdout)

    def test_skip_unreadable_releases_is_true_or_false(self):
        _, done = self.check("changelog", skip_unreadable_releases="yes")
        self.assertEqual(done.returncode, 1)
        self.assertIn("skip-unreadable-releases is 'yes', which is neither true nor false", done.stdout)
        self.assertNotIn("Compared", done.stdout)

    def test_a_name_that_is_no_check_fails_before_anything_runs(self):
        """A typo that was skipped would be a check that did not run, and nobody would notice."""
        _, done = self.check("ancestry ancestory")
        self.assertEqual(done.returncode, 1)
        self.assertIn("'ancestory' is not a check this action runs", done.stdout)
        self.assertNotIn("reachable", done.stdout)

    def test_checks_on_lines_of_their_own_all_run(self):
        """What a YAML block scalar hands over: one name per line, and every line is a check."""
        _, done = self.check("changelog\nancestry\n")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("Compared 1 released section(s)", done.stdout)
        self.assertIn("All 1 released tag(s) are reachable", done.stdout)

    def test_a_glob_is_a_name_and_not_the_files_it_matches(self):
        """The workspace holds CHANGELOG.md, which `*` would match.
        The refusal has to name what was asked for, not the file."""
        _, done = self.check("*")
        self.assertEqual(done.returncode, 1)
        self.assertIn("'*' is not a check this action runs", done.stdout)

    def test_asking_for_no_check_at_all_fails(self):
        for checks in ("", " ", "\n"):
            with self.subTest(checks=checks):
                _, done = self.check(checks)
                self.assertEqual(done.returncode, 1)
                self.assertIn("no check was asked for", done.stdout)

    def test_none_says_the_tags_carry_no_prefix(self):
        """`^none` passed on as a prefix of its own would count no release at all.
        The check would then pass having compared nothing."""
        self.stage(tag="0.1.0", recorded=False)
        _, done = self.check("ancestry", tag_prefix="^none")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("All 1 released tag(s) are reachable", done.stdout)

    def test_the_prefix_a_release_recorded_is_not_given_again(self):
        self.stage(tag="rel-0.1.0")
        _, done = self.check("changelog ancestry", tag_prefix="")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("All 1 released tag(s) are reachable", done.stdout)

    def test_before_a_release_records_it_the_prefix_is_given(self):
        self.stage(recorded=False)
        _, done = self.check("ancestry", tag_prefix="")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("take it in their tag-prefix input", done.stdout)

    def test_version_source_is_read_by_version_alone(self):
        """Left out, it arrives empty."""
        _, done = self.check("changelog ancestry", source="")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        outputs, done = self.check("changelog version", source="", version="0.2.0")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("::error::the version check needs version-source, which is empty", done.stdout)
        self.assertNotIn("Compared", done.stdout, "refused before any check ran")
        self.assertEqual(outputs, {})

    def test_version_says_what_may_be_released_and_where_it_goes(self):
        for asked, channel in (("0.2.0", "default"), ("0.2.0-beta.1", "beta"), ("0.2.0+build-7", "default")):
            with self.subTest(asked=asked):
                outputs, done = self.check("version", version=asked)
                self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
                self.assertEqual(outputs, {"version": asked, "channel": channel})
        # Under a source that declares its version, the version given may carry the snapshot suffix the release
        # recorded. The version in the output does not.
        (self.work / "gradle.properties").write_text("version = 0.2.0-SNAPSHOT\n", encoding="utf-8")
        outputs, done = self.check("version", version="0.2.0-SNAPSHOT", source="gradle.properties",
                                   snapshot_suffix="-SNAPSHOT")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs, {"version": "0.2.0", "channel": "default"})

    def test_a_version_the_read_command_prints_is_checked(self):
        """The command is read by `version` alone, as `version-source` is."""
        (self.work / "VERSION").write_text("0.2.0-SNAPSHOT\n", encoding="utf-8")
        outputs, done = self.check("version", source="command", snapshot_suffix="-SNAPSHOT", read_command="cat VERSION")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs, {"version": "0.2.0", "channel": "default"})
        _, done = self.check("changelog", source="command", read_command="cat VERSION")
        self.assertEqual(done.returncode, 1)
        self.assertIn("::error::version-read-command is read by the version check alone", done.stdout)

    def test_before_a_release_records_its_suffix_the_suffix_given_comes_off(self):
        """A new project, or one whose releases were all made before prepare recorded anything:
        nothing recorded says which suffix the declared version carries, so the input does."""
        self.stage(recorded=False)
        (self.work / "gradle.properties").write_text("version = 0.2.0-SNAPSHOT\n", encoding="utf-8")
        outputs, done = self.check("version", source="gradle.properties", snapshot_suffix="-SNAPSHOT")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs, {"version": "0.2.0", "channel": "default"})

    def test_the_first_recorded_release_yet_to_land_warns(self):
        """The first release prepare recorded is published from its release branch, and main has yet to take it in.
        No release main reaches records a suffix, so the input names it, and main's 0.2.0-SNAPSHOT is 0.2.0 waiting
        to land, not a pre-release below a released 0.2.0."""
        self.stage(recorded=False)
        git("switch", "-qc", "release/0.2.0", cwd=self.work)
        git("commit", "-q", "--allow-empty", "-m", "chore(release): 0.2.0\n\nRelease-CI-Version: 0.2.0\n"
            "Release-CI-Version-Source: gradle.properties\nRelease-CI-Tag-Prefix: v\n"
            "Release-CI-Snapshot-Suffix: -SNAPSHOT\n", cwd=self.work)
        git("tag", "v0.2.0", cwd=self.work)
        git("switch", "-q", "main", cwd=self.work)
        (self.work / "gradle.properties").write_text("version = 0.2.0-SNAPSHOT\n", encoding="utf-8")
        outputs, done = self.check("version", source="gradle.properties", snapshot_suffix="-SNAPSHOT")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertIn("::warning::check-release version found a published release that has yet to land here",
                      done.stdout)
        self.assertEqual(outputs, {})

    def test_under_a_declaring_source_only_version_needs_the_suffix(self):
        (self.work / "gradle.properties").write_text("version = 0.2.0-SNAPSHOT\n", encoding="utf-8")
        _, done = self.check("changelog ancestry", tag_prefix="", source="gradle.properties")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        outputs, done = self.check("version", tag_prefix="", source="gradle.properties")
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertIn("--snapshot-suffix is required under gradle.properties", done.stdout)
        self.assertEqual(outputs, {})


if __name__ == "__main__":
    unittest.main()
