# 0011. Closed Registration by Default

Status: accepted.

A self-hosted Server should not let a stranger create an account and fill its database, but a Server run as a public service must still be able to accept sign-ups. Every Server therefore has a Registration Mode, open or closed, set by the Operator in the Server's environment rather than inside the app, and the default is closed. On a closed Server only the very first account may register itself. That account becomes the Administrator, who adds anyone else by issuing single-use invite links that expire after 7 days. The sign-in screen states plainly when registration is closed.

## Considered Options

- **Open by default, with closing as an opt-in**: Rejected. Most Servers are homelab installs, and an Operator who never reads the docs would leave a reachable Server open to anyone. Opening a Server should be the deliberate choice.
- **Registration Mode as an in-app setting the Administrator toggles**: Rejected. Keeping it with the Operator means a compromised Administrator account cannot open the Server, and the setting sits with the rest of the Server's configuration.
- **Administrator-created accounts with temporary passwords**: Rejected in favour of invite links. Temporary passwords travel over chat or email and force a reset on first sign-in. With an invite link, the person picks their own password.

## Consequences

- The upgrade that introduces the Administrator makes the earliest-registered account on an existing Server its Administrator.
- A Server that upgrades without setting a Registration Mode becomes closed. Release notes must warn Operators who relied on open sign-ups.
- Registration Mode is read only by the check that accepts or rejects a sign-up, so open and closed Servers otherwise run identical code. CI tests sign-up under both modes.
- An unknown or misspelled Registration Mode stops the Server from starting with a clear message. It never falls back to open.
- Closing registration does not protect sign-in. Rate limiting on sign-in, sign-up and invite redemption applies to every Server whatever its mode, and is separate work.
- Open mode is not hardened for the public internet until per-athlete storage and upload quotas (and possibly email verification) exist. Until then the Operator docs and the startup log of an open Server say so plainly.
