#!/usr/bin/env bash
# Run the checks the action was asked for, and output what `version` answered.
# It reads environment variables, not arguments, because that is how a composite action passes its inputs.
# It is a file of its own, not part of action.yml, so that a test can run it against a repository.
#
# Reads:  PYTHON VERSION_SOURCE VERSION_READ_COMMAND TAG_PREFIX CHECKS VERSION SNAPSHOT_SUFFIX SKIP_UNREADABLE_RELEASES
#         DEFAULT_BRANCH
#         HTML_NOTES GITHUB_OUTPUT, and RUNNER_TEMP with HTML_NOTES true
# Writes: version, channel  (to GITHUB_OUTPUT, where `version` ran and said yes)
set -uo pipefail

script="$(cd "$(dirname "${BASH_SOURCE[0]}")/../lib" && pwd)/main.py"

# The prefix is passed on only where the caller gave one, so that the prefix the nearest release recorded answers.
# An empty input means "not given", not "the empty prefix":
# GitHub passes a missing input as an empty string too, so the two cannot be told apart.
# Read as "not given", a mistake fails loudly; read as "no prefix", it would pass having found no release.
# `^none` says "no prefix", and lib/main.py reads it so.
check_arguments=()
if [[ -n $TAG_PREFIX ]]; then
  check_arguments+=(--tag-prefix "$TAG_PREFIX")
fi
# The branch releases land on, so that a release landed after the commit checked only warns.
# Empty where the event names no repository: such a release then fails.
if [[ -n ${DEFAULT_BRANCH:-} ]]; then
  check_arguments+=(--default-branch "$DEFAULT_BRANCH")
  # actions/checkout fetches every branch, and then the commit of the event into the remote-tracking branch of the ref
  # it checks out, where that branch is not at the commit. On a push to the default branch, origin/<default branch> is
  # then the commit checked, not the branch as it is now, and a release that landed after that commit is not on the
  # branch the checks look for it on.
  # So the branch is fetched again, unless the clone is shallow: the action needs fetch-depth: 0 anyway.
  if [[ $(git rev-parse --is-shallow-repository) == false ]] \
    && ! GIT_TERMINAL_PROMPT=0 git fetch --quiet --no-tags origin \
      "+refs/heads/${DEFAULT_BRANCH}:refs/remotes/origin/${DEFAULT_BRANCH}"; then
    echo "::notice::${DEFAULT_BRANCH} could not be fetched again: a release that landed on it after this commit" \
      "may fail"
  fi
fi

# Split at spaces and newlines, without glob expansion.
# `*` is then a name that is no check, not the files in the workspace.
read -rd '' -a check_names <<< "$CHECKS"

# No check at all is refused, like a name that is no check:
# a run that compared nothing and passed looks exactly like one that compared everything.
if (( ${#check_names[@]} == 0 )); then
  echo "::error::no check was asked for: name some of version, changelog and ancestry"
  exit 1
fi

for check in "${check_names[@]}"; do
  case "$check" in
    version|changelog|ancestry) ;;
    *)
      echo "::error::'$check' is not a check this action runs: version, changelog and ancestry are"
      exit 1 ;;
  esac
done

# Read by version alone, so it may be left out where checks leaves version out.
# Left out, it arrives empty, and is refused here by the input's name rather than by lib/main.py's usage message.
if [[ " ${check_names[*]} " == *" version "* && -z ${VERSION_SOURCE:-} ]]; then
  echo "::error::the version check needs version-source, which is empty: it says where the version comes from"
  exit 1
fi

# `true` passes over a release whose tag holds a CHANGELOG.md line lib/main.py does not read: a tag cannot be edited.
# Empty is `false`, because a dispatch input arrives empty on every other event.
case ${SKIP_UNREADABLE_RELEASES:-false} in
  true) changelog_arguments=(--skip-unreadable-releases) ;;
  false) changelog_arguments=() ;;
  *)
    echo "::error::skip-unreadable-releases is '$SKIP_UNREADABLE_RELEASES', which is neither true nor false"
    exit 1 ;;
esac

# `true` has changelog refuse raw HTML under [Unreleased] that notes --html would leave out of the release notes.
# Read by changelog alone, so it is refused where checks leaves changelog out, like a check that is not run.
# cmarkgfm, which renders it, is installed from PyPI for the step, with the hashes lib/requirements.txt pins,
# into a directory of its own under RUNNER_TEMP, as intellij/build does.
case ${HTML_NOTES:-false} in
  true)
    if [[ " ${check_names[*]} " != *" changelog "* ]]; then
      echo "::error::html-notes is read by the changelog check alone, which checks does not name"
      exit 1
    fi
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

# Passed even where empty: lib/main.py refuses it empty under a source that declares a version, and takes the suffix
# the nearest recorded release names, using this only where that release names none or no release records anything.
# Joined by `=`, because a suffix starts with a hyphen and would be read as an option of its own.
version_arguments=("--snapshot-suffix=${SNAPSHOT_SUFFIX:-}")
if [[ -n $VERSION ]]; then
  version_arguments+=(--version "$VERSION")
fi
# Passed only where given: lib/main.py refuses a command under a source that reads none.
if [[ -n ${VERSION_READ_COMMAND:-} ]]; then
  if [[ " ${check_names[*]} " != *" version "* ]]; then
    echo "::error::version-read-command is read by the version check alone, which checks does not name"
    exit 1
  fi
  version_arguments+=(--version-read-command "$VERSION_READ_COMMAND")
fi

failed=0
for check in "${check_names[@]}"; do
  if [[ $check == version ]]; then
    out=$("$PYTHON" "$script" version --version-source "$VERSION_SOURCE" "${check_arguments[@]}" \
      "${version_arguments[@]}" 2>&1)
    rc=$?
  elif [[ $check == changelog ]]; then
    out=$("$PYTHON" "$script" changelog "${check_arguments[@]}" "${changelog_arguments[@]}" 2>&1)
    rc=$?
  else
    out=$("$PYTHON" "$script" "$check" "${check_arguments[@]}" 2>&1)
    rc=$?
  fi
  printf '%s\n' "$out"

  # 4: nothing wrong but a published release that has yet to land here, which merge-back or its pull request carries
  # back, or one that landed after this commit's history left the default branch.
  # A commit pushed in that window cannot be changed to pass, and does not have to be.
  if (( rc == 4 )); then
    echo "::warning::check-release $check found a published release that has yet to land here, or landed after this" \
      "commit's history left the default branch"
  elif (( rc != 0 )); then
    echo "::error::check-release $check said no"
    failed=1
  elif [[ $check == version ]]; then
    candidate=$(printf '%s' "$out" | tail -1)
    printf 'version=%s\n' "$candidate" >> "$GITHUB_OUTPUT"
    channel=$("$PYTHON" "$script" channel "$candidate")
    rc=$?
    if (( rc != 0 )); then
      echo "::error::check-release channel said no"
      failed=1
    else
      printf 'channel=%s\n' "$channel" >> "$GITHUB_OUTPUT"
    fi
  fi
done
exit "$failed"
