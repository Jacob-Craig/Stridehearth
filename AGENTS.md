## Agent skills

### Issue tracker

GitHub issues tracked via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Triage roles mapped to `needs-triage`, `needs-info`, `ready-for-dev-detailed`, `ready-for-dev-partial-detail`, and `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout (`GLOSSARY.md` + `docs/adr/` at the repo root). See `docs/agents/domain.md`.

### Visual check

Any change that can alter what the SPA renders, including "no visual change" refactors, must pass a Playwright visual check before you push for review. See `docs/agents/visual-check.md`.

### Git Push & Branch Safety

- **Feature-Branch Pushes Allowed**: AI agents may push feature branches (`git push origin <branch>`, `git push -u origin <branch>`) once all tests and linters pass locally. Changes reach `main` only via pull request (see `docs/adr/0005-agents-may-push-feature-branches.md`).
- **Forbidden Push Forms**: Never push to `main`/`master` (including `HEAD:main`, `:main`, `refs/heads/main`, `--delete main`); never skip hooks (`--no-verify`, `-n`, `core.hooksPath` overrides); never force push (`--force`, `-f`, `--force-with-lease`, `--mirror`, `+refspec`). The Claude Code hook and `.githooks/pre-push` reject these.
- **No Destructive Git Operations**: AI agents (Antigravity, Claude Code, Cursor, scripts) are forbidden from destructive git operations (`git reset --hard`, `git clean -f`, `git clean -fd`, `git branch -D`, `git checkout .`, `git restore .`).
- **Review Handoff**: When features or fixes are complete, agents must verify all tests and linters locally (plus the visual check for UI-affecting changes), commit on a descriptive feature branch, push it, and notify the human developer that the branch is ready for review.

