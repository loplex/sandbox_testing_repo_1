"""Where the version of the next release comes from: one adapter per kind of repository.

The rules know no source by name; main.py asks the adapter for them.

An adapter is a plain object, and opens no file and runs no command itself.
One over a file names it, and main.py reads and writes it: the adapter says what that text declares and what it
becomes.
One over commands holds the commands, and main.py runs them.
So each adapter can be tested without a repository,
and finding the file, refusing a missing one, and running a command, is written once instead of once per format.

There are two kinds of source:
- One that declares a version keeps the next version between releases,
  with the snapshot suffix the project chose, such as `-SNAPSHOT`, or none.
  A release reads the version there and writes the following one back.
- One that declares nothing is given the version when a release is asked for.
  Without one, it takes the version after the highest release, or a later one where `[Unreleased]` asks for more.
  It records nothing afterwards, because it has no file to record it in.

What every adapter answers:
- `name`: the `--version-source` value that selects it.
- `under()`: the same source reading its version under another key, or a refusal where it has no keys.
  `--version-source gradle.properties:appVersion` selects it so (see source()).
- `declares_a_version`.

An adapter that declares a version also answers `by_command`: whether commands read and write it, not main.py.
One over a file, `by_command` false, answers:
- `file`: the file it declares the version in.
- `holds`: what the file is read for, named in the refusal when the file is missing.
- `encoding`: how the file's bytes are read and written back.
- `version()` over the file's text, and `with_version()`, the text with a new version in it.

One over commands, `by_command` true, answers:
- `read_command` and `write_command`: the shell commands main.py runs, None where none was given (see Command).
- `given(read, write)`: the same source with the commands the project gives, or a refusal where it takes none.

No source declares the tag prefix: main.py reads it from what the nearest release recorded, or is given it.

Adding a source, such as package.json, pyproject.toml or Cargo.toml:
- It is another adapter here, filed in SOURCES under its `--version-source` name:
  over a file, or over the commands of the tool that owns the format.
- Nothing in main.py or the other modules of rules/ changes.
  The suffix of a version being worked on is no adapter's: release-flow/prepare is given it (see versions.py).
- test_sources.py holds it to what every adapter has to answer.
"""

import re

# gradle.properties is a Java properties file, and Gradle reads it with java.util.Properties.
# It is read here the way Properties.load() documents, so the version is the one the build sees:
# - A natural line ends at `\n`, `\r` or `\r\n`.
# - A line ending in an odd number of backslashes goes on in the next, whose leading white space is dropped.
# - `#` or `!` opens a comment only at the start of a logical line.
# - The key runs to the first `=`, `:` or white space that is not escaped.
# - An escape is `\t`, `\n`, `\r`, `\f`, `\uXXXX`, or a backslash before any other character,
#   which then stands for itself.
NATURAL_LINE = re.compile(r"[^\r\n]*(?:\r\n|\r|\n)|[^\r\n]+\Z")
KEY_AND_VALUE = re.compile(r"((?:\\.|[^\\=: \t\f])*)[ \t\f]*[=:]?[ \t\f]*(.*)", re.DOTALL)
ESCAPE = re.compile(r"\\(u[0-9A-Fa-f]{4}|u|.)", re.DOTALL)


def logical_lines(text: str) -> list[tuple[int, int, str]]:
    """Every logical line of a properties file that declares something, as (start, end, line).

    `start` is where its first natural line starts, `end` where its last one ends,
    and `line` is the logical line with its continuations joined."""
    found, start, joined = [], 0, None
    for match in NATURAL_LINE.finditer(text):
        natural = match.group(0).rstrip("\r\n")
        body = natural.lstrip(" \t\f")
        if joined is None:
            if not body or body[0] in "#!":
                continue
            start, joined = match.start(), ""
        joined += body
        if (len(body) - len(body.rstrip("\\"))) % 2:
            joined = joined[:-1]
            continue
        found.append((start, match.start() + len(natural), joined))
        joined = None
    if joined is not None:
        found.append((start, len(text), joined))
    return found


def unescaped(text: str) -> str:
    """A key or a value with its escapes read.

    A `\\u` not followed by four hex digits is refused, as Properties refuses it.
    Two that make a UTF-16 surrogate pair are one character, as they are in the Java string Properties reads them
    into."""
    def one(escape):
        code = escape.group(1)
        if code == "u":
            raise SystemExit(f"gradle.properties has a malformed \\uxxxx escape in '{text}', which Gradle refuses "
                             "as well")
        escapes = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}
        return chr(int(code[1:], 16)) if len(code) == 5 else escapes.get(code, code)
    return ESCAPE.sub(one, text).encode("utf-16-le", "surrogatepass").decode("utf-16-le", "surrogatepass")


def stored(value: str) -> str:
    """A value written the way Properties.store(OutputStream) writes one.

    The file then reads it back as it was given, and no character of text fails to encode:
    - a backslash is doubled;
    - a tab, a line break and a form feed become their escapes;
    - a leading space, and `=`, `:`, `#`, `!`, get a backslash in front;
    - every other character below a space or above `~` becomes `\\uXXXX`,
      and a character outside the first 65536 becomes the two units of its UTF-16 pair.

    A lone surrogate is no character of text.
    On the command line it is what Python makes of a byte its filesystem encoding cannot decode,
    and main() refuses it there.
    One handed here any other way is refused by the UTF-16 encoding below, before the file is opened for writing.
    Properties.store(OutputStream) would write it as `\\uXXXX` instead."""
    escapes = {"\\": "\\\\", "\t": "\\t", "\n": "\\n", "\r": "\\r", "\f": "\\f"}
    written = []
    for at, character in enumerate(value):
        if character in escapes:
            written.append(escapes[character])
        elif character in "=:#!" or (character == " " and at == 0):
            written.append("\\" + character)
        elif not " " <= character <= "~":
            units = character.encode("utf-16-be")
            written.extend(f"\\u{int.from_bytes(units[i:i + 2], 'big'):04X}" for i in range(0, len(units), 2))
        else:
            written.append(character)
    return "".join(written)


class Tags:
    """A repository whose version lives only in its tags.

    It declares no version:
    - the version is given, or it is the one after the highest release,
      or a later one where `[Unreleased]` asks for more;
    - no version is being worked on, so there is no snapshot suffix."""

    name = "tags"
    declares_a_version = False

    def under(self, key: str):
        raise ValueError(f"{self.name} declares no version, so there is no key '{key}' to read one under")


class GradleProperties:
    """gradle.properties, the Java properties file a project declares its version in.

    The version is read under the key `version` unless another is given.
    Gradle sets every project's `project.version` from a `version` line, so a project whose build reads its version
    from a key of its own, such as `appVersion`, cannot move it there without changing what the build makes.

    Between releases the version carries the snapshot suffix the release recorded, such as `-SNAPSHOT`, or none.
    """

    name = "gradle.properties"
    declares_a_version = True
    by_command = False
    file = "gradle.properties"
    holds = "the version"
    # Gradle hands the file to Properties.load() as bytes, which reads each byte as one Latin-1 character
    # instead of decoding UTF-8.
    # Read and written the same way here, no byte in the file fails to decode.
    # A comment, or any value but the version, goes back in the bytes it was read from, whatever its encoding.
    encoding = "iso-8859-1"

    # What a release puts back in front of the version it writes:
    # the indentation, the key and the separator, exactly as the first line of the declaration has them.
    # Gradle allows space around the separator and before the key, and projects write it every way.
    # Choosing one spelling here would show in the diff as a change nobody made,
    # and so would an indented declaration moved to the margin.
    # Where a continuation splits the key itself, the first line holds only part of it,
    # and the declaration is written as `<key> = ` after that line's indentation.
    DECLARATION_LEAD = re.compile(r"[ \t\f]*(?:\\[^\r\n]|[^\\=: \t\f\r\n])*[ \t\f]*[=:]?[ \t\f]*")

    # A key given for the version is one that reads the same escaped or not, so it is written back as given.
    # One that would need escapes, such as one holding a space or `=`, is refused instead of escaped.
    KEY = re.compile(r"[A-Za-z0-9_.-]+")

    def __init__(self, key: str = "version"):
        self.key = key

    def under(self, key: str) -> "GradleProperties":
        if not self.KEY.fullmatch(key):
            raise ValueError(f"'{key}' is no key gradle.properties can be given the version under: "
                             "letters, digits, `_`, `.` and `-` only")
        return GradleProperties(key)

    def properties(self, text: str) -> dict[str, str]:
        """Every key the file declares, with its value, read the way Gradle reads them (see NATURAL_LINE).

        `version = 0.2.0`, `version=0.2.0` and `version: 0.2.0` all declare it, and so does a continued line.
        Escapes are read.
        A key declared twice keeps its last value, as it does for Gradle."""
        found = {}
        for _, _, line in logical_lines(text):
            key, value = KEY_AND_VALUE.match(line).groups()
            found[unescaped(key)] = unescaped(value)
        return found

    def version(self, text: str) -> str:
        """The version the file names, snapshot suffix included.

        A release is asked to be this version with the suffix taken off."""
        declared = self.properties(text).get(self.key)
        if declared is None:
            raise SystemExit(f"gradle.properties names no {self.key}")
        return declared

    def with_version(self, text: str, version: str) -> str:
        """The file with the version's declaration rewritten and everything else left alone.

        The replacement is built by hand, so the version is data: `&` and `\\1` in it are characters,
        not instructions.
        The version is escaped as Properties.store(OutputStream) escapes a value (see stored()),
        so the file reads it back as it was given.

        A file that declares the version more than once is refused.
        The reader takes the last declaration, as Gradle does:
        - rewriting another one would leave the version the build sees unchanged;
        - rewriting the last one would leave a stale line above it for the next reader to trust.
        """
        declarations = [(start, end) for start, end, line in logical_lines(text)
                        if unescaped(KEY_AND_VALUE.match(line).group(1)) == self.key]
        if not declarations:
            raise SystemExit(f"gradle.properties names no {self.key} to rewrite")
        if len(declarations) > 1:
            raise SystemExit(f"gradle.properties declares {self.key} {len(declarations)} times, and a release "
                             f"rewrites one declaration: say it once")
        start, end = declarations[0]
        lead = self.DECLARATION_LEAD.match(text, start).group(0)
        if unescaped(KEY_AND_VALUE.match(lead.lstrip(" \t\f")).group(1)) != self.key:
            lead = lead[:len(lead) - len(lead.lstrip(" \t\f"))] + f"{self.key} = "
        elif not lead.endswith(("=", ":", " ", "\t", "\f")):
            lead += " = "
        return text[:start] + lead + stored(version) + text[end:]


class Command:
    """A version read and written by shell commands the project gives, for a build no other source reads.

    main.py runs each with `bash -c` in the root of the repository:
    - the read command prints the version on stdout, on one line, and nothing else there;
    - the write command writes the version `VERSION` holds in its environment, which the read command is not given.

    The version is never put into the text of a command: one holding a quote would be more of the command.
    """

    name = "command"
    declares_a_version = True
    by_command = True

    def __init__(self, read: str | None = None, write: str | None = None):
        self.read_command, self.write_command = read, write

    def under(self, key: str):
        raise ValueError(f"{self.name} is read by the commands given, so there is no key '{key}' to read it under")

    def given(self, read: str | None, write: str | None) -> "Command":
        return Command(read, write)


class Maven(Command):
    """A Maven project, whose pom.xml Maven itself reads and writes, through plugins pinned here.

    Nothing here reads the XML: what a release rewrites is what versions:set rewrites,
    such as the root's `<version>` and the `<parent><version>` of each module of the reactor.
    The version is read from the root alone, with -N, as Maven evaluates it.
    The commands run ./mvnw where the project has one, and mvn otherwise; the job provides Maven and a JDK.
    The plugins are pinned, so a release does not change with what Maven Central holds that day.

    A version given by a property, Maven's CI-friendly `${revision}`, is written under that property:
    `maven:revision` writes the property with versions:set-property, and leaves every `<version>` as it is.
    versions:set would replace `${revision}` with the version itself, and the version would still read back right.
    So under plain `maven` the write is refused where it took a `<version>${…}` line out of a pom.xml,
    as git's diff of them shows: an over-wide match, which refuses a one-line pom holding one beside the version.
    A property `.mvn/maven.config` sets is read, as Maven reads it, but versions:set-property writes pom.xml alone:
    the version then reads back unchanged, and the write is refused.
    """

    name = "maven"
    # A property is written into the text of the command, so it is one that needs no quoting there.
    KEY = re.compile(r"[A-Za-z0-9_.-]+")
    # A wrapper checked in without its executable bit is run with sh.
    MVN = ('if [ -x ./mvnw ]; then set -- ./mvnw; elif [ -f ./mvnw ]; then set -- sh ./mvnw; else set -- mvn; fi; '
           '"$@" -B -q')
    READ = (f"{MVN} -N org.apache.maven.plugins:maven-help-plugin:3.5.2:evaluate -Dexpression=project.version "
            "-DforceStdout")
    VERSIONS = "org.codehaus.mojo:versions-maven-plugin:2.16.2"
    WRITE = (f'{MVN} {VERSIONS}:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false || exit; '
             "if git diff --unified=0 -- '*pom.xml' | grep -q '^-.*<version>\\${'; then "
             "echo 'versions:set replaced a version a property gives, such as ${revision}, with the version itself: "
             "where pom.xml defines the property, name it, as in maven:revision' >&2; exit 1; fi")

    def __init__(self, key: str | None = None):
        self.key = key
        write = self.WRITE if key is None else (f'{self.MVN} {self.VERSIONS}:set-property -Dproperty={key} '
                                                '-DnewVersion="$VERSION" -DgenerateBackupPoms=false')
        super().__init__(self.READ, write)

    def under(self, key: str) -> "Maven":
        if not self.KEY.fullmatch(key):
            raise ValueError(f"'{key}' is no property maven can be given the version under: "
                             "letters, digits, `_`, `.` and `-` only")
        return Maven(key)

    def given(self, read: str | None, write: str | None):
        raise ValueError(f"{self.name} runs commands of its own: --version-read-command and --version-write-command "
                         f"are for {Command.name}")


SOURCES = {adapter.name: adapter for adapter in (GradleProperties(), Maven(), Command(), Tags())}


def source(spec: str):
    """The adapter `--version-source` names: a name from SOURCES, or a name, a colon and the key or property the
    version is kept under, as in `gradle.properties:appVersion` or `maven:revision`.

    The key goes with the name, so every action of a release is handed the same pair in one input.
    A name that is not a source, a key after one that has none, and an empty key are refused with ValueError."""
    name, colon, key = spec.partition(":")
    if name not in SOURCES:
        raise ValueError(f"'{spec}' names no version source: {', '.join(SOURCES)}, a name followed by ':<key>' "
                         "where the source takes one")
    if not colon:
        return SOURCES[name]
    if not key:
        raise ValueError(f"'{spec}' names no key after the colon")
    return SOURCES[name].under(key)
