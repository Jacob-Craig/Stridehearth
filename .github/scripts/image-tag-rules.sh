#!/usr/bin/env bash
# Prints the docker/metadata-action tag rules for this push, one per line (ADR-0009), or fails when the push
# must not publish an image. Run it from a checkout with full history and tags.
#
#   refs/heads/main          -> edge
#   refs/tags/vX.Y.Z         -> X.Y.Z, plus X.Y, X and latest, each only while this is the highest Release in its
#                               range, so a hotfix to an older line never moves them backwards
#   refs/tags/vX.Y.Z-<pre>   -> X.Y.Z-<pre> only
#
# A Release tag must be on main or on its own line's hotfix branch, release/X.Y.x.
# Inputs: GITHUB_REF and GITHUB_SHA, as GitHub Actions sets them.
set -euo pipefail

ref="${GITHUB_REF:?GITHUB_REF must be set}"
sha="${GITHUB_SHA:?GITHUB_SHA must be set}"

refuse() {
  echo "::error::$1" >&2
  exit 1
}

if [ "$ref" = "refs/heads/main" ]; then
  echo "type=edge,branch=main"
  exit 0
fi

case "$ref" in
  refs/tags/*) tag="${ref#refs/tags/}" ;;
  *) refuse "$ref publishes no image; only main and vX.Y.Z tags do." ;;
esac

if ! [[ "$tag" =~ ^v([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.-]+)?$ ]]; then
  refuse "$tag is not a vX.Y.Z Release tag."
fi
major="${BASH_REMATCH[1]}"
minor="${BASH_REMATCH[2]}"
patch="${BASH_REMATCH[3]}"
prerelease="${BASH_REMATCH[4]}"
version="$major.$minor.$patch"
hotfix_branch="release/$major.$minor.x"

on_branch() {
  git rev-parse --quiet --verify "refs/remotes/origin/$1" >/dev/null &&
    git merge-base --is-ancestor "$sha" "refs/remotes/origin/$1"
}
if ! on_branch main && ! on_branch "$hotfix_branch"; then
  refuse "$tag is not on main or $hotfix_branch; Releases are tagged on one of those (ADR-0009)."
fi

echo "type=semver,pattern={{version}}"
if [ -n "$prerelease" ]; then
  exit 0
fi

# The highest Release (no pre-releases) among the tags matching a glob, as X.Y.Z.
highest() {
  git tag --list "$1" | { grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; } |
    sed 's/^v//' | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1
}

if [ "$(highest "v$major.$minor.*")" = "$version" ]; then
  echo "type=semver,pattern={{major}}.{{minor}}"
fi
if [ "$(highest "v$major.*")" = "$version" ]; then
  echo "type=semver,pattern={{major}}"
fi
if [ "$(highest "v*")" = "$version" ]; then
  echo "type=raw,value=latest"
fi
