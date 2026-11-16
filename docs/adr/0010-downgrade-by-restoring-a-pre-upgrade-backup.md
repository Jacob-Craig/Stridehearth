# 0010. Downgrade by Restoring a Pre-Upgrade Backup

Status: accepted.

Flyway migrations only run forward, so a Server cannot step back from a One-way Release by running older code. The only supported way back is restoring a backup taken just before the upgrade. Stridehearth therefore takes that backup itself and refuses to run older code against a newer schema. When migrations are pending at startup, the app first writes a `pg_dump` of the database into a backups volume, keeps the latest three, and refuses to migrate if the dump fails. Moving between Releases that share a schema needs no backup and can go in either direction.

## Considered Options

- **Expand/contract migrations, so that release N-1 always runs on release N's schema**: Rejected. Every schema change would need a two-release dance, and that discipline is easy to get wrong without anyone noticing. It also still cannot undo data rewrites.
- **Flyway undo migrations**: Rejected. They are a paid Flyway feature, and hand-written undos of data changes are rarely trustworthy.
- **Leave backups to the Operator, documented and with an optional backup container**: Rejected as the main mechanism. Most self-hosters skip it, and they find out only when they need to roll back.

## Consequences

- Flyway's default of ignoring "future" migrations (applied in the database but unknown to the running code) is overridden, so an older image fails fast with a clear message instead of running against a schema it does not understand.
- The app image bundles a PostgreSQL client whose major version matches the supported PostGIS server (16). A server major-version upgrade becomes an Operator-visible event, called out in release notes.
- Release notes flag every One-way Release, and the sample compose file pins an exact version (see ADR-0009).
- Normal restarts with no pending migrations take no backup and cost nothing.
