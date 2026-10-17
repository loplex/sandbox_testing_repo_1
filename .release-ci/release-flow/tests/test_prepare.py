#!/usr/bin/env python3
"""What prepare does to a repository, tested against one built for the purpose.

A bare repository stands in for the remote, so a test can see that nothing reaches it.
prepare pushes nothing: the build runs from its commit next.
Nothing here needs `gh`: prepare stops before anything is drafted.

Run with `python3 -m unittest discover -s release-flow` from the repository root.
"""

import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

PREPARE = Path(__file__).resolve().parent.parent / "prepare" / "prepare.sh"
MAIN = Path(__file__).resolve().parents[2] / "lib" / "main.py"

PENDING = "# Changelog\n\n## [Unreleased]\n\n### Fixed\n\n- F\n\n## [0.1.0] - 2026-09-01\n\n- old\n"


def git(*arguments, cwd, check=True):
    return subprocess.run(["git", *arguments], cwd=cwd, check=check, capture_output=True, text=True)


class Staged(unittest.TestCase):
    """A repository between releases.

    0.1.0 is tagged and work is under [Unreleased].
    Where the source declares a version, the next one is declared with its snapshot suffix.
    """

    def stage(self, properties="version = 0.1.1-SNAPSHOT\n", changelog=PENDING, tag="v0.1.0"):
        root = Path(self.enterContext(tempfile.TemporaryDirectory())).resolve()
        self.origin = root / "origin.git"
        self.work = root / "work"
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(self.origin)], check=True)
        subprocess.run(["git", "clone", "-q", str(self.origin), str(self.work)], check=True,
                       capture_output=True)
        for name, value in (("user.email", "t@t"), ("user.name", "t")):
            git("config", name, value, cwd=self.work)
        (self.work / "CHANGELOG.md").write_text(changelog, encoding="utf-8")
        if properties is not None:
            (self.work / "gradle.properties").write_text(properties, encoding="utf-8")
        git("add", "-A", cwd=self.work)
        git("commit", "-qm", "0.1.0 and work since", cwd=self.work)
        git("tag", tag, cwd=self.work)
        git("push", "-q", "-u", "origin", "main", tag, cwd=self.work)

    def setUp(self):
        self.stage()

    def prepare(self, source="gradle.properties", tag_prefix="v", version="", repository_url="",
                skip_unreadable_releases="false", snapshot_suffix=None, html_notes=None, read_command="",
                write_command=""):
        """`snapshot_suffix` left out is the input a workflow gives: `-SNAPSHOT` where the source declares a version.
        With `html_notes`, `PYTHON` stands for a Python that has cmarkgfm already, and skips pip, which it names in
        self.pip."""
        if snapshot_suffix is None:
            snapshot_suffix = "" if source == "tags" else "-SNAPSHOT"
        scratch = Path(self.enterContext(tempfile.TemporaryDirectory()))
        written = scratch / "output.txt"
        written.write_text("", encoding="utf-8")
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
            ["bash", str(PREPARE)], cwd=self.work, capture_output=True, text=True,
            env={**os.environ, "PYTHON": str(python), "VERSION_SOURCE": source, "TAG_PREFIX": tag_prefix,
                 "VERSION": version, "REPOSITORY_URL": repository_url,
                 "SKIP_UNREADABLE_RELEASES": skip_unreadable_releases, "SNAPSHOT_SUFFIX": snapshot_suffix,
                 "VERSION_READ_COMMAND": read_command, "VERSION_WRITE_COMMAND": write_command,
                 "GITHUB_OUTPUT": str(written), **notes},
        )
        outputs = dict(
            line.split("=", 1) for line in written.read_text(encoding="utf-8").splitlines() if "=" in line
        )
        return outputs, done

    def file_on(self, branch, name):
        return git("show", f"{branch}:{name}", cwd=self.work).stdout

    def release_branches(self):
        return git("for-each-ref", "--format=%(refname:short)", "refs/heads/release", cwd=self.work).stdout.split()


class Prepare(Staged):
    def test_a_tag_holding_a_line_not_read_stops_the_release_unless_skipped(self):
        """The copy of CHANGELOG.md at v0.1.0 holds a block quote in its section, and a tag cannot be edited."""
        git("tag", "-d", "v0.1.0", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text(PENDING.replace("- old", "> old"), encoding="utf-8")
        git("commit", "-qam", "quoted", cwd=self.work)
        git("tag", "v0.1.0", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text(PENDING, encoding="utf-8")
        git("commit", "-qam", "listed", cwd=self.work)
        _, done = self.prepare()
        self.assertEqual(done.returncode, 1)
        self.assertIn("holds a block quote", done.stderr)
        self.assertEqual(self.release_branches(), [])
        outputs, done = self.prepare(skip_unreadable_releases="true")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs.get("version"), "0.1.1")

    def test_skip_unreadable_releases_is_true_or_false(self):
        _, done = self.prepare(skip_unreadable_releases="yes")
        self.assertEqual(done.returncode, 1)
        self.assertIn("skip-unreadable-releases is 'yes', which is neither true nor false", done.stdout)
        self.assertEqual(self.release_branches(), [])

    def test_the_release_commit_is_cut_onto_a_branch_of_its_own(self):
        outputs, done = self.prepare()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs, {"version": "0.1.1", "tag": "v0.1.1", "branch": "release/0.1.1"})
        self.assertEqual(git("log", "-1", "--format=%s", cwd=self.work).stdout, "chore(release): 0.1.1\n",
                         "the build runs from the release commit next, so the workspace is left on it")
        self.assertEqual(git("branch", "--show-current", cwd=self.work).stdout, "", "detached at the release commit")
        self.assertEqual(git("for-each-ref", "--contains", "HEAD", "--format=%(refname)", "refs/heads",
                             cwd=self.work).stdout, "refs/heads/release/0.1.1\n",
                         "no other branch holds the release commit")

    def test_the_next_version_is_started_above_the_release_commit(self):
        """merge-back writes nothing: the branch carries the next version to the default branch as it is."""
        outputs, done = self.prepare()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(git("log", "--format=%s", "main..release/0.1.1", cwd=self.work).stdout,
                         "chore: start 0.1.2-SNAPSHOT\nchore(release): 0.1.1\n")
        self.assertEqual(git("rev-parse", "release/0.1.1~1", cwd=self.work).stdout,
                         git("rev-parse", "HEAD", cwd=self.work).stdout)
        self.assertEqual(self.file_on("release/0.1.1", "gradle.properties"), "version = 0.1.2-SNAPSHOT\n")
        self.assertEqual(git("status", "--porcelain", cwd=self.work).stdout, "",
                         "the workspace is the release commit, not the next version")
        self.assertIn("started 0.1.2-SNAPSHOT on release/0.1.1", done.stdout)

    def test_after_a_pre_release_the_next_version_stays_in_its_core(self):
        """0.2.0-rc.1 was a step towards 0.2.0, so work goes on towards 0.2.0, not 0.2.1."""
        outputs, done = self.prepare(version="0.2.0-rc.1")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.file_on("release/0.2.0-rc.1", "gradle.properties"), "version = 0.2.0-SNAPSHOT\n")

    READ = "sed -n 's/^version = //p' gradle.properties"
    WRITE = 'printf "version = %s\\n" "$VERSION" > gradle.properties'

    def test_the_commands_given_read_and_write_both_versions(self):
        outputs, done = self.prepare(source="command", read_command=self.READ, write_command=self.WRITE)
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs["version"], "0.1.1")
        self.assertEqual(self.file_on("HEAD", "gradle.properties"), "version = 0.1.1\n")
        self.assertEqual(self.file_on("release/0.1.1", "gradle.properties"), "version = 0.1.2-SNAPSHOT\n")
        self.assertEqual(self.recorded("v0.1.1", "version-source"), "command",
                         "the commands are recorded nowhere: nothing after prepare runs them")
        self.assertNotIn(self.READ, git("log", "-1", "--format=%B", cwd=self.work).stdout)

    def test_a_next_version_that_cannot_be_written_stops_the_run_with_no_output(self):
        """The release commit is made by then, on the branch; nothing is pushed, and the next attempt replaces it.
        Gone on without it, merge-back would land the release with its own version still declared."""
        refusing = '[[ $VERSION == *-SNAPSHOT ]] && exit 7; ' + self.WRITE
        outputs, done = self.prepare(source="command", read_command=self.READ, write_command=refusing)
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertEqual(outputs, {})
        self.assertIn("::error::command could not be given 0.1.2-SNAPSHOT, so the next version was not started",
                      done.stdout)

    def test_a_command_given_where_none_is_read_or_missing_where_one_is_is_refused(self):
        """A write command is checked only when the version is first written, after the changelog is closed and the
        branch cut; a read command given to a source that takes none is refused with the checks, before either.
        Nothing reaches the remote either way."""
        for source, commands, saying, with_the_checks in (
                ("gradle.properties", {"read_command": self.READ}, "gradle.properties reads no commands", True),
                ("command", {"write_command": self.WRITE}, "none was given: --version-read-command", True),
                ("gradle.properties", {"write_command": self.WRITE}, "gradle.properties reads no commands", False),
                ("command", {"read_command": self.READ}, "none was given: --version-write-command", False),
                ("tags", {"read_command": self.READ}, "tags reads no commands", True),
                ("tags", {"write_command": self.WRITE}, "tags reads no commands", False)):
            with self.subTest(source=source, given=sorted(commands)):
                self.stage()
                before = git("for-each-ref", cwd=self.origin).stdout
                outputs, done = self.prepare(source=source, **commands)
                self.assertEqual(done.returncode, 1)
                self.assertEqual(outputs, {})
                self.assertIn(saying, done.stderr)
                self.assertEqual(git("for-each-ref", cwd=self.origin).stdout, before)
                if with_the_checks:
                    self.assertEqual(self.release_branches(), [])
                    self.assertEqual((self.work / "CHANGELOG.md").read_text(encoding="utf-8"), PENDING)

    def test_a_write_command_that_adds_its_change_to_the_index_is_taken(self):
        outputs, done = self.prepare(source="command", read_command=self.READ,
                                     write_command=self.WRITE + " && git add gradle.properties")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.file_on("release/0.1.1", "gradle.properties"), "version = 0.1.2-SNAPSHOT\n")

    def test_a_version_written_to_no_file_git_tracks_is_refused(self):
        """It would read back from the workspace, and no commit would carry it.
        It is caught at the next version, which always differs from the one released."""
        (self.work / ".gitignore").write_text("VERSION\n", encoding="utf-8")
        git("add", ".gitignore", cwd=self.work)
        git("commit", "-qm", "ignore VERSION", cwd=self.work)
        git("push", "-q", "origin", "main", cwd=self.work)
        (self.work / "VERSION").write_text("0.1.1-SNAPSHOT\n", encoding="utf-8")
        outputs, done = self.prepare(source="command", read_command="cat VERSION",
                                     write_command='echo "$VERSION" > VERSION')
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertEqual(outputs, {})
        self.assertIn("::error::command wrote 0.1.2-SNAPSHOT to no file git tracks, so the next version was not "
                      "started", done.stdout)

    def test_the_next_version_carries_the_suffix_and_the_key_given(self):
        self.stage(properties="version = 9.9.9\nappVersion = 0.1.1\n")
        outputs, done = self.prepare(source="gradle.properties:appVersion", snapshot_suffix="^none")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(self.file_on("release/0.1.1", "gradle.properties"), "version = 9.9.9\nappVersion = 0.1.2\n")
        self.assertIn("chore: start 0.1.2\n", git("log", "--format=%s", "release/0.1.1", cwd=self.work).stdout)

    def recorded(self, tag, name):
        """What lib/main.py reads back from the release commit once `tag` is put on it, as publishing puts it."""
        git("tag", "-f", tag, cwd=self.work)
        done = subprocess.run([sys.executable, str(MAIN), "recorded", tag, name], cwd=self.work,
                              capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        return done.stdout.strip()

    def test_the_release_commit_records_how_the_release_was_made(self):
        """merge-back reads these back from the commit the published tag names, instead of being given them."""
        self.prepare()
        self.assertEqual({name: self.recorded("v0.1.1", name) for name in
                          ("version", "version-source", "tag-prefix", "snapshot-suffix", "skip-unreadable-releases")},
                         {"version": "0.1.1", "version-source": "gradle.properties", "tag-prefix": "v",
                          "snapshot-suffix": "-SNAPSHOT", "skip-unreadable-releases": "false"})
        self.assertNotIn("Skip-Unreadable", git("log", "-1", "--format=%B", cwd=self.work).stdout,
                         "the skip is recorded only where it was asked for")

    def test_the_next_release_takes_the_prefix_this_one_recorded(self):
        """Published and carried back, the release records its prefix for every release after it."""
        self.prepare(tag_prefix="rel-")
        git("tag", "rel-0.1.1", cwd=self.work)
        git("switch", "-q", "main", cwd=self.work)
        git("merge", "-q", "--ff-only", "release/0.1.1", cwd=self.work)
        changelog = (self.work / "CHANGELOG.md").read_text(encoding="utf-8")
        (self.work / "CHANGELOG.md").write_text(
            changelog.replace("## [Unreleased]\n", "## [Unreleased]\n\n### Fixed\n\n- G\n", 1), encoding="utf-8")
        git("commit", "-qam", "fix: G", cwd=self.work)
        outputs, done = self.prepare(tag_prefix="")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs, {"version": "0.1.2", "tag": "rel-0.1.2", "branch": "release/0.1.2"})
        self.assertEqual(self.recorded("rel-0.1.2", "tag-prefix"), "rel-")

    def test_a_key_no_prefix_a_suffix_and_a_skip_are_recorded_as_given(self):
        self.stage(properties="appVersion = 0.1.1-dev\n", tag="0.1.0")
        outputs, done = self.prepare(source="gradle.properties:appVersion", tag_prefix="^none",
                                     skip_unreadable_releases="true", snapshot_suffix="-dev")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs["version"], "0.1.1")
        self.assertEqual(self.recorded("0.1.1", "version-source"), "gradle.properties:appVersion")
        self.assertEqual(self.recorded("0.1.1", "tag-prefix"), "^none")
        self.assertEqual(self.recorded("0.1.1", "snapshot-suffix"), "-dev")
        self.assertEqual(self.recorded("0.1.1", "skip-unreadable-releases"), "true")
        self.assertEqual(git("log", "-1", "--format=%s", cwd=self.work).stdout, "chore(release): 0.1.1\n")

    def test_under_tags_no_suffix_is_recorded_and_none_may_be_given(self):
        """There is no file to write a next version to, so none is started, and the branch is the release commit."""
        outputs, done = self.prepare(source="tags", tag_prefix="v")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(git("rev-parse", "release/0.1.1", cwd=self.work).stdout,
                         git("rev-parse", "HEAD", cwd=self.work).stdout)
        self.assertEqual(git("branch", "--show-current", cwd=self.work).stdout, "",
                         "detached as under a source that starts the next version, so the build finds the same")
        self.assertNotIn("Snapshot-Suffix", git("log", "-1", "--format=%B", cwd=self.work).stdout)
        self.assertEqual(self.recorded("v0.1.1", "snapshot-suffix"), "^none")
        git("switch", "-q", "main", cwd=self.work)
        _, done = self.prepare(source="tags", tag_prefix="v", snapshot_suffix="-SNAPSHOT")
        self.assertEqual(done.returncode, 1)
        self.assertIn("tags keeps no version between releases", done.stderr)

    def test_a_suffix_left_out_under_a_declaring_source_cuts_nothing(self):
        _, done = self.prepare(snapshot_suffix="")
        self.assertEqual(done.returncode, 1)
        self.assertIn("--snapshot-suffix is required under gradle.properties", done.stderr)
        self.assertEqual(self.release_branches(), [])

    def test_nothing_reaches_the_remote(self):
        """A build that fails after this should leave neither a branch nor a draft, so pushing waits for it.

        Every ref the remote has is compared, not only the release branch.
        A tag or another branch pushed early would be on the remote just as much.
        """
        before = git("for-each-ref", cwd=self.origin).stdout
        _, done = self.prepare()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(git("for-each-ref", cwd=self.origin).stdout, before)

    def test_the_link_definitions_are_written_where_a_repository_url_is_given(self):
        self.prepare(repository_url="https://example.invalid/r")
        changelog = self.file_on("release/0.1.1", "CHANGELOG.md")
        self.assertIn("[0.1.1]: https://example.invalid/r/compare/v0.1.0...v0.1.1\n", changelog)

    def test_the_suffix_comes_off_the_declared_version(self):
        self.prepare()
        self.assertIn("version = 0.1.1\n", self.file_on("HEAD", "gradle.properties"))

    def test_a_version_under_a_key_of_its_own_is_read_and_written_there(self):
        """`version` holds a value the release does not touch: Gradle's, for a project keeping its own key."""
        self.stage(properties="version = 9.9.9\nappVersion = 0.1.1-SNAPSHOT\n")
        outputs, done = self.prepare(source="gradle.properties:appVersion")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs["version"], "0.1.1")
        self.assertEqual(self.file_on("HEAD", "gradle.properties"), "version = 9.9.9\nappVersion = 0.1.1\n")

    def test_a_version_handed_in_without_the_suffix_is_released_as_it_is(self):
        """Only a suffix at the end of the version comes off, and 0.2.0 has none.

        The version differs from the declared 0.1.1, so a version handed in and then ignored would show.
        """
        outputs, done = self.prepare(version="0.2.0")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs["version"], "0.2.0")
        self.assertIn("version = 0.2.0\n", self.file_on("HEAD", "gradle.properties"))

    def test_the_pending_entries_become_the_released_section(self):
        self.prepare()
        changelog = self.file_on("release/0.1.1", "CHANGELOG.md")
        self.assertIn("## [0.1.1] - ", changelog)
        self.assertIn("### Fixed\n\n- F\n", changelog)
        self.assertRegex(changelog, r"## \[Unreleased\]\n\n## \[0\.1\.1\]")  # Nothing is left pending.

    def test_the_default_branch_is_not_touched(self):
        """The local default branch, the one prepare can reach.

        test_nothing_reaches_the_remote covers the remote's.
        A release commit made on the local one too would go out with its next push.
        """
        before = git("rev-parse", "main", cwd=self.work).stdout
        _, done = self.prepare()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(git("rev-parse", "main", cwd=self.work).stdout, before)

    def test_a_source_declaring_no_version_releases_what_it_is_handed(self):
        self.stage(properties=None)
        outputs, done = self.prepare(source="tags", tag_prefix="v", version="0.2.0")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs["tag"], "v0.2.0")
        self.assertIn("declares no version, so none was written; v0.2.0 is what the tag will say", done.stdout)
        self.assertIn("## [0.2.0] - ", self.file_on("release/0.2.0", "CHANGELOG.md"))

    def test_a_source_declaring_no_version_defaults_to_the_version_after_the_highest(self):
        """A patch, because the highest release, 0.10.0, is final and [Unreleased] holds only a fix.

        What follows an open pre-release train is tested beside `next_candidate` in check-release, not here.
        Lower releases sit beside it, and 0.9.0 sorts after it by name.
        So the highest is neither the only release nor the last one `git tag -l` lists.
        """
        self.stage(properties=None)
        for tag in ("v0.0.9", "v0.9.0", "v0.10.0"):
            git("tag", tag, cwd=self.work)
        outputs, done = self.prepare(source="tags", tag_prefix="v")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs["version"], "0.10.1")

    def test_none_says_the_tags_carry_no_prefix(self):
        """Passed on as a literal prefix, `^none` would match no release.

        There would then be no release to count the default from.
        """
        self.stage(properties=None, tag="0.1.0")
        outputs, done = self.prepare(source="tags", tag_prefix="^none")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual((outputs["version"], outputs["tag"]), ("0.1.1", "0.1.1"))

    def test_a_second_attempt_at_the_same_version_starts_again(self):
        """A branch left by an earlier attempt is replaced, not continued.

        The release commit and the one starting the next version are then still the only commits on top of the
        default branch.
        The default branch moves between the two attempts.
        A branch continued with the new tree carried onto it would get two more commits, and the count would be 4.
        Continued any other way, the run fails at the switch or on an empty commit, and the return code shows it.
        """
        self.prepare()
        git("switch", "-q", "main", cwd=self.work)
        (self.work / "more.txt").write_text("landed meanwhile\n", encoding="utf-8")
        git("add", "more.txt", cwd=self.work)
        git("commit", "-qm", "more work", cwd=self.work)
        git("push", "-q", "origin", "main", cwd=self.work)
        outputs, done = self.prepare()
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(git("rev-list", "--count", "main..release/0.1.1", cwd=self.work).stdout.strip(), "2")

    def test_a_version_that_cannot_be_written_leaves_its_branch_with_no_commit(self):
        """The version is declared twice, which the writer refuses.

        Treated like a source that declares nothing, the release commit would keep -SNAPSHOT on the line Gradle
        reads, and the build would make a snapshot.
        The branch is cut after the changelog is closed and before the version is written.
        So it is left with no commit on it, and the next attempt at the version replaces it.
        """
        self.stage(properties="version = 0.1.1-SNAPSHOT\nversion = 0.1.1-SNAPSHOT\n")
        outputs, done = self.prepare()
        self.assertEqual(done.returncode, 1, done.stdout + done.stderr)
        self.assertEqual(outputs, {})
        self.assertIn("could not be given 0.1.1", done.stdout)
        self.assertIn("release/0.1.1", self.release_branches(), "the branch was cut before the write failed")
        self.assertNotIn("chore(release)", git("log", "--all", "--format=%s", cwd=self.work).stdout)
        self.assertEqual(git("rev-list", "--count", "main..release/0.1.1", cwd=self.work).stdout.strip(), "0")


class NothingIsCutWhereTheRulesSayNo(Staged):
    """A release that fails the checks is never cut, so there is nothing to undo.

    No release branch, no output, nothing written, and the workspace still on the default branch.
    """

    def assert_nothing_was_cut(self, outputs, done, saying):
        self.assertNotEqual(done.returncode, 0)
        self.assertEqual(outputs, {})
        self.assertIn(saying, done.stdout + done.stderr)
        self.assertEqual(self.release_branches(), [], "no release branch is cut where the rules say no")
        self.assertEqual(git("branch", "--show-current", cwd=self.work).stdout.strip(), "main")
        self.assertEqual(git("status", "--porcelain", cwd=self.work).stdout, "",
                         "nothing is written where the rules say no")
        self.assertEqual(git("rev-list", "--count", "origin/main..main", cwd=self.work).stdout.strip(), "0",
                         "nothing is committed where the rules say no")

    def test_a_version_the_step_refuses(self):
        outputs, done = self.prepare(version="0.4.0-SNAPSHOT")
        self.assert_nothing_was_cut(outputs, done, "does not follow 0.1.0")

    def test_a_patch_over_an_addition(self):
        self.stage(changelog=PENDING.replace("### Fixed\n\n- F\n", "### Added\n\n- A\n"))
        outputs, done = self.prepare()
        self.assert_nothing_was_cut(outputs, done, "[Unreleased] holds ### Added")

    def test_a_released_section_that_was_edited(self):
        (self.work / "CHANGELOG.md").write_text(PENDING.replace("- old", "- old, said better"), encoding="utf-8")
        git("commit", "-qam", "edit", cwd=self.work)
        git("push", "-q", "origin", "main", cwd=self.work)
        outputs, done = self.prepare()
        self.assert_nothing_was_cut(outputs, done, "no longer reads as the tag has it")

    def test_raw_html_under_unreleased_with_html_notes(self):
        """notes --html would refuse it in the build, after the cut; the section could not be edited then."""
        self.stage(changelog=PENDING.replace("- F\n", "- F <kbd>G</kbd>\n"))
        outputs, done = self.prepare(html_notes="true")
        self.assert_nothing_was_cut(outputs, done, "[Unreleased] holds raw HTML other than a comment")
        self.assertIn("--require-hashes", self.pip.read_text(encoding="utf-8"))
        outputs, done = self.prepare(html_notes="false")
        self.assertEqual(done.returncode, 0, done.stdout + done.stderr)
        self.assertEqual(outputs.get("version"), "0.1.1")

    def test_html_notes_is_true_or_false(self):
        outputs, done = self.prepare(html_notes="yes")
        self.assert_nothing_was_cut(outputs, done, "html-notes is 'yes', which is neither true nor false")

    def test_a_release_yet_to_land(self):
        """0.1.1 is published from its release branch, which merge-back has yet to carry back.
        check-release only warns of it on the default branch; prepare refuses, so no release goes out above it.
        Under `tags` no version is declared, so `version` passes, and changelog and ancestry alone refuse."""
        for source in ("gradle.properties", "tags"):
            with self.subTest(source):
                self.stage()
                git("switch", "-qc", "release/0.1.1", cwd=self.work)
                (self.work / "CHANGELOG.md").write_text(PENDING.replace("[Unreleased]", "[0.1.1] - 2026-09-02"),
                                                        encoding="utf-8")
                (self.work / "gradle.properties").write_text("version = 0.1.1\n", encoding="utf-8")
                git("commit", "-qam", "chore(release): 0.1.1", cwd=self.work)
                git("tag", "v0.1.1", cwd=self.work)
                git("push", "-q", "origin", "release/0.1.1", "v0.1.1", cwd=self.work)
                git("switch", "-q", "main", cwd=self.work)
                git("branch", "-qD", "release/0.1.1", cwd=self.work)
                outputs, done = self.prepare(source=source)
                self.assert_nothing_was_cut(outputs, done, "v0.1.1 is released, and has yet to land here")
                self.assertIn("::error::the release rules said no", done.stdout)

    def test_a_release_yet_to_land_whose_copy_changelog_skips_is_refused_by_ancestry(self):
        """The copy at v0.1.1 holds a line not read above its section, which skip-unreadable-releases passes over,
        so changelog says nothing of the release, and under `tags` version does not either: ancestry refuses."""
        git("switch", "-qc", "release/0.1.1", cwd=self.work)
        (self.work / "CHANGELOG.md").write_text(
            PENDING.replace("## [Unreleased]", "> intro\n\n## [0.1.1] - 2026-09-02"), encoding="utf-8")
        git("commit", "-qam", "chore(release): 0.1.1", cwd=self.work)
        git("tag", "v0.1.1", cwd=self.work)
        git("push", "-q", "origin", "release/0.1.1", "v0.1.1", cwd=self.work)
        git("switch", "-q", "main", cwd=self.work)
        git("branch", "-qD", "release/0.1.1", cwd=self.work)
        outputs, done = self.prepare(source="tags", tag_prefix="v", skip_unreadable_releases="true")
        self.assert_nothing_was_cut(outputs, done, "v0.1.1 is released, and has yet to land here")
        self.assertIn("Not compared: 0.1.1", done.stdout + done.stderr)

    def test_nothing_under_unreleased(self):
        nothing_pending = PENDING.replace("### Fixed\n\n- F\n\n", "")
        self.stage(changelog=nothing_pending)
        outputs, done = self.prepare()
        self.assert_nothing_was_cut(outputs, done, "nothing is under [Unreleased]")
        self.assertEqual((self.work / "CHANGELOG.md").read_text(encoding="utf-8"), nothing_pending,
                         "refused before the file is rewritten")


if __name__ == "__main__":
    unittest.main()
