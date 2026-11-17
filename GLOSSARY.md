# Stridehearth

A fitness activity tracking application that imports, decodes, and visualizes athletic activities and detailed sensor telemetry.

## Language

**Activity**:
A recorded physical workout (such as a run, cycle, or swim) containing summary metrics, sport classification, and ownership.
_Avoid_: Run, workout session

**Activity Details**:
High-resolution time-series sensor measurements and spatial route coordinates recorded during an activity.
At the API/wire level the Activity Details payload is named "telemetry" (the `/api/activities/{id}/telemetry` endpoint, its DTOs, client functions, query keys and the props that carry that payload); that name is accepted there, while prose, UI copy and domain concepts say Activity Details.
_Avoid_: Telemetry log, GPS track, raw data

**Parser Version**:
The revision number of the decoding logic that produced a set of Activity Details and Laps; it rises whenever decoding the same activity file would produce different Activity Details or Laps.
_Avoid_: Schema version, decoder version, format version

**Upgrade**:
Replacing an Activity's stored Activity Details and Laps with ones decoded from its activity file by a newer Parser Version.
_Avoid_: Re-parse, migration

**Backfill**:
Filling in something an Activity lacks (Activity Details, their distance, Laps, Best Efforts, or derived data such as Grade-Adjusted Pace or Normalized Graded Pace) without replacing anything it already has. Replacing derived data that is out of date is a Recompute.
_Avoid_: Repair, fix-up

**Recompute (derived data)**:
Replacing an Activity's stored derived data (such as its Grade-Adjusted Pace or Run metrics like Normalized Graded Pace) because something it was computed from changed: its Activity Details, its sport or start time, or the version of the algorithm that computed it. It never changes the Activity itself, so it leaves no Activity History Entry.
_Avoid_: Reprocess, regenerate, migration

**Activity Source**:
The origin or mechanism by which an activity was ingested into the system (e.g. manual entry, FIT file upload).
_Avoid_: Ingestion type, import channel

**Protected Metrics**:
Summary measurements on an Activity (start time, distance, duration, pace, heart rate, and calories) that are immutable once sourced from an external file or backed by Activity Details, leaving only descriptive metadata (title and sport) editable.
_Avoid_: Locked fields, protected fields, read-only metrics

**Activity History Entry**:
A chronological audit record of one change to an Activity's own recorded data: its creation, an edit to its metadata or Protected Metrics, or a backfill or upgrade of its Activity Details and Laps. Recomputing derived data (Best Efforts, Personal Records, Grade-Adjusted Pace) does not change the Activity and leaves no entry.
_Avoid_: Activity log, audit trail, change event

**Activity Change**:
What one Activity History Entry records, as typed facts rather than text: the Activity was created, edited, batch edited, or had an Upgrade or Backfill of its Activity Details and Laps. An Import Batch Item keeps the same Activity Changes for the Activities its file upgraded. Entries written before Activity Changes existed keep their original text and are never parsed.
_Avoid_: History description, upgrade message

**Lap**:
A discrete interval of an Activity demarcated by the recording device (manually by the athlete or automatically by a configured distance, time, or workout threshold), carrying its own summary metrics.
_Avoid_: Split, segment

**Split**:
A synthetic, uniform distance or time slice (such as every 1 km or 1 mile) calculated across continuous Activity Details, independent of device-recorded Laps.
_Avoid_: Auto-lap, lap interval

**Import Batch**:
A grouped collection of activity files submitted together for asynchronous ingestion, tracked through its lifecycle.
_Avoid_: Bulk upload, ingestion job, run batch

**Import Batch Item**:
An individual activity file within an Import Batch, tracking its ingestion outcome and resulting activities or error.
_Avoid_: Batch entry, upload task, file job

**In-flight Import Batch Item**:
An Import Batch Item that is still waiting to be ingested or is being ingested, and has not yet been recorded with an Import Outcome or a failure. Once recorded, an item never changes again.
_Avoid_: Active item, open item, unfinished item

**Cancelled Import Batch**:
An Import Batch the athlete stopped before all of its items were recorded.
_Avoid_: Aborted batch, stopped batch

**Interrupted Import Batch**:
An Import Batch stopped by a server restart before all of its items were recorded. It differs from a Cancelled Import Batch only in who stopped it.
_Avoid_: Crashed batch, failed batch

**Stranded Import Batch Item**:
An In-flight Import Batch Item whose Import Batch was cancelled or interrupted. It will never be ingested and does not count towards the batch's totals.
_Avoid_: Skipped item, orphaned item, abandoned item

**Import Outcome**:
The classification of what ingesting one activity file did to the athlete's Activities: created new Activities, upgraded existing ones, a mix of both, or found only duplicates. A file that yields no Activities is invalid and has no Import Outcome.
_Avoid_: Import result, upload status, processed result

**Benchmark Distance**:
A standardized target distance (such as 400m, 1k, 1 mile, 5k, 10k, or half-marathon) against which athletic performance is tracked across activities.
Each Benchmark Distance belongs to one sport; a sport with no Benchmark Distances has no Best Efforts or Personal Records.
_Avoid_: Target distance, benchmark interval

**Best Effort**:
The fastest continuous window within a single Activity matching a specified Benchmark Distance of that Activity's sport.
It is derived entirely from the Activity's current Activity Details and sport, so it is never edited, only recomputed.
_Avoid_: Segment, segment effort, fastest interval

**Personal Record**:
An athlete's all-time fastest Best Effort achieved across all recorded Activities for a specific Benchmark Distance and Sport.
When two Best Efforts tie, the one from the earlier Activity holds the Personal Record.
_Avoid_: Personal best, PR segment, record effort

**Telemetry Cleaning**:
The pass that produces Cleaned Activity Details from an Activity's Activity Details and sport: it rejects GPS spikes (speeds above the sport's ceiling and distance jumps), treats heart rate as missing during dropouts and cadence-lock, interpolates gaps of up to 10 seconds, and smooths altitude over a 15 m distance window.
It never changes the stored Activity Details, and an Activity with no GPS fix keeps its speed, distance and altitude exactly as recorded.
_Avoid_: Filtering, sanitising, data correction

**Cleaned Activity Details**:
Speed, distance, heart-rate and altitude series computed from an Activity's Activity Details by Telemetry Cleaning, with GPS spikes, heart-rate dropouts and altitude noise removed.
They are never stored and never shown in place of Activity Details; derived analytics read them. Best Efforts are the exception: they are measured on the recorded distance.
_Avoid_: Filtered telemetry, corrected data, cleaned streams

**Grade-Adjusted Pace (GAP)**:
The pace an athlete would have held on flat ground for the same effort, computed per sample from Cleaned Activity Details and the gradient (Minetti's cost of running, grade clamped to ±45%), for Runs, Walks and Hikes.
Stored as a speed; derived, so recomputed, never edited. Without altitude or a GPS fix it equals the cleaned speed.
_Avoid_: Effort pace, adjusted speed

**Moving Time**:
The seconds of a Run spent moving: its Grade-Adjusted Pace resampled to one value a second, leaving out recording pauses longer than 10 seconds and seconds slower than 0.5 m/s.
Derived, so recomputed, never edited. It is not the Activity's recorded duration.
_Avoid_: Active time, elapsed time

**Normalized Graded Pace (NGP)**:
A Run's Grade-Adjusted Pace weighted towards its harder stretches: the fourth-power mean of its 30-second rolling average over Moving Time. It needs no heart rate.
Stored as a speed; derived, so recomputed, never edited.
_Avoid_: Normalized pace, NP, weighted pace

**Efficiency Factor (EF)**:
A Run's Normalized Graded Pace in metres per minute divided by its average heart rate over Moving Time. A Run whose heart rate covers less than 80% of its Moving Time has none.
Derived, so recomputed, never edited.
_Avoid_: Efficiency, pace-to-heart-rate ratio

**Aerobic Decoupling**:
How far a Run's Efficiency Factor fell from the first half to the second, as a percentage of the first, after leaving out the first 10% (warm-up) and last 5% (cool-down) of Moving Time. Below 5% heart rate stayed coupled to pace; 5% or more is drift.
It is only reported for a steady state: at least 30 minutes of Moving Time and a 30-second rolling Grade-Adjusted Pace that varies by less than 12% (coefficient of variation). Otherwise it is absent with the reason (no pace data, no heart rate, too short, not steady). Derived, so recomputed, never edited.
_Avoid_: Cardiac drift, Pa:HR, heart-rate drift

**Dashboard**:
The athlete's primary landing view summarizing Personal Records, aggregate training volume, and performance trends across recorded Activities.
_Avoid_: Statistics page, homepage, stats hub

**Heatmap**:
A world map showing how densely one athlete's Activity routes overlap, limited to the Activities chosen by a Heatmap Filter. Activities without a route take no part in it.
_Avoid_: Route overlay, activity map, global heatmap, corridor map

**Heatmap Filter**:
The date range and set of sports that choose which Activities make up a Heatmap; a year is a shorthand for a date range, not a separate criterion.
_Avoid_: Heatmap query, layer filter

**Full Heat**:
How many overlapping Activities a Heatmap shows at its hottest colour, measured across the athlete's whole filtered Heatmap rather than the area in view; places used less often glow dimmer in proportion.
_Avoid_: Max brightness, normalisation, scale

**Starting View**:
Where the Heatmap opens when the athlete has no Remembered View: the busiest area of the routes the Heatmap Filter matches, or the whole world when it matches none. The busiest area is the 3×3 block of roughly city-sized grid cells holding the most matching routes, the more recently used winning a tie; routes outside it are left out of the view.
_Avoid_: Initial view, default view, home area

**Remembered View**:
The last area of the Heatmap an athlete looked at, kept in their browser and reopened on their next visit. It is kept only while the Heatmap has routes to show, so it never beats the busiest area with a view of the empty world.
_Avoid_: Saved view, last position, bookmark

**Map Perspective**:
Whether a map is seen flat from straight above (2D) or tilted towards the horizon with raised buildings (3D); it applies to both the Activity route map and the Heatmap, and every map opens in 2D. It is separate from where the map looks, so a Starting View or Remembered View holds no Map Perspective.
_Avoid_: 3D mode, map mode, view mode, map view, 3D view, tilt, pitch

### Running a server

**Server**:
One running installation of Stridehearth, with its own database and its own athletes, hosted by an Operator on hardware they control or on the public internet.
_Avoid_: Instance, deployment, node, site

**Operator**:
The person who installs, configures, upgrades and backs up a Server. They choose its settings, such as its Registration Mode, from outside the application.
_Avoid_: Host, admin, sysadmin, owner

**Administrator**:
The account on a Server allowed to manage that Server's other accounts, such as by issuing Invites; the first account ever registered on a Server becomes its Administrator, and a Server has at most one. On a Server upgraded from before Administrators existed, its earliest-registered account became the Administrator. The Administrator is often, but not necessarily, the same person as the Operator.
_Avoid_: Admin user, superuser, owner, root

**Invite**:
A single-use link the Administrator creates so one person can create their own account and password on the Server, even while its Registration Mode is closed. An Invite is pending until it is used, revoked by the Administrator, or expires 7 days after it was created; a link that is not pending is refused. The link is shown once, when it is created: the Server keeps only a hash of it.
_Avoid_: Invitation code, sign-up token, referral, guest pass

**Registration Mode**:
Whether a Server lets anyone create an account (open) or only allows the very first account to register itself (closed). Closed is the default, and a closed Server says so on its sign-in screen. On either, an Invite lets one more person in.
_Avoid_: Sign-up setting, invite-only mode, private mode, lockdown

**Throttle**:
A Server's refusal, for the rest of a short window, of further sign-in or sign-up attempts from one source (the client's network address) after too many in that window, and of further sign-ins against one username after too many fail. Only failed sign-ins count, so signing in often is never throttled; every sign-up attempt counts, accepted or refused, including each attempt to sign up with an Invite. It applies on every Server, whatever its Registration Mode.
_Avoid_: Lockout, ban, account lock, rate limit (in prose and UI copy)

### Releasing

**Release**:
A numbered, published version of Stridehearth that Operators can install, identified by a semantic version such as 1.4.0. Once published, a Release never changes.
_Avoid_: Prod build, deploy, drop, stable branch

**Edge Build**:
An unnumbered build of whatever is currently on the main line of development, published for trying changes before they reach a Release. It replaces itself whenever main moves and carries no upgrade promises.
_Avoid_: Nightly, dev build, snapshot, beta, prod

**One-way Release**:
A Release that changes the shape of a Server's database, so a Server that has run it cannot go back to an earlier Release except by restoring a backup taken before the upgrade.
_Avoid_: Breaking release, migration release, irreversible upgrade
