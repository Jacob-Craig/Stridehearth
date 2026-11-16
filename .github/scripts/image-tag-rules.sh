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

# Whether commit $1 is on main or on release/$2.$3.x, the only places a vX.Y.Z Release may be tagged.
on_release_line() {
  local branch
  for branch in main "release/$2.$3.x"; do
    if git rev-parse --quiet --verify "refs/remotes/origin/$branch" >/dev/null &&
      git merge-base --is-ancestor "$1" "refs/remotes/origin/$branch"; then
      return 0
    fi
  done
  return 1
}
if ! on_release_line "$sha" "$major" "$minor"; then
  refuse "$tag is not on main or $hotfix_branch; Releases are tagged on one of those (ADR-0009)."
fi

echo "type=semver,pattern={{version}}"
if [ -n "$prerelease" ]; then
  exit 0
fi

# Every Release (no pre-releases) this script would accept, as X.Y.Z: a stray vX.Y.Z tag that sits off main and off
# its own release/X.Y.x branch was refused, so it must not hold the floating tags back either.
releases() {
  local release
  git tag --list 'v*' | { grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' || true; } | while IFS= read -r release; do
    [[ "$release" =~ ^v([0-9]+)\.([0-9]+)\. ]]
    if on_release_line "$(git rev-parse "$release^{commit}")" "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}"; then
      echo "${release#v}"
    fi
  done
}
accepted=$(releases)

# The highest accepted Release whose version starts with a prefix ("" for any), as X.Y.Z.
highest() {
  local release
  while IFS= read -r release; do
    if [[ -n "$release" && "$release" == "$1"* ]]; then
      echo "$release"
    fi
  done <<<"$accepted" | sort -t. -k1,1n -k2,2n -k3,3n | tail -n 1
}

if [ "$(highest "$major.$minor.")" = "$version" ]; then
  echo "type=semver,pattern={{major}}.{{minor}}"
fi
if [ "$(highest "$major.")" = "$version" ]; then
  echo "type=semver,pattern={{major}}"
fi
if [ "$(highest "")" = "$version" ]; then
  echo "type=raw,value=latest"
fi
