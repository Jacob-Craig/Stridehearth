#!/usr/bin/env bash
# Activity-file guard (ADR-0012).
#
# Fails when an activity file (.fit .gpx .tcx .kml .kmz, gzipped variants, .zip),
# an image or a video sits outside the paths in .github/activity-file-allowlist.txt.
#
# Usage:
#   .github/scripts/check-activity-files.sh            # check every tracked file in HEAD
#   .github/scripts/check-activity-files.sh BASE..HEAD # also check every file added by any
#                                                       # commit in the range, even if a later
#                                                       # commit deleted it (merge commits keep
#                                                       # those commits in history)
#
# ACTIVITY_FILE_ALLOWLIST overrides the allowlist path (used by tests).
set -euo pipefail

GUARDED_EXTENSIONS='fit|gpx|tcx|kml|kmz|zip|png|jpe?g|gif|webp|svg|ico|bmp|tiff?|avif|heic|heif|mp4|m4v|mov|webm|avi|mkv|wmv|flv|mpe?g'
GUARDED_REGEX="\\.(${GUARDED_EXTENSIONS})(\\.gz)?\$"

root="$(git rev-parse --show-toplevel)"
cd "$root"
allowlist="${ACTIVITY_FILE_ALLOWLIST:-$root/.github/activity-file-allowlist.txt}"
range="${1:-}"

if [ ! -f "$allowlist" ]; then
  echo "Allowlist not found: $allowlist" >&2
  exit 2
fi

patterns=()
while IFS= read -r line || [ -n "$line" ]; do
  line="${line%%$'\r'}"
  case "$line" in '' | '#'*) continue ;; esac
  patterns+=("$line")
done <"$allowlist"

is_allowed() {
  local path="$1" pattern
  for pattern in ${patterns[@]+"${patterns[@]}"}; do
    # Unquoted right-hand side: glob match, where * also matches /.
    # shellcheck disable=SC2053
    if [[ "$path" == $pattern ]]; then
      return 0
    fi
  done
  return 1
}

list_candidates() {
  git -c core.quotePath=off ls-files
  if [ -n "$range" ]; then
    # --no-renames: a move shows up as an add at the new path, so moving a file
    # out of an allowed folder is caught too.
    git -c core.quotePath=off log --no-renames --diff-filter=A --name-only --format= "$range"
  fi
}

violations=()
while IFS= read -r path; do
  [ -z "$path" ] && continue
  if ! is_allowed "$path"; then
    violations+=("$path")
  fi
done < <(list_candidates | grep -iE "$GUARDED_REGEX" | sort -u || true)

if [ "${#violations[@]}" -eq 0 ]; then
  echo "Activity-file guard passed: no activity files, images or videos outside $(basename "$allowlist")${range:+ (tree and commits in $range)}."
  exit 0
fi

echo "Activity-file guard failed: these files are activity files, images or videos outside the allowlist." >&2
for path in "${violations[@]}"; do
  echo "  $path" >&2
  if [ -n "${GITHUB_ACTIONS:-}" ]; then
    echo "::error file=${path}::Activity file or media outside .github/activity-file-allowlist.txt (ADR-0012)"
  fi
done
cat >&2 <<'EOF'

Real recorded runs, and screenshots or videos of them, must never be committed (ADR-0012).
If the file is synthetic and belongs in the repository, move it under an allowed folder,
or add its folder to .github/activity-file-allowlist.txt in the same pull request.
If a pull request commit added the file and a later one removed it, it is still in the
branch history: drop it from the commits (rebase) before merging.
EOF
exit 1
