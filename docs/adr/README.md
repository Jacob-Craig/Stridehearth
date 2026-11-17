# Architectural Decision Records (ADRs)

This directory documents the architectural and engineering decisions for the Stridehearth codebase.

## Index

- [0001-bulk-fit-import-batch-architecture.md](0001-bulk-fit-import-batch-architecture.md): Asynchronous Bulk FIT File Ingestion via Redis Task Queue and PostgreSQL
- [0002-ci-pipeline-and-push-guardrails.md](0002-ci-pipeline-and-push-guardrails.md): Continuous Integration Pipeline, Mechanical Code Linting, and Push Guardrails (agent push governance superseded by ADR-0005)
- [0003-best-efforts-and-personal-records-engine.md](0003-best-efforts-and-personal-records-engine.md): Best Efforts and Personal Records Engine (concurrency claim superseded in part by ADR-0006)
- [0004-react-typescript-spa-architecture.md](0004-react-typescript-spa-architecture.md): React 19 and Kotlin-Aligned TypeScript SPA Architecture
- [0005-agents-may-push-feature-branches.md](0005-agents-may-push-feature-branches.md): Agents May Push Feature Branches
- [0006-serialise-personal-record-writes-per-athlete-and-sport.md](0006-serialise-personal-record-writes-per-athlete-and-sport.md): Serialise Personal Record Writes per Athlete and Sport
- [0007-personal-heatmap-counted-segment-vector-tiles.md](0007-personal-heatmap-counted-segment-vector-tiles.md): Personal Heatmap as Counted-Segment Vector Tiles Built on Request (tile format and scale superseded by ADR-0008)
- [0008-personal-heatmap-raster-density-tiles.md](0008-personal-heatmap-raster-density-tiles.md): Personal Heatmap as Raster Density Tiles
- [0009-trunk-based-releases-pulled-from-a-registry.md](0009-trunk-based-releases-pulled-from-a-registry.md): Trunk-Based Releases Pulled from a Container Registry
- [0010-downgrade-by-restoring-a-pre-upgrade-backup.md](0010-downgrade-by-restoring-a-pre-upgrade-backup.md): Downgrade by Restoring a Pre-Upgrade Backup
- [0011-closed-registration-by-default.md](0011-closed-registration-by-default.md): Closed Registration by Default
- [0012-publish-as-a-new-repository-with-squashed-history.md](0012-publish-as-a-new-repository-with-squashed-history.md): Publish as a New Repository with Squashed History
- [0013-derived-analytics-read-cleaned-activity-details.md](0013-derived-analytics-read-cleaned-activity-details.md): Derived Analytics Read Cleaned Activity Details, Computed on Demand
- [0014-recompute-derived-activity-data-in-one-versioned-refresh.md](0014-recompute-derived-activity-data-in-one-versioned-refresh.md): Recompute Derived Activity Data in One Versioned Refresh, Backfilled by a Background Worker
- [0015-athlete-local-dates-come-from-a-stored-iana-time-zone.md](0015-athlete-local-dates-come-from-a-stored-iana-time-zone.md): Athlete-Local Dates Come from a Stored IANA Time Zone
