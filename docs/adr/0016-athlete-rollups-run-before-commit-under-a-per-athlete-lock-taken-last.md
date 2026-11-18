# 0016. Athlete Rollups Run Before Commit under a Per-Athlete Lock Taken Last

Status: accepted. Refines ADR-0006 with a global lock order.

The **Performance Management Chart** (issue #36) is an athlete-level rollup: each day's Fitness and Fatigue depend on every earlier day's **Daily Load**, so an Activity imported, upgraded, reclassified, moved or deleted on one day changes that day and every later one. Concurrent writers for one athlete (Import Batch workers, a delete, the recompute worker) must not interleave, or two recomputes would each miss the other's Activity. ADR-0006 already serialises Personal Record writes with an advisory lock per athlete and sport, taken before the caller flushes; copying that literally would take a second lock mid-transaction, and a file holding a Ride and then a Run (`personal_record:RIDE`, then the rollup lock, then `personal_record:RUN`) deadlocks against a concurrent Run import that holds `personal_record:RUN` and wants the rollup lock.

Writers therefore only **mark** what changed, by the Activity's `startedAt` instant (`AthleteRollups.markDailyLoad`, already called from the derived-data refresh, ADR-0014). One `TransactionSynchronization` per transaction runs the rollups in `beforeCommit`:

1. flush the caller's pending changes;
2. for each marked athlete in ascending id order, take `pg_advisory_xact_lock(hashtextextended('athlete_rollup:<userId>', 0))`;
3. read `athlete_analytics_state.rollup_version`:
   - no row: a full rebuild, and the row is created at `ROLLUP_VERSION`;
   - below `ROLLUP_VERSION`: skip, because the recompute worker rebuilds it in full;
   - otherwise: convert the earliest mark to an athlete-local day with the zone read now, under the lock, and rebuild `athlete_daily_load` and `athlete_daily_metrics` from that day forward, continuing from the stored metrics of the day before. Rows before it are never rewritten.

**Global lock order:** (1) Personal Record advisory locks (ADR-0006); (2) Activity row locks and the shared Training Load scoring lock (ADR-0014); (3) `athlete_rollup:<userId>`, taken only before commit; (4) the athlete's `athlete_analytics_state` row, which the rollup may lock after (3): the worker's rebuild reads it `FOR UPDATE`, and the version upserts can wait on a concurrent, uncommitted insert of it. The rule that keeps this free of deadlocks: **a transaction that has written or locked the state row, or holds the exclusive scoring lock, must never then take the rollup lock**, and nothing inside the rollup may publish `ThresholdsChanged` or `AthleteTimeZoneChanged` (their listeners take the exclusive scoring lock and write the state row). The holder of the rollup lock therefore waits only on the state row, whose holders never wait on the rollup lock, so the rollup lock cannot be part of a deadlock cycle. A later rollup that records a Threshold (#39) must keep to this rule. Before commit the rollup writes only athlete-keyed rows (a key-share lock on `app_user`, compatible with a concurrent time-zone update), never rows that reference an Activity. Under READ COMMITTED each rollup statement sees every earlier commit, so whichever writer commits last writes the state of all of them.

A time-zone change can move every Activity to another day, so it does not recompute inline: its listener marks the rollups stale (`rollup_version = 0`, after taking the scoring lock as the Training Load recompute request does), and the recompute worker's third stage rebuilds them in full, one athlete per transaction, under the same rollup lock and with the state row locked `FOR UPDATE`, so a second zone change cannot be lost between the rebuild and setting the version. Raising `ROLLUP_VERSION` rebuilds every athlete the same way; migration V22 creates every existing athlete's row at version 0. The third stage also builds an athlete who has Activities but no state row (Activities inserted by SQL, never through a writer), and a Training Load recompute request that creates the row for such an athlete creates it at version 0, so the first rollup they get is a full one. Such an athlete has no row to lock while the rebuild runs, so it records its version only if no row exists by then (`ON CONFLICT DO NOTHING`, as the before-commit rollup does): a zone change that committed its stale row during the rebuild keeps it, and the next drain rebuilds again. An athlete whose rebuild fails other than in the database is logged and backed off, like a failed Activity, so the athletes after them are still rebuilt.

The stored series ends at the athlete's last day with an Activity. `GET /api/athlete/pmc` continues it to today in memory with zero-load days, so no daily job is needed for Fitness and Fatigue to decay.

## Considered Options

- **A per-athlete lock taken mid-transaction, as in ADR-0006**: Rejected. It deadlocks with the Personal Record locks in a multi-session import, as above.
- **Recompute after commit**: Rejected for the reason ADR-0006 gives: a window in which the chart disagrees with the Activities, and a second transaction that can fail on its own after the first committed.
- **Compute the chart on read**: Rejected. Every Dashboard load would aggregate every Activity and run the recurrence over the athlete's whole history; storing it costs one rewrite per change instead.
- **Rebuild everything on every change**: Rejected. Recomputing from the earliest affected day is as simple and leaves earlier rows untouched, which the acceptance criteria check.

## Consequences

- An Activity imported years before the athlete's latest one rewrites every later day's metrics (about 3,650 rows for ten years), serialised per athlete. Accepted.
- Writers for one athlete queue briefly on the rollup lock at commit; writers for different athletes never wait on each other.
- After a time-zone change the chart is stale until the worker's next run (about 15 s); writes in that window skip the rollup, and the rebuild covers them.
- Any later athlete rollup (Critical Speed models, #39) runs in the same before-commit pass under the same lock and version.
