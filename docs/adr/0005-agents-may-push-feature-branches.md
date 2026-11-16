# 0005. Agents May Push Feature Branches

Status: accepted. Supersedes the agent push-governance part of ADR-0002.

AI coding agents may push feature branches themselves once tests and linters pass locally, replacing ADR-0002's human-only push rule. The human-only handoff slowed every review cycle, and the protection that matters is keeping `main` reachable only through reviewed pull requests, not stopping pushes to branches nobody merges unreviewed. Pushes that bypass verification, target `main`/`master`, or rewrite remote history stay blocked, enforced in layers because any one client-side check can be skipped.

## Considered Options

- **Keep human-only pushes (ADR-0002)**: Rejected. Every finished branch waited on a human to run `git push`, and the `CONFIRM_PUSH`/TTY guard in `.githooks/pre-push` only caught non-interactive callers, which is a weak proxy for "is an agent".
- **Rely on `.githooks/pre-push` alone**: Rejected. `git push --no-verify` (or `-n`, or a `core.hooksPath` override) skips client hooks entirely, so an agent could land commits on `main` while the repository has no server-side branch protection.
- **Allow `--force-with-lease` on feature branches**: Rejected for now. Rebasing a branch under review is occasionally useful, but a bare `--force-with-lease` trusts a remote-tracking ref that a background fetch can refresh, so it can still discard someone else's commits. History rewrites on pushed branches stay a human action.

## Consequences

- **Layer 1, Claude Code `PreToolUse` hook** (`.claude/hooks/block-dangerous-git.sh`): blocks `git push` with `--no-verify`/`-n`; `core.hooksPath` overrides on push (`git -c core.hooksPath=…`, `GIT_CONFIG_*` env) and `git config` writes to `core.hooksPath`; any refspec whose destination is `main` or `master` (`main`, `HEAD:main`, `:main`, `refs/heads/main`, `--delete main`); and force pushes (`--force`, `-f`, `--force-with-lease`, `--force-if-includes`, `--mirror`, `+refspec`). Plain `git push origin feature/x` and `git push -u origin feature/x` are allowed. It also keeps blocking destructive local commands (`git reset --hard`, `git clean -f`, `git branch -D`, `git checkout .`, `git restore .`). It only covers Claude Code; other agents rely on layers 2 and 3.
- **Layer 2, `.githooks/pre-push`**: rejects any push whose remote ref resolves to `refs/heads/main` or `refs/heads/master`, then runs `ktlintCheck testClasses frontendCheck`. The `CONFIRM_PUSH`/TTY guard is removed.
- **Layer 3, GitHub branch protection on `main`** (recommended, not yet enabled): the only layer that a client cannot skip. Requiring pull requests and passing CI on `main` is a repository-settings change for the repository owner.
- **`AGENTS.md`** states the policy for all agents: push feature branches after local verification, never push to `main`/`master`, never use `--no-verify` or force pushes.
