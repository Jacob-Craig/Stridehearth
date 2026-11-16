# Going-Public Checklist

Run this checklist before a Stridehearth repository is made public (ADR-0012). It assumes no knowledge of the project. Every item has a command and the result that counts as a pass. Record each result (paste the output, or "pass") on the go-public issue before ticking the item.

The checklist runs in two parts. Part A runs against the new repository while it is still private. Some GitHub settings only exist on public repositories (on a free personal account), so Part B runs right after the flip, before anyone is told the repository exists. If any Part B item fails, make the repository private again, fix it, and rerun Part B.

## Before you start

You need:

- `git`, `docker` and the GitHub CLI `gh`, logged in as the repository owner (`gh auth status`).
- A **fresh clone** of the new repository. Do not use an old working copy: it may contain untracked files or other branches.

```sh
export REPO=Jacob-Craig/Stridehearth   # the new public repository
export OLD_REPO=Jacob-Craig/Active-Reader   # the private archive
gh repo clone "$REPO" stridehearth-check && cd stridehearth-check
```

Every command below runs from the root of that clone.

## Part A: before the flip (repository still private)

### A1. History is one squashed commit with the noreply author

```sh
git rev-list --all --count
git log --all --format='author=%an <%ae> committer=%cn <%ce>'
git ls-remote origin
```

Pass: the count is `1`. The author and committer emails are both `79336797+Jacob-Craig@users.noreply.github.com`. `ls-remote` lists only `HEAD` and `refs/heads/main` (no other branches, tags or `refs/pull/*`).

### A2. The clone's local git identity is the noreply address

```sh
git config --local user.email
```

Pass: prints `79336797+Jacob-Craig@users.noreply.github.com`. (Run this in the maintainer's own working clone, not only in the check clone.)

### A3. gitleaks finds no secrets in the snapshot

Run both the history scan and the plain-directory scan, with the same image CI uses:

```sh
docker build --quiet --tag gitleaks-ci .github/gitleaks
docker run --rm --volume "$PWD:/repo" gitleaks-ci git /repo --redact --verbose --no-banner
docker run --rm --volume "$PWD:/repo" gitleaks-ci dir /repo --redact --verbose --no-banner
```

Pass: both commands end with `no leaks found` and exit `0` (`echo $?`).

### A4. No activity files outside the allowlist

```sh
.github/scripts/check-activity-files.sh
git ls-files | grep -iE '\.(fit|gpx|tcx|kml|kmz|zip)(\.gz)?$'
```

Pass: the guard prints `Activity-file guard passed`. Every path the `grep` lists is a synthetic fixture under a folder in `.github/activity-file-allowlist.txt`. To confirm a fixture is synthetic, check that the generator that produces it is committed next to the tests and that regenerating it gives the same bytes (see the fixture folder's README or the generator's test).

### A5. No images or videos outside the allowlist, and none carry location metadata

```sh
git ls-files | grep -iE '\.(png|jpe?g|gif|webp|svg|ico|bmp|tiff?|avif|heic|heif|mp4|m4v|mov|webm|avi|mkv|wmv|flv|mpe?g)$'
docker run --rm --volume "$PWD:/w:ro" --workdir /w alpine:3.22 sh -c \
  'apk add -q --no-cache exiftool >/dev/null && exiftool -r -q -gps:all -location:all -s -ext png -ext jpg -ext jpeg -ext gif -ext webp -ext tif -ext tiff -ext heic -ext heif -ext mp4 -ext mov .'
```

Pass: every listed file is under an allowed docs image folder, and the `exiftool` command prints nothing (no GPS or location tags in any image or video in the clone). If it prints anything, strip the metadata (`exiftool -all= <file>`) or remove the image. Also open each image and confirm it does not show a real map route, a real address, or a real person.

### A6. No links to media hosted outside the repository

Old screenshots and GIFs were uploaded to GitHub issue and pull request bodies and to media branches. None may be referenced from the public tree.

```sh
git grep -nIiE 'user-images\.githubusercontent\.com|github\.com/user-attachments|private-user-images|camo\.githubusercontent\.com|raw\.githubusercontent\.com|/blob/[^ )]*\.(png|jpe?g|gif|mp4|mov|webm)'
git grep -nI "$OLD_REPO"
```

Pass: the first command prints nothing. The second prints nothing, or only mentions of the old repository's name that do not link to media (ADR-0012 accepts that old issue numbers point into the private archive).

### A7. No personal strings

Use your own values for the placeholders. Do not write them into the repository or into the go-public issue.

```sh
# Real names: your first name, family name, and anyone else's real name.
git grep -nIiE '<first name>|<family name>|<other real names>'
# Email addresses: count addresses per domain, then list any address outside example domains.
git grep -IhoE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' | sed 's/.*@//' | sort | uniq -c | sort -rn
git grep -nIoE '[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}' | grep -vE '@(example\.(com|org|net)|users\.noreply\.github\.com)$'
# The real run's city, its districts, nearby towns, the home street and postcode.
git grep -nIiE '<the real run'"'"'s city>|<district>|<nearby town>|<street>|<postcode>'
# Coordinate literals (five or more decimal places).
git grep -nIE '[-]?[0-9]{1,3}\.[0-9]{5,}'
```

Pass:

- **Names:** no real names, except the owner's GitHub handle and the code's package namespace (`src/main/kotlin/com/...`), which the owner has accepted as public.
- **Emails:** every address is a placeholder (`example.com`, `example.org`, `test`/`localhost` domains), a GitHub noreply address, or a deliberately public project address. No personal mailbox (for example `gmail.com`, `outlook.com`, `icloud.com`, or an employer's domain).
- **City:** the place-name search prints nothing.
- **Coordinates:** every hit is synthetic test data, a projection constant, or a non-geographic number. None is within a few kilometres of the real run's area. Check any coordinate you are unsure of on a map.

### A8. Licence and notice are present

```sh
head -3 LICENSE
shasum -a 256 LICENSE
grep -n 'Garmin FIT SDK' NOTICE
grep -n 'com.garmin:fit' build.gradle.kts
```

Pass: `LICENSE` starts with `Apache License` / `Version 2.0, January 2004`, and its SHA-256 is `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` (the unmodified text from apache.org). `NOTICE` names the Garmin FIT SDK and links to its licence. The SDK is a Maven dependency, not vendored: `git ls-files | grep -iE '(^|/)com/garmin/|fit-[0-9.]+\.jar$'` prints nothing. The reasoning is in `docs/research/2026-fit-sdk-licence-redistribution.md`.

### A9. SECURITY.md points to private vulnerability reporting

```sh
grep -n 'Report a vulnerability' SECURITY.md
```

Pass: the line is found.

### A10. Every workflow has an explicit least-privilege `permissions` block

```sh
for f in .github/workflows/*.y*ml; do echo "== $f"; grep -n -A3 'permissions:' "$f"; done
docker run --rm --volume "$PWD:/repo" --workdir /repo rhysd/actionlint:latest -color .github/workflows/*.yml
```

Pass: every workflow has a top-level `permissions:` block that grants only `read` (normally just `contents: read`). Any `write` appears only in a job-level block, with a comment saying why. `actionlint` reports no errors.

### A11. Third-party actions are pinned to commit SHAs

```sh
grep -nE 'uses: ' .github/workflows/*.yml | grep -vE 'uses: [^@]+@[0-9a-f]{40} # v'
```

Pass: prints nothing.

### A12. Dependabot covers every ecosystem

```sh
grep -n 'package-ecosystem' .github/dependabot.yml
```

Pass: `github-actions`, `gradle`, `npm`, `docker` and `docker-compose` are listed.

### A13. CI passes on the snapshot

```sh
gh run list --repo "$REPO" --branch main --limit 1 --json conclusion,headSha,workflowName
git rev-parse HEAD
```

Pass: the latest `CI` run on `main` has `"conclusion":"success"` and its `headSha` equals `git rev-parse HEAD`. Its jobs include `Secret Scan (gitleaks)` and `Activity File & Media Guard`.

### A14. Branch ruleset on `main`

Create the ruleset in **Settings > Rules > Rulesets** with target `main`, enforcement **Active**, and these rules: *Require a pull request before merging*, *Require status checks to pass* (every CI job: `Lint & Style Check`, `Frontend Lint, Typecheck & Tests`, `Integration & Unit Tests`, `Secret Scan (gitleaks)`, `Activity File & Media Guard`), *Block force pushes*, and *Restrict deletions*.

```sh
gh api "repos/$REPO/rulesets" --jq '.[] | {id, name, enforcement, target}'
gh api "repos/$REPO/rulesets/<id>" --jq '{enforcement, include: .conditions.ref_name.include, rules: [.rules[].type], checks: [.rules[] | select(.type=="required_status_checks") | .parameters.required_status_checks[].context]}'
```

Pass: one ruleset with `"enforcement":"active"`, `include` containing `~DEFAULT_BRANCH` or `refs/heads/main`, rules containing `pull_request`, `required_status_checks`, `non_fast_forward` and `deletion`, and `checks` listing every CI job above.

On a free personal account GitHub does not enforce rulesets on private repositories, so B2 checks that it is enforced after the flip.

### A15. Workflow token defaults to read-only

```sh
gh api "repos/$REPO/actions/permissions/workflow"
```

Pass: `"default_workflow_permissions":"read"` and `"can_approve_pull_request_reviews":false`. Set these in **Settings > Actions > General > Workflow permissions** if not.

### A16. The account blocks pushes that expose a personal email

This is an account setting, not a repository setting, so there is no API for it.

Check **GitHub > Settings > Emails**: *Keep my email addresses private* and *Block command line pushes that expose my email* are both ticked.

Pass: both are ticked. To prove it, make a throwaway private repository, commit with your personal address as author, and push. GitHub must reject the push with `GH007: Your push would publish a private email address`. Delete the throwaway repository afterwards.

## Part B: right after the flip to public

Flip with `gh repo edit "$REPO" --visibility public --accept-visibility-change-consequences`, then immediately:

### B1. The repository is public and the licence is detected

```sh
gh repo view "$REPO" --json visibility,licenseInfo
```

Pass: `"visibility":"PUBLIC"` and `licenseInfo.key` is `apache-2.0`.

### B2. The ruleset is enforced

```sh
gh api "repos/$REPO/rules/branches/main" --jq '[.[].type] | sort'
```

Pass: the list contains `deletion`, `non_fast_forward`, `pull_request` and `required_status_checks`. An empty list means the ruleset is not enforced: go back to A14.

### B3. Secret scanning with push protection

Turn on in **Settings > Advanced Security**: *Secret Protection* (secret scanning) and *Push protection*.

```sh
gh api "repos/$REPO" --jq '.security_and_analysis | {secret_scanning: .secret_scanning.status, push_protection: .secret_scanning_push_protection.status}'
```

Pass: both are `"enabled"`.

### B4. Private vulnerability reporting

Turn on in **Settings > Advanced Security > Private vulnerability reporting**, or:

```sh
gh api -X PUT "repos/$REPO/private-vulnerability-reporting"
gh api "repos/$REPO/private-vulnerability-reporting"
```

Pass: `{"enabled":true}`, and the repository's **Security** tab shows a **Report a vulnerability** button to a logged-in user who is not the owner.

### B5. Workflows from outside contributors need approval

Set **Settings > Actions > General > Approval for running fork pull request workflows from contributors** to *Require approval for all external contributors*, or:

```sh
gh api -X PUT "repos/$REPO/actions/permissions/fork-pr-contributor-approval" -f approval_policy=all_external_contributors
gh api "repos/$REPO/actions/permissions/fork-pr-contributor-approval"
```

Pass: `"approval_policy":"all_external_contributors"`.

### B6. Dependabot alerts and security updates

```sh
gh api -X PUT "repos/$REPO/vulnerability-alerts"
gh api -X PUT "repos/$REPO/automated-security-fixes"
gh api -i "repos/$REPO/vulnerability-alerts" | head -1
gh api "repos/$REPO/automated-security-fixes"
```

Pass: the alerts check returns `HTTP/2.0 204`, and security fixes return `"enabled":true`. Within a day, the **Insights > Dependency graph > Dependabot** page lists every ecosystem from A12 without errors.

### B7. The old repository is private and archived

```sh
gh repo view "$OLD_REPO" --json visibility,isArchived
```

Pass: `"visibility":"PRIVATE"` and `"isArchived":true`. (Transfer its open issues before archiving it: an archived repository's issues cannot be transferred.)

### B8. Transferred issues do not link to old media

```sh
gh issue list --repo "$REPO" --state all --limit 500 --json number,body,title \
  --jq '.[] | select(.body | test("user-images\\.githubusercontent|user-attachments|private-user-images|camo\\.githubusercontent")) | "#\(.number) \(.title)"'
```

Pass: prints nothing.
