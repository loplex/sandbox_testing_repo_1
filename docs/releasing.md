# CI and releasing Inspect Code with Filters

The workflows in [`.github/workflows`](../.github/workflows) check every change and cut every release.\
The release steps are the actions of [loplex/release-ci](https://github.com/loplex/release-ci/tree/v0.2.0), pinned to
its release `v0.2.0`; its README says what each of them does and why.

| Workflow              | Runs on                         | Does                                            |
|-----------------------|---------------------------------|-------------------------------------------------|
| `ci.yml`              | a push to `main`, a PR, by hand | tests, Plugin Verifier, UI tests, release rules |
| `release.yml`         | by hand, on `main` only         | release commit, signed archive, draft release   |
| `release-publish.yml` | a draft release published       | merge back onto `main`, Marketplace upload      |

The release rules in `ci.yml` ask three things of every change:

- whether the version in `gradle.properties` may be released next;
- whether every released section of [`CHANGELOG.md`](../CHANGELOG.md) still reads as its tag has it;
- whether every release tag is still on the history.

The UI tests run [`scripts/ui-tests.sh`](../scripts/ui-tests.sh) on a virtual display, as
[UI tests in a real IDE](development.md#ui-tests-in-a-real-ide) describes.

## What a change records

Every change worth a line in the release notes goes under `## [Unreleased]` in [`CHANGELOG.md`](../CHANGELOG.md).

- A release refuses an empty `[Unreleased]`.
- The release notes are the section the release closes it into.
- A released section is not edited again: `ci.yml` compares it against its tag.

## What the repository needs

**Secrets**, under Settings > Secrets and variables > Actions:

| Secret                 | What it is                                              |
|------------------------|---------------------------------------------------------|
| `CERTIFICATE_CHAIN`    | the certificate chain the plugin is signed with, PEM    |
| `PRIVATE_KEY`          | its private key, PEM                                    |
| `PRIVATE_KEY_PASSWORD` | the password of that key                                |
| `PUBLISH_TOKEN`        | a JetBrains Marketplace permanent token, for the upload |

[Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html) says how to make the key and the
certificate.\
`signPlugin` reads the first three from the environment, through the `signing` block of
[`build.gradle.kts`](../build.gradle.kts).

**Settings**, for when the release cannot be carried back onto `main` by a fast-forward and a pull request carries it
instead:

- *Allow GitHub Actions to create and approve pull requests*, under Settings > Actions > General.
- Merge commits allowed, under Settings > General > Pull Requests.\
  Such a pull request is merged with a merge commit: a squash or a rebase takes the release's tag off the history.

A rule protecting `release/*` has to let the workflow force-push there: each run of `release.yml` replaces the release
branch.

## Cutting a release

1. Run **release** from the Actions tab on `main`.\
   Its `version` input is left empty to release the version `gradle.properties` declares, without `-SNAPSHOT`.
2. Look over the draft release it leaves: its notes, and the signed archive attached.\
   Nothing is tagged yet; a draft thrown away leaves nothing behind but the `release/<version>` branch.
3. Publish the draft.\
   That creates the tag `v<version>` and starts `release-publish.yml`.
4. `release-publish.yml` carries the release back onto `main` and opens the next version, such as `0.1.1-SNAPSHOT`.\
   Where `main` cannot be fast-forwarded onto the release commit, a pull request carries the release instead.
5. It uploads the archive the release carries to JetBrains Marketplace.\
   Where the Marketplace does not end up serving it, the release notes start with a warning linking the run.

### The first version is uploaded by hand

JetBrains Marketplace takes no upload over its API for a plugin with no listing yet.

1. Upload the archive the release carries, `ij-inspection-filter-<version>-signed.zip`, on
   [Upload plugin](https://plugins.jetbrains.com/plugin/add).
2. The upload of `release-publish.yml` fails, and the release carries the warning, until JetBrains has approved that
   version.
3. Once it is approved, re-run the failed job of `release-publish.yml`; GitHub allows that for 30 days after the run
   started.\
   It finds the version served and takes the warning off.
