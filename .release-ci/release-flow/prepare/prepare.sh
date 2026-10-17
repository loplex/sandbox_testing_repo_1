#!/usr/bin/env bash
# Cut the release commit onto a branch of its own, named for the version:
# - [Unreleased] is closed into a section for the version being released;
# - that version is written back where the source declares one;
# - all of it is one commit, which records how the release was made for the steps after it;
# - where the source declares a version, a second commit on top of it starts the next one.
# This is everything a release does to the repository, so the steps after it only push and merge what it made.
# Nothing is pushed: the build runs from the release commit next, which is left checked out.
# A failed build should leave neither a branch nor a draft behind, so the push waits for the build and goes with the
# draft.
#
# Inputs come from the environment, because that is how a composite action hands them over.
# The script lives outside action.yml so that a test can run it against a repository.
#
# Reads:  PYTHON VERSION_SOURCE VERSION_READ_COMMAND VERSION_WRITE_COMMAND TAG_PREFIX VERSION SNAPSHOT_SUFFIX
#         REPOSITORY_URL SKIP_UNREADABLE_RELEASES HTML_NOTES
#         GITHUB_OUTPUT, and RUNNER_TEMP with HTML_NOTES true
# Writes: version, tag, branch  (to GITHUB_OUTPUT)
set -uo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../lib" && pwd)/main.py"

check_release=("$PYTHON" "$script")
# Without a prefix given, lib/main.py takes the one the nearest release recorded.
prefix_arguments=()
if [[ -n $TAG_PREFIX ]]; then
  prefix_arguments=(--tag-prefix "$TAG_PREFIX")
fi

# `true` passes over a release whose tag holds a CHANGELOG.md line lib/main.py does not read: a tag cannot be edited.
# Empty is `false`, because a dispatch input arrives empty on every other event.
skip=${SKIP_UNREADABLE_RELEASES:-false}
case $skip in
  true) changelog_arguments=(--skip-unreadable-releases) ;;
  false) changelog_arguments=() ;;
  *)
    echo "::error::skip-unreadable-releases is '$SKIP_UNREADABLE_RELEASES', which is neither true nor false"
    exit 1 ;;
esac

# `true` has changelog refuse raw HTML under [Unreleased] that notes --html would leave out of the release notes,
# as check-release does: installed and passed on the same way.
case ${HTML_NOTES:-false} in
  true)
    changelog_arguments+=(--html-notes)
    packages="$RUNNER_TEMP/release-ci-html-notes"
    "$PYTHON" -m pip install --quiet --disable-pip-version-check --require-hashes --target "$packages" \
      -r "$(dirname "$script")/requirements.txt" || exit 1
    export PYTHONPATH="$packages${PYTHONPATH:+:$PYTHONPATH}" ;;
  false) ;;
  *)
    echo "::error::html-notes is '$HTML_NOTES', which is neither true nor false"
    exit 1 ;;
esac

# The checks of the release rules all run before anything is written.
# A release that fails them was then never cut, and there is nothing to undo.
failed=0
"${check_release[@]}" changelog "${prefix_arguments[@]}" "${changelog_arguments[@]}" || failed=1
"${check_release[@]}" ancestry "${prefix_arguments[@]}" || failed=1
# Passed even where empty: lib/main.py refuses it empty under a source that declares a version, and takes the suffix
# the nearest recorded release names, using this only where that release names none or no release records anything.
# Joined by `=`, because a suffix starts with a hyphen and would be read as an option of its own.
version_arguments=("--snapshot-suffix=${SNAPSHOT_SUFFIX:-}")
if [[ -n $VERSION ]]; then
  version_arguments+=(--version "$VERSION")
fi
# Passed only where given: lib/main.py refuses commands under a source that reads none.
read_arguments=()
if [[ -n ${VERSION_READ_COMMAND:-} ]]; then
  read_arguments=(--version-read-command "$VERSION_READ_COMMAND")
fi
write_arguments=("${read_arguments[@]}")
if [[ -n ${VERSION_WRITE_COMMAND:-} ]]; then
  write_arguments+=(--version-write-command "$VERSION_WRITE_COMMAND")
fi
version=$("${check_release[@]}" version --version-source "$VERSION_SOURCE" "${read_arguments[@]}" \
  "${prefix_arguments[@]}" "${version_arguments[@]}"); rc=$?
if (( rc != 0 || failed != 0 )); then
  echo "::error::the release rules said no, so nothing was cut"
  exit 1
fi
prefix=$("${check_release[@]}" prefix "${prefix_arguments[@]}"); rc=$?
if (( rc != 0 )); then
  exit 1
fi
# Not a setting: once the release is tagged, check-release names release/<version> first among the branches
# that hold its tag - the remote ones, or the local ones where no remote one does.
branch="release/${version}"

# What patchChangelog does in a Gradle plugin, done by the tool that later checks the result against the tag.
# The entries under [Unreleased] become the section for this version, dated today.
# Where a repository URL is given, the link definitions are written again to include it.
#
# This is the first write, and the last check of the release rules.
# An [Unreleased] with nothing to release is refused before the file is rewritten:
# nothing under it says something, and for a final release nothing in the pre-releases since the final release before
# it, in SemVer order, says something either.
# Such a release then leaves no branch and no output behind.
repository_url_arguments=()
if [[ -n $REPOSITORY_URL ]]; then
  repository_url_arguments=(--repository-url "$REPOSITORY_URL")
fi
"${check_release[@]}" close-changelog "$version" "${prefix_arguments[@]}" "${repository_url_arguments[@]}" || exit 1

git config user.name 'github-actions[bot]'
git config user.email '41898282+github-actions[bot]@users.noreply.github.com'

# Created here, not switched to, so that a branch left by an earlier attempt is replaced, not continued.
# A second attempt at the same version starts again from the commit checked out.
git switch -C "$branch" || exit 1

# Written by the tool that also reads that line, not by a pattern here.
# A source that declares no version, given no command, has nothing to write and exits with 3.
# The release is still the version it was asked to be.
# Any other failure stops here, before a commit names a release the build would not make.
# A command the source does not take, or one it needs and was not given, is refused here at the latest,
# once the changelog is closed and the branch cut.
# A run refused here has written to its own workspace alone, and pushed nothing.
"${check_release[@]}" set-version "$version" --version-source "$VERSION_SOURCE" "${write_arguments[@]}" >/dev/null
rc=$?
declares=true
if (( rc == 3 )); then
  declares=false
  echo "${VERSION_SOURCE} declares no version, so none was written; ${prefix}${version} is what the tag will say"
elif (( rc != 0 )); then
  echo "::error::${VERSION_SOURCE} could not be given ${version}, so no release commit was made"
  exit 1
fi

# The release commit records how the release was made, as git trailers.
# merge-back and intellij/build read them there, so a later workflow cannot be given other values than this one was.
# The comment above SETTINGS in lib/main.py says what each one means.
# Git writes them, and git reads them back, so where a trailer starts and ends is git's to decide.
recorded=(
  --trailer "Release-CI-Version: ${version}"
  --trailer "Release-CI-Version-Source: ${VERSION_SOURCE}"
  --trailer "Release-CI-Tag-Prefix: ${prefix:-^none}"
)
# The suffix the next version carries, where the source declares a version.
if [[ $declares == true ]]; then
  recorded+=(--trailer "Release-CI-Snapshot-Suffix: ${SNAPSHOT_SUFFIX}")
fi
if [[ $skip == true ]]; then
  recorded+=(--trailer "Release-CI-Skip-Unreadable-Releases: true")
fi
message=$(printf 'chore(release): %s\n' "$version" | git interpret-trailers "${recorded[@]}") || exit 1
git commit -qa -m "$message" || exit 1
release=$(git rev-parse HEAD) || exit 1

# The next version is started here, not once the release is out.
# The source is written only in this step, where it is given, so merge-back has nothing to write.
# The suffix is the one recorded above, which merge-back counts the next version with for its title too.
if [[ $declares == true ]]; then
  next=$("${check_release[@]}" next "$version" "${prefix_arguments[@]}" "--snapshot-suffix=${SNAPSHOT_SUFFIX}") \
    || exit 1
  if ! "${check_release[@]}" set-version "$next" --version-source "$VERSION_SOURCE" "${write_arguments[@]}" \
      >/dev/null; then
    echo "::error::${VERSION_SOURCE} could not be given ${next}, so the next version was not started"
    exit 1
  fi
  # The next version always differs from the one released, so a write that changed no file git tracks went
  # elsewhere, such as to an ignored file: the version reads back from the workspace, but no commit would carry it.
  # The version released may legitimately change nothing, where the file declares it already.
  # Compared with the release commit, as `commit -a` takes it: a change the command added to the index counts.
  if git diff --quiet HEAD; then
    echo "::error::${VERSION_SOURCE} wrote ${next} to no file git tracks, so the next version was not started"
    exit 1
  fi
  git commit -qam "chore: start ${next}" || exit 1
fi
# The workspace goes back to the release commit, detached, because the build runs from it next.
# Detached under a source that starts no next version too, so the build finds the same either way.
# The branch keeps the commit that starts the next version, where one was made.
# release-flow/draft pushes it as it is, and drafts the release at the commit checked out.
git switch -q --detach "$release" || exit 1

# Written only once the commits are made.
# A failed step then leaves nothing that a later step could read as a release that was cut.
{
  echo "version=${version}"
  echo "tag=${prefix}${version}"
  echo "branch=${branch}"
} >> "$GITHUB_OUTPUT"

echo "cut ${version} onto ${branch}, to be built from here and tagged ${prefix}${version} once published"
if [[ $declares == true ]]; then
  echo "started ${next} on ${branch}, above the release commit"
fi
