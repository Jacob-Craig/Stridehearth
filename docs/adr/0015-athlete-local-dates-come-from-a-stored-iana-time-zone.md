# 0015. Athlete-Local Dates Come from a Stored IANA Time Zone

Status: accepted.

Thresholds are dated, and the metrics later issues derive from them (Training Load from the Thresholds in force on an Activity's date, the daily rollups of the Performance Management Chart, 90-day model windows) need every Activity's **calendar day**. An Activity stores only a UTC `started_at`, and nothing recorded which calendar the athlete lives in: `FitActivityParser` reads the FIT local offset only to build a title, and manual Activities have none (issue #34).

We store one IANA region id per athlete, `app_user.time_zone` (nullable; the effective zone is the stored one, else UTC), and count every athlete-local date in it: Kotlin through `AthleteTimeZone.of(userId)` and `localDate(userId, instant)`, SQL through `(started_at AT TIME ZONE :tz)::date` with the same id. The SPA sets it once from `Intl.DateTimeFormat().resolvedOptions().timeZone` when a signed-in athlete has none, and the Settings page changes it (`PUT /api/athlete/time-zone`, region ids only, as the Heatmap's year shortcuts already require). A change publishes `AthleteTimeZoneChanged(userId)`, and Threshold writes publish `ThresholdsChanged(userId, kind, affectedFrom)`, both inside the writing transaction, for later issues to turn into durable recompute requests.

The Threshold as-of lookup is per kind: the latest entry with `effective_date` on or before the date; **before a kind's first entry, that entry applies backwards**; null only when the kind has never been recorded. `affectedFrom` is therefore null ("from the beginning") whenever the changed entry is, or was, the earliest of its kind.

## Considered Options

- **Each Activity's own UTC offset from its FIT file**: Rejected. Manual Activities have none, many files omit it, and two Activities a few hours apart across a trip could fall on days that contradict the athlete's own calendar.
- **UTC dates for everyone**: Rejected. An evening run in the Americas or a morning run in Asia would count towards the neighbouring day, which shifts Daily Load and the Thresholds in force.
- **The browser's zone sent with every request** (as `GET /api/heatmap/years?timeZone=` does): Rejected for derived data. Stored rollups must be computed in the background without a request, and two browsers in different zones would disagree.
- **A strict as-of lookup with no backwards fallback**: Rejected. An athlete who records Thresholds today and imports years of history would get no Training Load for any of it. Applying the earliest entry backwards is a deliberate extension of the issue text, flagged for the maintainer.

## Consequences

- An athlete who never opens the SPA after this change stays on UTC until they do; a time-zone change later is a full recompute of their athlete-local data, which the event lets later issues schedule.
- Only region ids are accepted: PostgreSQL reads a bare offset such as `+02:00` with the POSIX sign, the other way round.
- Threshold and time-zone events have no subscriber that recomputes anything yet; Training Load (#35) and the daily rollups (#36) subscribe. The only listener today withdraws pending Threshold Proposals that a newly recorded value already matches within 3%.
