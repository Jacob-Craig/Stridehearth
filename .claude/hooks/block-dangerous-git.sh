#!/usr/bin/env bash
# Claude Code PreToolUse hook: blocks destructive git operations and unsafe pushes.
#
# Agents may push feature branches (see docs/adr/0005-agents-may-push-feature-branches.md),
# but this hook rejects any push that:
#   - skips client hooks (--no-verify / -n, or core.hooksPath overrides),
#   - targets main/master (main, HEAD:main, :main, refs/heads/main, --delete main, ...),
#   - rewrites remote history (--force, -f, --force-with-lease, --force-if-includes, --mirror, +refspec).
# It also blocks changing core.hooksPath via `git config`, which would disable .githooks/pre-push.
# Exit code 2 tells Claude Code to block the tool call and show stderr to the model.
#
# Compatible with macOS bash 3.2 (no ${var,,}, no associative arrays).

INPUT=$(cat)

if command -v jq >/dev/null 2>&1; then
    COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')
else
    COMMAND=$(echo "$INPUT" | sed -n 's/.*"command"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p')
fi

if [ -z "$COMMAND" ]; then
    exit 0
fi

block() {
    echo "BLOCKED: '$COMMAND' $1 Hand off to the human developer." >&2
    exit 2
}

DANGEROUS_PATTERNS=(
  "git reset --hard"
  "git clean -fd"
  "git clean -f"
  "git branch -D"
  "git checkout \."
  "git restore \."
)

for pattern in "${DANGEROUS_PATTERNS[@]}"; do
  if echo "$COMMAND" | grep -qE "$pattern"; then
    block "matches dangerous pattern '$pattern'. Destructive git operations are prohibited."
  fi
done

lower() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]'
}

strip_quotes() {
    local t="$1"
    t="${t#\"}"; t="${t%\"}"
    t="${t#\'}"; t="${t%\'}"
    printf '%s' "$t"
}

# Check one refspec (or branch name) given to `git push`.
check_refspec() {
    local spec="$1" dst
    case "$spec" in
        +*) block "uses a forced '+' refspec. Force pushes are prohibited." ;;
    esac
    dst="${spec##*:}"
    dst="${dst#refs/heads/}"
    if [ "$dst" = "main" ] || [ "$dst" = "master" ]; then
        block "targets '$dst'. Pushes to main/master are prohibited; open a pull request from a feature branch."
    fi
}

check_push() {
    local segment_lower="$1"; shift
    if echo "$segment_lower" | grep -q "core\.hookspath"; then
        block "overrides core.hooksPath on push. Skipping .githooks/pre-push is prohibited."
    fi

    local positional=0 skip_next=0 arg
    for arg in "$@"; do
        if [ "$skip_next" = 1 ]; then skip_next=0; continue; fi
        case "$arg" in
            --no-verify)
                block "uses --no-verify. Skipping .githooks/pre-push is prohibited." ;;
            --force|--force=*|--force-with-lease|--force-with-lease=*|--force-if-includes|--mirror)
                block "uses $arg. Force pushes are prohibited." ;;
            --repo|--receive-pack|--exec|--push-option|-o)
                skip_next=1 ;;
            --*)
                ;;
            -*)
                case "$arg" in
                    *n*) block "uses '$arg' (-n is --no-verify). Skipping .githooks/pre-push is prohibited." ;;
                esac
                case "$arg" in
                    *f*) block "uses '$arg' (-f is --force). Force pushes are prohibited." ;;
                esac
                case "$arg" in
                    *o) skip_next=1 ;;
                esac
                ;;
            *)
                positional=$((positional + 1))
                # First positional is the remote; the rest are refspecs.
                if [ "$positional" -gt 1 ]; then
                    check_refspec "$arg"
                fi
                ;;
        esac
    done
}

check_config() {
    local arg
    local touches_hooks=0 is_read=0
    for arg in "$@"; do
        case "$(lower "$arg")" in
            *core.hookspath*) touches_hooks=1 ;;
            --get|--get-all|--get-regexp|--list|-l|get) is_read=1 ;;
        esac
    done
    if [ "$touches_hooks" = 1 ] && [ "$is_read" = 0 ]; then
        block "modifies core.hooksPath. Disabling .githooks/pre-push is prohibited."
    fi
}

# Split compound commands into simple-command segments and inspect each git invocation.
SEGMENTS=$(printf '%s\n' "$COMMAND" | awk '{ gsub(/[;|&()`]/, "\n"); print }')

while IFS= read -r segment; do
    [ -z "$segment" ] && continue
    read -ra raw_tokens <<< "$segment"
    tokens=()
    for t in "${raw_tokens[@]}"; do
        tokens+=("$(strip_quotes "$t")")
    done

    i=0
    n=${#tokens[@]}
    # Skip env assignments and simple wrappers preceding git.
    while [ "$i" -lt "$n" ]; do
        t="${tokens[$i]}"
        case "$t" in
            [A-Za-z_]*=*|env|command|exec|nohup|time|sudo) i=$((i + 1)) ;;
            *) break ;;
        esac
    done
    [ "$i" -ge "$n" ] && continue
    case "${tokens[$i]}" in
        git|*/git) ;;
        *) continue ;;
    esac
    i=$((i + 1))

    # Skip git global options to find the subcommand.
    while [ "$i" -lt "$n" ]; do
        t="${tokens[$i]}"
        case "$t" in
            -c|-C|--git-dir|--work-tree|--namespace|--exec-path|--config-env) i=$((i + 2)) ;;
            -*) i=$((i + 1)) ;;
            *) break ;;
        esac
    done
    [ "$i" -ge "$n" ] && continue
    subcommand="${tokens[$i]}"
    args=("${tokens[@]:$((i + 1))}")

    case "$subcommand" in
        push) check_push "$(lower "$segment")" ${args[@]+"${args[@]}"} ;;
        config) check_config ${args[@]+"${args[@]}"} ;;
    esac
done <<< "$SEGMENTS"

exit 0
