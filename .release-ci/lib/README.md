# lib

[`main.py`](main.py) is the command line behind these actions:

- [`check-release`](../check-release/README.md) runs its checks;
- [`release-flow`](../release-flow/README.md) cuts, drafts and carries back a release with it;
- [`intellij/build`](../intellij/README.md) reads the version the signed archive has to carry, and
  renders its change notes, with it;
- [`intellij/publish`](../intellij/README.md) reads a version's channel with it.

A workflow calls the actions, not `main.py`.

`main.py` checks a repository before and after a release, and writes what a release changes in it.\
It reads three things:

- the tags of the repository;
- `CHANGELOG.md` of the same repository;
- the version the release is asked to be, from the source the call names.

The first two work the same in every project.\
The third depends on the kind of project, as
[Where the version comes from](#where-the-version-comes-from) describes.

## Subcommands

`main.py` runs on the Python [`.python-version`](../.python-version) names, which the
actions install for themselves.\
No older Python is tested.

`main.py` has one subcommand per question a release asks:

| Subcommand        | What it does                                                                                                |
|-------------------|-------------------------------------------------------------------------------------------------------------|
| `version`         | checks whether the [candidate version](#where-the-version-comes-from) may be released next                  |
| `next`            | prints the version after a released one, with the [snapshot suffix](#the-snapshot-suffix) given             |
| `changelog`       | checks that every released section of `CHANGELOG.md` still reads as its tag has it                          |
| `ancestry`        | checks that every release tag is still reachable from the current history                                   |
| `prefix`          | prints what release tags carry in front of the version                                                      |
| `channel`         | prints the [distribution channel](#the-channel-a-version-goes-to) of a version                              |
| `set-version`     | writes a version where the source declares it                                                               |
| `close-changelog` | moves what is under `[Unreleased]` into a dated section for the version being released                      |
| `notes`           | prints the text of one released section, which is what its release notes say                                |
| `recorded`        | prints a setting `release-flow/prepare` recorded in the commit of a release tag                             |

`version`, `changelog` and `ancestry` refuse to answer in a shallow clone,
which cuts the history between the release tags short.

The options go after the subcommand name, and a subcommand refuses an option it never reads:

- `--version-source`: `version` and `set-version`.\
  The other commands read no source: the [tag prefix](#the-tag-prefix) does not come from one.
- `--tag-prefix`: `version`, `next`, `changelog`, `ancestry`, `prefix` and `close-changelog`.\
  `close-changelog` reads it only with `--repository-url`, and takes it either way.
- `--skip-unreadable-releases`: `changelog` alone, as [The changelog](#the-changelog) says.
- `--html-notes`: `changelog` alone.\
  It refuses raw HTML under `[Unreleased]` that `notes --html` would leave out, before a release
  makes it a section that cannot be edited.\
  It needs the packages `--html` needs.
- `--snapshot-suffix`: `version` and `next`, as [The snapshot suffix](#the-snapshot-suffix) says.
- `--default-branch`: `version`, `changelog` and `ancestry`, as
  [A release landed after this commit](#a-release-landed-after-this-commit-exits-with-4-too) says.
- `--html`: `notes` alone.\
  It prints the section as HTML, rendered by cmark-gfm, the renderer GitHub's Markdown is built on.\
  It leaves comments out, and refuses a section holding other raw HTML, which it would leave
  out: a `<kbd>`, for one, whose tags the release page keeps.\
  A comment that `<!-->` or `<!--->` opens, or `--!>` closes, may be refused where nothing would be
  lost, or, with an older Python, pass where text after `--!>` would be.\
  It needs the packages in [`requirements.txt`](requirements.txt):
  `python3 -m pip install --require-hashes -r lib/requirements.txt`.
- `--repository-url` with `--ref`: `notes --html` alone.\
  They make every relative link in the HTML absolute, read at that tag or commit of the repository.\
  [Relative links in the HTML](#relative-links-in-the-html) says where each one then leads.\
  `close-changelog` takes a `--repository-url` of its own, for the versions' link definitions.
- Neither `--version-source` nor `--tag-prefix`: `notes`, `channel` and `recorded`.\
  `recorded <tag> <name>` reads the setting from the commit the tag names, as
  [merge-back reads it](../release-flow/README.md#merge-back-reads-how-the-release-was-made-from-the-release-commit),
  and refuses a tag that commit does not record.

## A release yet to land exits with 4

A release is published from its branch, `release/<version>`.\
It reaches the default branch only when `release-flow/merge-back` carries it there, or its pull
request does once merged.\
Until then the default branch does not reach its tag, and no commit made there in that window does.

A release is yet to land where all three hold:

- `release/<version>` holds its tag.\
  The branch is looked for among the fetched branches, and among the local ones only where no
  fetched branch holds the tag.
- Every parent of the tagged commit is on the current history.\
  A force-push below the point the release was cut from fails this.
- `CHANGELOG.md` in the tree has no section for it.\
  A squash or a rebase merge copies the release commit, and the section with it.\
  A tree whose `CHANGELOG.md` is missing, is not UTF-8, or holds a line `main.py` does not
  [read](#what-mainpy-reads-in-changelogmd), cannot tell, and its release is failed.

Each check reports such a release as yet to land, naming the branch that holds it:

- `changelog` does not compare its section, where it reads the copy at its tag, or, with
  `--skip-unreadable-releases`, reads it past that section's heading.\
  A copy it reads less of fails the check, or with `--skip-unreadable-releases` is named as not
  compared, as for any other release.
- `version`, where the source declares a version, no version is handed in, and the version declared
  is that release, does not refuse it as released already.\
  The default branch declares it until merge-back lands the commit that starts the next one.\
  A version handed in with `--version` is still refused.

A check that finds nothing else exits with status 4, and with 1 where it finds more.

- `check-release` reads 4 as a warning.
- `release-flow/prepare` refuses, as on any other status but 0, so no release is prepared above one
  that has yet to land.
- `release-flow/merge-back` leaves its merged tree to a pull request, as on any other status but 0.

A force-push that takes a release back off the default branch after it landed passes all three while
`release/<version>` is still there, and is reported as yet to land.\
merge-back deletes the branch when its own push lands the release.\
Delete it once merge-back's pull request is merged, as
[The release branch](../release-flow/README.md#the-release-branch) says.

### A release landed after this commit exits with 4 too

A commit pushed while merge-back runs may be checked only once the release has landed:
a re-run, or a run that waited in a queue.\
`release/<version>` is usually gone by then, and the default branch holds the tag.\
The commit is older than the release, not cut off from it.\
While `release/<version>` is still there, the release is reported as yet to land instead.

`--default-branch` names the branch releases land on, such as `main`.\
With it, a release landed after this commit where all three hold:

- That branch holds its tag, looked for as `release/<version>` is above.\
  A squash or a rebase merge leaves the branch a copy of the release commit instead.
- Every parent of the tagged commit is on the current history.
- `CHANGELOG.md` in the tree has no section for it.

A commit made later on a branch that holds the commit the release was cut from, but has not taken
in the default branch since the release landed, meets all three too, and is reported the same way:
its history is older than the release.

Each check reports it as landed after this commit's history left that branch, naming the branch,
and exits with 4 where it finds nothing else, as for a release yet to land.\
Without `--default-branch`, such a release fails `ancestry` and `changelog`, and `version` refuses
the version as released already where the tree still declares it.

## `ancestry` names a branch that holds a tag

A release tag that the current history does not reach fails `ancestry`, unless it is
[yet to land](#a-release-yet-to-land-exits-with-4) or
[landed after this commit](#a-release-landed-after-this-commit-exits-with-4-too).\
Where a branch holds the tag, the report names a branch that holds it and says no more:
`v0.3.0 is released but is not on this history: main holds it`.\
The cause can be a squash merge, a history that left the branch before the release was cut, etc.\
A tag that no branch holds is reported as taken off the history by a rewrite.

## Versions

A version is a semantic version: `1.0.0`, `1.0.0-rc.1`, `1.0.0-eap-2`, `1.0.0+dfsg1`.\
The grammar is the one SemVer 2.0.0 defines.\
A leading zero or an empty identifier is refused, because SemVer gives it no order.

### What `version` refuses

`version` refuses a candidate that:

- is not a semantic version;
- still carries the [snapshot suffix](#the-snapshot-suffix) of a version being worked on;
- does not come after the last release it is offered next to:
  - a final release has to come after the last final release;
  - a pre-release has to come after the highest release of any kind;
- has a core that is not one step past the last final release: after `0.2.0`, the next one is
  `0.2.1`, `0.3.0` or `1.0.0`, or a pre-release of one of them;
- moves less than [`[Unreleased]` asks for](#what-unreleased-asks-of-the-version).

A stable hotfix such as `0.2.1` may therefore go out while `0.3.0-beta.1` is open, from a branch
whose changelog does not hold the beta's section.\
Where it does, the beta's entries ask for a step as
[`[Unreleased]`'s do](#what-unreleased-asks-of-the-version).\
Where only pre-releases exist, the next version stays in their core: after `0.2.0-rc.1`, a later
pre-release of `0.2.0`, or `0.2.0` itself.\
With nothing released yet, any valid version may be the first.

### Build metadata does not order releases

Build metadata is the part after `+`, as in `1.0.0+dfsg1`.\
SemVer says it plays no part in ordering:

> Build metadata MUST be ignored when determining version precedence. Thus two versions that differ
> only in the build metadata, have the same precedence.

So `1.0.0+b` does not come after `1.0.0+a`, and releasing it after `1.0.0+a` is refused.\
The refusal says the two differ only in build metadata.
It does not say "does not come after", which would look like a bug for a version made later.

A project that needs `+dfsg1` to count as an upgrade needs an ordering SemVer does not define.\
That rule would go into `precedence()` in [`rules/versions.py`](rules/versions.py).

### The channel a version goes to

The channel is the first identifier of the pre-release suffix, or `default` for a final release.

- Identifiers are separated by dots.
- A hyphen is an ordinary character inside an identifier.
- Build metadata plays no part.

| Version       | Channel                 |
|---------------|-------------------------|
| `1.0.0`       | `default`               |
| `1.0.0-rc.1`  | `rc`                    |
| `1.0.0-eap-2` | `eap-2`, one of its own |
| `1.0.0-eap.2` | `eap`                   |

`channel` takes the version without the tag prefix: `0.3.0-beta.1`, not `v0.3.0-beta.1`.

## The changelog

`CHANGELOG.md` is read in the format of
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/):

- a `## [Unreleased]` section on top;
- a `## [<version>]` section for each release below it.

`close-changelog`, which `release-flow/prepare` runs, turns `[Unreleased]` into the section of the
release.
It refuses:

- a file with no `[Unreleased]` section;
- a version that already has a section;
- an `[Unreleased]` that says nothing, unless the release is a final one whose pre-releases to take
  in say something.\
  HTML comments say nothing: GitHub shows none of them.\
  Nor does a line where Python's `str.isprintable()` accepts none of its characters but the space:
  white space, a no-break space, a zero-width space or a soft hyphen alone say nothing, and
  `U+3164`, drawn blank, says something.\
  A link definition, such as `[//]: # (nothing removed yet)`, says nothing either: GitHub shows
  it nowhere.\
  In an entry or a line of text, a link counts as its text, and `*`, `_` and `~` as nothing, as the
  marks of emphasis and strikethrough would; a character reference such as `&nbsp;` counts as its
  character.
- an entry under `[Unreleased]` that says nothing on its line or in the lines of text right below
  it, indented as far as its text, such as `- <!-- what changed -->` left as a reminder, by its line
  number: released, it would be an empty bullet.\
  Any other line ends what is read, such as a blank line, a comment or a code block, and so does a
  line indented less: an entry whose text stands only past it is refused too, though GitHub may show
  that text.
- text that is read as saying nothing in a `###` group that holds nothing else but comments and
  link definitions, a comment's line with something after its `-->` included, by its line number:
  the group would be left out, and the text with it.\
  It is refused even where the entries of a pre-release taken in would keep the group.\
  A pre-release section closed by release-ci 0.2 or earlier was not checked for such text, nor for
  an entry that says nothing, so a final release that takes it in can leave its group out with them,
  or show such an entry as an empty bullet where its group says something.

Markdown is read only this far, not rendered, so the verdict can differ from GitHub's either way: an
`[Unreleased]` or an entry left empty by an HTML tag with nothing in it, or by a comment that runs
on over lines, can pass, and an entry of nothing but `*` is refused.

A `###` group with nothing under it, comments and link definitions aside, is left out of the
section instead.\
A link definition in it stays where the group stood, since a link elsewhere may use it.\
An `[Unreleased]` of nothing but such groups, as the Gradle changelog plugin leaves it after a
release, then says nothing and is refused.

A final release takes into its section the entries of every pre-release that SemVer orders below it
and above the final release before it.\
That includes an abandoned pre-release of another version: `1.0.0` after `0.3.0-beta.1` takes that
beta's entries in.\
A pre-release takes in nothing but its own entries.

`notes` prints a released section below its heading, without the link definitions in it.\
It follows the section with the definitions from anywhere in the file whose label the section names
in brackets, so a reference link such as `[the issue][1]` stays a link on the release page.\
Any text in brackets counts, so a definition can be printed by chance, such as one for a version
the text mentions as `[0.2.0]`: GitHub shows no definition, and only that text becomes a link.

The `changelog` check compares every released section with the copy in its release tag.

- `[Unreleased]` is not compared.
- Link definitions are not compared, wherever they stand: given a repository URL, a release
  rewrites the versions' own.\
  `notes` prints the ones a section names as the file holds them when it runs, so one edited later
  moves the changelog's link and not the release page's.\
  Adding or removing one inside a released section still fails the check: its line is left blank,
  not taken out, so the section's blank lines change.
- A release tagged before its section existed has nothing to compare, and is listed as not
  compared.
- A section that is missing because its tag is not on the current history is reported as such:
  neither the section nor the tag is here, and `ancestry` reports the tag.\
  A release still on its way here is [awaited](#a-release-yet-to-land-exits-with-4), not failed.
- The copy at a tag is read down to the end of that release's section.\
  An older section below it is not read there.\
  A copy without that section is read whole.
- A line in the copy that `main.py` does not
  [read](#what-mainpy-reads-in-changelogmd), in the part that is read, fails the check.\
  A tag cannot be edited, so it fails on every run.\
  With `--skip-unreadable-releases`, the release is listed as not compared instead, with that line.\
  Where that line stands inside the release's own section, the tag holds the section, so a tree
  without it still fails.
- A byte that is not UTF-8, in the part of the copy that is read, is a line not read as well.\
  It changes nothing about where a section starts, so the copy is read past it: the tag holds the
  release's section where the part that is read has it.\
  The first line not read is named, a byte or another.\
  `CHANGELOG.md` in the tree that is not UTF-8 is refused, naming the line, by every command that
  reads what it holds; `ancestry` reads it only to
  [tell a release on its way](#a-release-yet-to-land-exits-with-4), and fails that release instead.

### What `main.py` reads in `CHANGELOG.md`

`main.py` reads the file itself, without a Markdown library, and only the part of Markdown a
changelog needs.\
A line outside that part is refused, with its number and what to write instead.\
It is not guessed at: a line read differently from GitHub would count a heading or an entry where
GitHub shows none, or miss one it shows.\
In the copy at a release tag, which cannot be edited, the refusal does not say what to write
instead.\
The limits are `main.py`'s, not Keep a Changelog's, which says nothing of code or quotes.

Read:

- blank lines, and paragraphs, such as an introduction above the first section;
- headings of one to six `#`, with up to three spaces before them: `## [<label>]` opens a section,
  and `### <name>` a group within it;
- list items opened by `-`, `*`, `+`, or a number followed by `.` or `)`, nested in each other, with
  their lines continued, indented or not;
- fenced code blocks, between ` ``` ` or `~~~` lines, at the top or inside a list item: a heading or
  an entry inside one is code, not a section or an entry;
- HTML comments: one that starts a line or an item hides all up to its `-->`, lines below included;
  one that starts later in a line hides what it holds up to its `-->`, and is text where it does not
  close there, unless only closed comments stand before it in an item.\
  On a comment's own line, what follows `-->` is raw HTML: its Markdown is not read, a comment left
  open in it hides the rest of the line, and a character reference stands for its character.
- link definitions, `[label]: url` on a line of their own, outside list items, followed by a blank
  line or another definition.

Refused, with what to write instead:

| Refused                                                         | Write instead                      |
|-----------------------------------------------------------------|------------------------------------|
| an indented code block: four spaces more than its place         | a fenced code block                |
| five spaces or more after a list marker, an indented code block | one space after the marker         |
| a tab in a line's indentation                                   | spaces                             |
| a tab after a list marker                                       | a space                            |
| a block quote, `>`, starting a line or an item                  | a paragraph or a list item         |
| a line of only `=` or only `-`, which can underline a heading   | a heading of `#`s                  |
| a line of three or more `-`, `*` or `_`, a thematic break       | a heading, or nothing              |
| the delimiter row of a table, with `\|` or without, as `:-`     | a list                             |
| a footnote definition, `[^label]:`, starting a line or an item  | the note in the entry itself       |
| a heading inside a list item                                    | a heading above the list           |
| an empty list item                                              | a list item with text              |
| HTML starting a line or an item, other than a comment           | Markdown, or text before the HTML  |
| a link definition inside a list item                            | it outside the lists, on one line  |
| a link definition that goes on in the next line                 | the definition on one line         |
| a line right after a link definition                            | a blank line between them          |
| a fenced code block or a comment still open at the end          | its closing line                   |
| a line of a fence or a comment in a list item, left of its text | the indentation of the item's text |

A released section cannot be edited while its tag's copy is compared.\
A refused line in one is rewritten in the tree, and `--skip-unreadable-releases` then lists the
release as not compared: its tag still holds the line.

### What `[Unreleased]` asks of the version

The entries under `[Unreleased]` say how far the release has to move past the last final release.\
So do the entries of every pre-release above the last final release whose section the changelog
holds.\
The rule is [SemVer's rules 6 to 8](https://semver.org/#semantic-versioning-specification-semver),
applied to Keep a Changelog's kinds of change:

| Under `[Unreleased]`                                   | The release is at least              |
|--------------------------------------------------------|--------------------------------------|
| `### Added`, `### Changed`, `### Deprecated`           | a minor                              |
| `### Removed`, or an entry marked `**BREAKING**`       | a major, and a minor while below 1.0 |
| `### Fixed`, `### Security`, a group of any other name | a patch                              |

- `version` refuses a candidate that moves less, and names the group that asks for more.
- Under `tags` with no version given, the default is the version after the highest release.\
  Where `[Unreleased]` asks for more, it is the smallest version that satisfies it.
- An entry starting with `**BREAKING**` marks a change that breaks something a caller relied on,
  under whichever group it stands.\
  This is a convention of this repository: Keep a Changelog has no such marker.\
  A group such as release-please's `### ⚠ BREAKING CHANGES` counts as a group of any other name.
- Below 1.0, a breaking change moves the minor, as Cargo and npm read `^0.y`.\
  SemVer's rule 4 promises nothing about `0.y.z`, and 1.0 is a step taken on purpose.
- A group with a name Keep a Changelog does not use, such as `### Documentation`, is named in a
  note on stderr.\
  A feature filed under a misspelled `### Add` can then still be seen.
- An empty group asks for nothing, and HTML comments and link definitions are ignored.\
  An entry or a line that says nothing counts as none, comments aside, by the measure that
  [refuses](#the-changelog) an `[Unreleased]` that says nothing.\
  That measure is no renderer: it can take for nothing raw HTML a browser shows after a comment's
  `-->`, such as `<!-- c --> <!--> The v1 API is gone.`, and so ask for a smaller step than that
  text calls for.\
  `close-changelog` refuses such a line under `[Unreleased]` in a group that holds nothing else, so
  the release stops there rather than go out with the smaller step.\
  A pre-release section closed by release-ci 0.2 or earlier was not checked for it, and can still
  ask for the smaller step.
- An entry under no group at all is refused, because nothing says what kind of change it is.
- A repository with no `CHANGELOG.md`, or no `[Unreleased]` section in it, is asked for no more
  than a patch, and told so on stderr.

### Relative links in the HTML

`notes --html` with `--repository-url` and `--ref` makes every relative link absolute.\
The HTML is shown away from the repository, as a JetBrains plugin's change notes, where a relative
link has nothing to lead to.\
Each one is read as `CHANGELOG.md`'s own, and leads where GitHub leads it on a published release's
page:

| In the section       | Leads to                                     |
|----------------------|----------------------------------------------|
| `docs/guide.md`      | `<repository>/blob/<ref>/docs/guide.md`      |
| `/docs/guide.md`     | the same: `/` is the repository's root       |
| `![s](docs/s.png)`   | `<repository>/raw/<ref>/docs/s.png`          |
| `./`                 | `<repository>/tree/<ref>`                    |
| `#usage`             | `<repository>/blob/<ref>/CHANGELOG.md#usage` |
| `https://e.invalid/` | itself, as does any link with a scheme       |

- `#usage` alone is where the HTML differs from the release page.\
  There it stays an anchor of the page, which holds no such heading.
- A link that climbs above the repository's root, such as `../x.md`, leads nowhere, on the release
  page too, and is refused.

## Where the version comes from

Every call that reads the source names it, with `--version-source` or the action's
`version-source` input.\
There is no default, because a wrong guess would pass silently.

Four sources exist:

| `--version-source`  | Where the candidate version comes from                                                         | After a release           |
|---------------------|------------------------------------------------------------------------------------------------|---------------------------|
| `gradle.properties` | the `version` line of the file, or `--version`; its snapshot suffix is dropped                 | the next one written back |
| `maven`             | what Maven evaluates in the root `pom.xml`, or `--version`; its snapshot suffix is dropped     | the next one written back |
| `command`           | what the read command prints, or `--version`; its snapshot suffix is dropped                   | the next one written back |
| `tags`              | `--version`, or the version after the highest release, moved further where `[Unreleased]` asks | nothing written           |

The candidate version is the version `main.py version` checks against the rules.

### `gradle.properties:<key>` reads the version under another key

`--version-source gradle.properties:appVersion` reads and writes the version in the `appVersion`
line instead of `version`.\
Everything else is as under `gradle.properties`.\
Gradle sets every project's `project.version` from a `version` line.
So a build that keeps its version under a key of its own cannot move it there without changing
what the build makes.\
The key is letters, digits, `_`, `.` and `-`.

### `maven` has Maven read and write the version

`--version-source maven` runs Maven itself, so what a release rewrites is what `versions:set`
rewrites, such as the root's `<version>` and the `<parent><version>` of each module of the
reactor.\
Nothing here reads the XML.

- The version is read from the root `pom.xml` alone, as
  `maven-help-plugin:3.5.2:evaluate -Dexpression=project.version` evaluates it.
- It is written with `versions-maven-plugin:2.16.2:set`.\
  The plugins are pinned, so a release does not change with what Maven Central holds that day.
- `./mvnw` runs where the project has one, and `mvn` otherwise.\
  The job provides Maven and a JDK, in every job that reads or writes the version.
- `maven:<property>` writes the version into that property instead, in each `pom.xml` of the
  reactor that defines it, with `versions:set-property`, for Maven's CI-friendly
  `<version>${revision}</version>`.\
  Every `<version>` stays as it is.
- Under plain `maven`, a write that takes a `<version>${…}` line out of a `pom.xml` is refused:
  `versions:set` puts the version itself where `${revision}` stood, and the version would still
  read back right.\
  Nothing is pushed, and the `pom.xml` files are left as `versions:set` wrote them.
- A `<version>` that joins the property with another, such as `${revision}${changelist}`, reads
  back other than the version written, and `set-version` fails.
- A property that `.mvn/maven.config` sets, as in `-Drevision=0.2.0-SNAPSHOT`, is read, as Maven
  reads it, but not written: `versions:set-property` writes `pom.xml` alone, so the version reads
  back unchanged, and `set-version` fails.\
  Such a project writes the version with `command` instead.

### `command` reads and writes the version with the project's own commands

`--version-source command` is for a build no other source reads.\
`--version-read-command` prints the version, and `--version-write-command` writes one:

```yaml
version-source: command
version-read-command: cat VERSION
version-write-command: printf '%s\n' "$VERSION" > VERSION
```

- Both run with `bash -c` in the root of the repository.
- The read command prints the version alone, on one line.\
  An exit other than 0, or anything else on stdout, is refused.\
  What it prints on stderr is passed through.
- The write command finds the version in `VERSION` in its environment, never in its own text:
  a version holding a quote would be more of the command.\
  The read command is not given `VERSION`.
- `set-version` reads the version back with the read command, and fails where it differs.
- `version` reads the read command alone, and `set-version` both.\
  Under any other source, either one is refused.

The actions take them as `version-read-command`, on `check-release` and `release-flow/prepare`, and
`version-write-command`, on `prepare`.\
`prepare` writes both the version released and the next one, so nothing after it runs the commands.\
They are recorded nowhere, and `intellij/build` refuses a release made under `command`.

### The nearest release

The snapshot suffix and the tag prefix are read from what the nearest release recorded.\
`release-flow/prepare` records them in the release commit, as `Release-CI-*` git trailers.

- The nearest release is, of the release tags HEAD reaches whose commit records how `prepare` made
  it, the one fewest commits away, the higher version where two are as near.
- Such a tag is named for the prefix and the version its commit records.\
  Another tag on that commit is passed over, such as a draft's tag renamed before it was published,
  and so is a tag whose commit records nothing, as older releases left them.
- Given a prefix, only the tags named for it and a version are read: `v` reads `v1.2.0`, and passes
  over `vscode-0.4.0`.
- There is none before the first release a recording `prepare` made, on a new project and on one
  that has just moved to it.

### The snapshot suffix

Between releases, a source that declares a version names the next one with a suffix the project
chooses: `-SNAPSHOT` in `0.2.1-SNAPSHOT`, another such as `-dev`, or none.\
It is a hyphen and one identifier of letters and digits, so the version is still a semantic
version.\
The identifier holds a letter: a number could match the number of a pre-release, such as `1` in
`1.0.0-rc.1`.

- `release-flow/prepare` is given it in its `snapshot-suffix` input, `^none` for none.\
  It records it in the release commit, and appends it to the next version it starts.
- `version` takes the suffix off the end of the declared version, or of `--version`, before it
  checks it.\
  The suffix is the one [the nearest release](#the-nearest-release) under the tag prefix recorded.\
  That release's `prepare` appended the suffix to the next version, and a version raised by hand
  since keeps it.
- Where there is no such release, or it recorded no suffix, `version` takes `--snapshot-suffix`
  instead:
  - before the first release of a project, or the first since it moved to a `prepare` that
    records;
  - after that first release is published, until merge-back carries it here;
  - where that release was made under `tags`, which starts no next version.
- `--snapshot-suffix` is required under a source that declares a version, `^none` included.\
  `check-release` and `release-flow/prepare` pass their `snapshot-suffix` input there.
- A version given by hand may leave the suffix out.
- A version that still carries the suffix after that is refused:
  `1.0.0-SNAPSHOT-SNAPSHOT` or `1.0.0-SNAPSHOT+b` under `-SNAPSHOT`.
- `next` appends the `--snapshot-suffix` it is given, and nothing without one.
- `tags` keeps no version between releases, so it has no suffix: `version` refuses one given.
- A tag carrying a suffix counts as a release: nothing says which suffix it was tagged under.\
  Release flow makes no such tag.\
  One made by hand is a pre-release of its version, below the version itself, so that version can
  still be released.\
  `changelog` names it as not compared where its copy of CHANGELOG.md has no section for it.

### The first release under `tags` needs a version

Under `tags`, with nothing released yet, there is no release to count from.\
Give the first version with `--version`, or with the `version` input of `check-release` or
`release-flow/prepare`.

### `set-version` under `tags` exits with 3

Under `tags` there is no file to write a version to.\
`set-version` then exits with status 3 instead of reporting a success.\
Its other refusals exit with 1, or 2 for a command line it cannot parse.\
So a caller can tell "nothing to write to" from a failed write.

### The tag prefix

`--tag-prefix` says what release tags carry in front of the version: `v` for `v0.1.0`.

- Left out, it is the prefix [the nearest release](#the-nearest-release) recorded, in its
  `Release-CI-Tag-Prefix` trailer.\
  No source declares it: `gradle.properties` is read for the version alone.
- Where there is no such release, it has to be given, under every source.
- Given, it is the prefix.\
  Where one history carries releases under more than one prefix, it chooses whose record is read.
- `--tag-prefix ''` says the tags have no prefix, and so does `--tag-prefix '^none'`.
- `check-release` and `release-flow/prepare` take it as their `tag-prefix` input, with `^none` for
  no prefix, as
  [the action's `tag-prefix`](../check-release/README.md#tag-prefix-is-needed-until-a-release-records-it)
  says.

#### A changed prefix is not caught

A prefix no release carries yet is taken as given.\
So a release under a new prefix is checked as a first release, until it records the new one.\
A prefix mistyped is taken the same way, and
[No default tag prefix, on purpose](#no-default-tag-prefix-on-purpose) says what that costs.

#### No default tag prefix, on purpose

A wrong default would pass silently.\
A repository that tags bare versions, read as if it tagged `v*`, has no releases at all.\
Every check then passes, having compared nothing.\
The one exception is `version` under `tags` with no version given, which fails with no release to
count from.\
A caller that wants a default writes it in its own workflow, where its readers can see it.

### Adding a source

Each source is an adapter in [`lib/rules/sources.py`](rules/sources.py).\
The rules know none of them by name.

Adding `package.json`, `pyproject.toml` or `Cargo.toml` means one more adapter there, filed under
the name `--version-source` gives it.\
`main.py` reads and writes the file of an adapter over a file, and runs the commands of one over
commands.

The rules need no change: the [snapshot suffix](#the-snapshot-suffix) is the project's choice, not
the source's.

The top of [`rules/sources.py`](rules/sources.py) lists what an adapter has to answer.\
[`lib/tests/test_sources.py`](tests/test_sources.py) holds every adapter to that list.\
It also needs a sample file for each adapter over a file.
