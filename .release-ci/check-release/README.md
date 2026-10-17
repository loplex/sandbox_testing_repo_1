# check-release

`check-release` is a composite action that checks a repository before and after a release, with
[`lib/main.py`](../lib/README.md).\
It runs:

- `version`, `changelog` and `ancestry`, whichever its `checks` input names;
- `channel`, after a `version` check that passed.

What each check holds a repository to, and where the version comes from, is in
[`lib/README.md`](../lib/README.md).

## Using check-release from another repository

[`check-release/action.yml`](action.yml) is a composite action.\
A project uses it instead of keeping its own copy of the rules:

```yaml
# .github/workflows/ci.yml
on:
  push:
    branches: [main]
  pull_request:
  # release-flow/merge-back starts this workflow by hand, as its check-workflow, once a release has landed.
  workflow_dispatch:

jobs:
  release-rules:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v7
        with:
          fetch-depth: 0
      - uses: loplex/release-ci/check-release@<tag>
        id: release
        with:
          version-source: gradle.properties
          tag-prefix: v
          checks: version changelog ancestry
          snapshot-suffix: -SNAPSHOT
```

The [`action.yml`](action.yml) describes every input and output.\
The sections below explain the choices in this example.

### `branches` keeps the workflow off tags

Publishing a release creates its tag.\
A workflow on `push` with no filter runs for that tag too, with nothing to check.

If it does run on the release commit, `version` can still pass there:

- The commit a release tags still declares the version it released.
- Where the source declares a version and no `version` input is given, `version` sees that the tag
  points at this commit, notes it, and passes.
- A version given in the `version` input is still checked.

### `checks` names `version`, which its default leaves out

The default of `checks` is `changelog ancestry`.\
The example adds `version`.

- Every check named runs, even after one of them fails.
- The step fails at the end if any check failed, and names each one that did.
- A check that finds nothing but a published release
  [yet to land](../lib/README.md#a-release-yet-to-land-exits-with-4) here warns and does not fail.\
  So does one that finds nothing but a release
  [landed after the commit checked](../lib/README.md#a-release-landed-after-this-commit-exits-with-4-too)
  on the branch the `default-branch` input names, the repository's default branch unless given.\
  The action fetches that branch again before the checks, since on a push to it
  `actions/checkout` leaves its remote-tracking branch at the commit checked.
- The [outputs](#outputs) are written whenever `version` passed, even if another check failed.\
  A `version` that only warns has not passed, and writes neither.

### `<tag>` is a release tag

`<tag>` is one of this repository's release tags, as in every example here.\
[Pinning](../README.md#pinning) says what other refs give you.\
What the action needs on the runner is under
[What an action needs of the runner](../README.md#what-an-action-needs-of-the-runner).

### `tag-prefix` is needed until a release records it

`release-flow/prepare` records the prefix in the release commit.\
Once such a release is reachable from the commit checked, the checks read the prefix there, and
`tag-prefix` may be left out.\
Before it, as on a project's first release, the checks refuse to run without it.\
Given, it is also what chooses among releases under more than one prefix in one history.\
[The tag prefix](../lib/README.md#the-tag-prefix) says how the prefix is found.

Where the tags have no prefix at all, give `tag-prefix: ^none`.

- An empty value cannot mean that: GitHub passes a missing input as an empty string too.
- `^none` can never be a real prefix, because git refuses `^` in a tag name.

[No default tag prefix, on purpose](../lib/README.md#no-default-tag-prefix-on-purpose)
explains why there is no default.

### `fetch-depth: 0` is required

A shallow checkout cuts the history short.\
`version`, `changelog` and `ancestry` refuse it, naming `fetch-depth: 0`.

### `snapshot-suffix` is the one `release-flow/prepare` is given

`version` takes the suffix off the declared version before it checks it: `0.2.0-SNAPSHOT` is
checked as `0.2.0`.\
The suffix is the one a release recorded where one applies, and this input otherwise, as
[The snapshot suffix](../lib/README.md#the-snapshot-suffix) says.\
Under a source that declares a version, `version` fails without it.

### Outputs

When `version` is among the checks and passes, the action sets two outputs:

- `steps.<id>.outputs.version`: the version that may be released, without the tag prefix and
  without the snapshot suffix.\
  For `0.2.0-SNAPSHOT` in `gradle.properties`, it is `0.2.0`.
- `steps.<id>.outputs.channel`: the
  [channel](../lib/README.md#the-channel-a-version-goes-to) of that version.\
  `default` for a final release, otherwise the first identifier of its pre-release suffix.

Both are empty where `version` only warns, of a release yet to land or landed after the commit
checked, though it does not fail the step.

### The `version` input

The `version` input gives the `version` check a version to check, such as one from a dispatch form.\
It replaces:

- under `gradle.properties`, the version the file declares;
- under `maven`, the version Maven evaluates in the root `pom.xml`;
- under `command`, the version the read command prints;
- under `tags`, the default: the version after the highest release, or a later one where
  `[Unreleased]` asks for more.

How to spell it under each source is under
[Where the version comes from](../lib/README.md#where-the-version-comes-from).
