# Security Policy

## Reporting a vulnerability

Please report security vulnerabilities privately through GitHub's private vulnerability reporting:

1. Open the repository's **Security** tab.
2. Choose **Report a vulnerability**.
3. Describe the issue, how to reproduce it, and its impact.

Please do not open a public issue, pull request or discussion for a vulnerability, and do not include real activity files or personal data in a report.

You should get a first reply within 7 days. Once a fix is released, the advisory is published with credit to the reporter unless you ask otherwise.

## Supported versions

Only the latest release, and `main`, receive security fixes.

## Scope

In scope: the Stridehearth backend, the web app in `frontend/`, the build, and the published jar or container image.

Out of scope: vulnerabilities in third-party dependencies that are already publicly known (Dependabot tracks those), and issues that need an attacker who already has the server's administrator access.
