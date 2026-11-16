# 0012. Publish as a New Repository with Squashed History

Status: accepted.

The original private repository could not simply be made public. Its history held a real recorded run (GPS that could point to the author's home area) as test fixtures, screenshots and GIFs of that route on media branches and in pull request bodies, and commits under a personal email address. GitHub keeps `refs/pull/*` and pull request bodies even after history is rewritten. Stridehearth was therefore published as a new repository, `Jacob-Craig/Stridehearth`, whose history begins with a single squashed commit of the cleaned code. The old repository stays private and archived as the full historical record.

## Considered Options

- **Flip the existing repository to public**: Rejected. It would publish the run data, the media and the personal email address.
- **Rewrite the existing history with `git filter-repo`**: Rejected. It keeps `git blame`, but 271 commits leave plenty of room to miss one leak, and the old pull request refs and bodies would survive anyway.

## Consequences

- Pull request and issue numbers mentioned in earlier ADRs and documents (for example `#119`) point into the private archive, not the public repository.
- Before the snapshot, the real fixtures were replaced by a deterministic synthetic run produced by a committed generator, and every reference to the author's location was removed.
- All commits in the public repository use the GitHub noreply address.
- Permanent checks guard against a repeat: a gitleaks CI job, and a CI check that rejects activity files and images outside the allowed synthetic-fixture and docs folders.
