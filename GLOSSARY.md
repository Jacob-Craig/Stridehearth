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
Filling in something an Activity lacks (Activity Details, their distance, Laps, Best Efforts, or derived data such as Grade-Adjusted Pace, Normalized Graded Pace, Peak Efforts, Training Load, VO2max Estimate or Ambient Temperature) without replacing anything it already has. Replacing derived data that is out of date is a Recompute.
_Avoid_: Repair, fix-up

**Recompute (derived data)**:
Replacing an Activity's stored derived data (such as its Grade-Adjusted Pace, Run metrics like Normalized Graded Pace, Peak Efforts, Training Load, VO2max Estimate, or Ambient Temperature and Mean Altitude) because something it was computed from changed: its Activity Details, its sport or start time, for a manual Activity its Protected Metrics, the athlete's Thresholds or Athlete Time Zone, or the version of the algorithm that computed it. It never changes the Activity itself, so it leaves no Activity History Entry.
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

**Personal Record Progression**:
The Personal Records an athlete has held over time for one Benchmark Distance: every Best Effort that was faster than all Best Efforts from Activities started before it, oldest first. Its last step is the current Personal Record.
It is worked out from the stored Best Efforts whenever it is read, so the order Activities were imported in, and deleted Activities, never leave it wrong.
_Avoid_: PR history, record timeline

**Benchmark Duration**:
A standard window length (5 s, 15 s, 30 s, 1, 2, 3, 5, 10, 20, 30 or 60 minutes) over which Peak Efforts are measured. It is to Peak Efforts what a Benchmark Distance is to Best Efforts.
_Avoid_: Duration benchmark, time interval

**Peak Effort**:
The highest average Grade-Adjusted speed, or power, over one continuous window of a Benchmark Duration within a single Run, read from its Cleaned Activity Details (power as recorded, except that a sample above 1,500 W is a glitch and counts as missing) on wall-clock time: recording pauses count as standing still, so a GPS spike or a pause can never produce one.
It is not a Best Effort and never sets a Personal Record. Derived, so recomputed, never edited.
_Avoid_: Duration best effort, peak, power PR, mean-maximal

**Critical Speed (CS)**:
The highest running speed an athlete can sustain without steadily using up their D′, in m/s: the slope of the 2-parameter model `D(t) = CS·t + D′`, fitted by least squares to the distances of their best Grade-Adjusted Peak Efforts at the 2, 3, 5, 10 and 20 minute Benchmark Durations from Runs on the 90 days up to a date (that day included, in their Athlete Time Zone).
No model is published (it records why instead) with fewer than 3 such Peak Efforts, an R² below 0.9, a non-positive CS, or a D′ under 100 m (which comes from sub-maximal efforts, such as one steady easy run, whose near-straight distance-time line fits with a high R² anyway). A model is kept for every day the athlete has a Run and refitted whenever a Peak Effort in its window changes. When it differs from the threshold pace in force by more than 3% (of the threshold, as a speed) it makes a Threshold Proposal; it never changes a Threshold itself. Derived, so recomputed, never edited.
_Avoid_: Threshold pace (that is the Threshold the athlete records), FTP, critical pace

**D′ (D prime)**:
The distance, in metres, an athlete can cover above Critical Speed before they must slow down: the intercept of the same fit. It is spent while running faster than CS and recovers below it.
_Avoid_: Anaerobic capacity, W′ (for running speed), reserve

**Stamina**:
How much of their D′ an athlete has left at each moment of a Run, as a percentage of D′ (100% full, 0% empty), by Skiba's D′ balance (D′bal). Above Critical Speed (on Grade-Adjusted speed) it is spent metre for metre; at or below CS it recovers towards full, faster the further below CS, with time constant `τ = D′ / (CS − speed)`. A recording pause or a missing speed recovers as standing still.
It is measured against the Critical Speed model in effect on the Run's own date (its own Peak Efforts included), and a Run has none when there is no model on that date. Computed on every read, never stored or edited. Its lowest value is the Run's **Min Stamina**.
_Avoid_: D′bal (the balance in metres), W′bal (for power), fatigue, energy

**Critical Power (CP)**:
Critical Speed's counterpart for running power, in watts, fitted the same way from power Peak Efforts (work `P·t` against time) when the athlete has any in the window. No model is published with a W′ under 5 kJ, D′'s 100 m floor for power. It proposes the critical power Threshold the same way.
_Avoid_: FTP, threshold power

**W′ (W prime)**:
D′'s counterpart for power: the work, in joules, an athlete can do above Critical Power, the intercept of the power fit.
_Avoid_: Anaerobic work capacity, D′ (for power)

**Pace-Duration Curve**:
An athlete's best Peak Efforts plotted against their Benchmark Durations (5 s to 60 minutes, on a log scale): one curve for the last 90 days and one for all time, with their current Critical Speed model `CS + D′/t` drawn from 2 minutes up. When they have power Peak Efforts it can show power instead, with the Critical Power model. Each point opens the Activity that set it. Worked out from the stored Peak Efforts whenever it is read.
_Avoid_: Power-duration curve (for pace), mean-maximal curve, PDC (in the UI)

**Telemetry Cleaning**:
The pass that produces Cleaned Activity Details from an Activity's Activity Details and sport: it rejects GPS spikes (speeds above the sport's ceiling and distance jumps), treats heart rate as missing during dropouts and cadence-lock (heart rate that sits at step rate and follows it, not a true heart rate that merely matches it), interpolates gaps of up to 10 seconds, and smooths altitude over a 15 m distance window.
It never changes the stored Activity Details, and an Activity with no GPS fix keeps its speed, distance and altitude exactly as recorded. Temperature is not cleaned.
_Avoid_: Filtering, sanitising, data correction

**Cleaned Activity Details**:
Speed, distance, heart-rate and altitude series computed from an Activity's Activity Details by Telemetry Cleaning, with GPS spikes, heart-rate dropouts and altitude noise removed.
They are never stored and never shown in place of Activity Details; derived analytics read them. Best Efforts are the exception: they are measured on the recorded distance.
_Avoid_: Filtered telemetry, corrected data, cleaned streams

**Ambient Temperature**:
The air temperature an Activity was done in, as Stridehearth estimates it: the median of the temperatures the device recorded (when at least 60 samples cover at least half the Activity) less the Wrist-Heat Correction. It comes only from the Activity's own file (ADR-0017). Derived, so recomputed, never edited.
_Avoid_: Weather, air temperature, device temperature

**Wrist-Heat Correction**:
The 6 °C subtracted from a device's temperature to estimate the Ambient Temperature, because a watch worn on the wrist reads warmer than the air. Where a device is worn is never recorded, so it applies to every Activity.
_Avoid_: Temperature offset, body-heat adjustment

**Mean Altitude**:
The mean of an Activity's cleaned altitude, in metres, once it has at least 60 samples. Derived, so recomputed, never edited.
_Avoid_: Average elevation, altitude

**Grade-Adjusted Pace (GAP)**:
The pace an athlete would have held on flat ground for the same effort, computed per sample from Cleaned Activity Details and the gradient (Minetti's cost of running, grade clamped to ±45%), for Runs, Walks and Hikes.
The gradient is measured over about 180 m of distance, so barometer steps and ordinary altitude noise barely make hills on flat ground (very noisy GPS altitude still biases it slightly upwards; see ADR-0013), at the cost of spreading a short climb over a longer stretch.
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
It is only reported for a steady state: at least 30 minutes of Moving Time and a 30-second rolling Grade-Adjusted Pace that varies by less than 12% (coefficient of variation). Otherwise it is absent with the reason (no pace data, no heart rate, too short, heart rate missing in one half, not steady). Derived, so recomputed, never edited.
_Avoid_: Cardiac drift, Pa:HR, heart-rate drift

**Threshold**:
A physiological reference value an athlete records with the date it took effect: max HR, resting HR and lactate-threshold heart rate (LTHR) in bpm, threshold pace (kept as a speed, shown as a pace per km) and critical power in watts. Each kind keeps its own dated history; the value in force on a date is the latest entry on or before it, and before a kind's first entry that entry applies backwards. Dates are the athlete's local dates (Athlete Time Zone). Changing a Threshold recomputes the derived data that depends on it from its date onwards.
_Avoid_: FTP setting, profile value

**Zone**:
An intensity band derived from a Threshold: five heart-rate Zones as percentages of LTHR and five pace Zones as percentages of threshold pace (Friel's running zones), each also grouped into Seiler's three (Zones 1-2 below the first threshold, 3-4 between the thresholds, 5 above the second).
_Avoid_: Level, bucket

**Threshold Proposal**:
A value another feature (such as a Critical Speed fit) suggests for one of an athlete's Thresholds. It never takes effect on its own: it becomes a Threshold entry only when the athlete accepts it, and a dismissed one is not suggested again for a value within 3% of it.
_Avoid_: Auto-update, recommendation

**Athlete Time Zone**:
The time zone an athlete's calendar days are counted in, such as the day an Activity falls on for the Thresholds in force then. Set from the athlete's browser when they first sign in, changeable in Settings, and UTC until set.
_Avoid_: Locale, timezone offset

**Training Load**:
One number per Activity for how much physiological stress it imposed, scaled so one hour at threshold is 100, computed by the first Training Load Method whose data and Threshold the Activity has, against the Thresholds in force on its date.
An Activity with none (no Activity Details, no usable stream, a missing Threshold, or heart-rate Thresholds that contradict each other, such as a max heart rate not above resting) has no Training Load and records why. Derived, so recomputed, never edited.
_Avoid_: TSS, stress score, effort, load score

**Training Load Method**:
How a Training Load was computed, tried in this order: RSS (Running Stress Score, from power against critical power, for Runs, Walks and Hikes), rTSS (from Normalized Graded Pace against threshold pace over Moving Time, for Runs) and HRSS (Heart Rate Stress Score, Banister's TRIMP over heart rate normalised to one hour at LTHR, for any sport). A power or heart-rate stream is used only when it covers at least 80% of the recorded time.
_Avoid_: Load type, scoring model

**Daily Load**:
The sum of the Training Loads of an athlete's Activities on one of their calendar days (in their Athlete Time Zone). A day without Activities has a Daily Load of 0, and an Activity without a Training Load contributes 0 and is counted as unscored.
_Avoid_: Daily TSS, daily stress

**Fitness (CTL)**:
Chronic Training Load: the exponentially weighted moving average of Daily Load with a 42-day time constant, starting from 0 the day before the athlete's first scored day. UI copy says "Fitness (CTL)".
_Avoid_: Form, chronic load (for this number)

**Fatigue (ATL)**:
Acute Training Load: the exponentially weighted moving average of Daily Load with a 7-day time constant, starting like Fitness. UI copy says "Fatigue (ATL)".
_Avoid_: Tiredness, acute load (for this number)

**Form (TSB)**:
Training Stress Balance: yesterday's Fitness minus yesterday's Fatigue, so a day's Form describes readiness before that day's training. 0 on the first scored day. UI copy says "Form (TSB)".
_Avoid_: Freshness score, readiness

**Form Zone**:
One of five half-open bands of Form that the Performance Management Chart shades and names: High Risk (below -30), Optimal (-30 up to -10), Grey (-10 up to 5), Fresh (5 up to 25) and Transition (25 and above). Each boundary belongs to the zone above it.
_Avoid_: Form band, readiness zone, training zone

**Acute:Chronic Workload Ratio (ACWR)**:
The mean Daily Load of the last 7 days divided by the mean Daily Load of the 28 days before them (uncoupled: the acute week is not part of the chronic window). Load-based, never distance-based. Withheld for the first 34 days after the first scored day and when the chronic window holds no load.
_Avoid_: Workload ratio, ACR, coupled ACWR

**ACWR Corridor**:
The ACWR range from 0.80 to 1.30, both ends included, shaded on the Performance Management Chart as the range in which load is rising no faster than the athlete is adapted to.
_Avoid_: Sweet spot, safe zone

**Monotony**:
Foster's measure of how little Daily Load varies: the mean of the last 7 days' Daily Loads divided by their sample standard deviation. Withheld for the first 6 days after the first scored day and undefined for a week of equal loads.
_Avoid_: Variability, training variety

**Strain**:
Foster's weekly strain: the sum of the last 7 days' Daily Loads times their Monotony. Withheld and undefined whenever Monotony is.
_Avoid_: Weekly load, stress

**Performance Management Chart (PMC)**:
An athlete's daily series of Daily Load, Fitness, Fatigue, Form, ACWR, Monotony and Strain, one entry per day from their first scored day to today. Derived and rebuilt from the earliest affected day whenever an Activity's Training Load or day changes, never edited.
_Avoid_: Fitness chart, training graph

**Heat Acclimation**:
How adapted an athlete is to heat, 0–100 %, at the end of each athlete-local day: a day with an Activity of at least 20 minutes above 22 °C Ambient Temperature adds 100/14 points, any other day takes away 100/21, so 14 exposed days build it fully and 21 days without exposure lose it all. Derived and rebuilt from the earliest affected day, never edited.
_Avoid_: Heat adaptation, heat tolerance

**Altitude Acclimation**:
Heat Acclimation's counterpart for altitude: a day counts as exposed when it has an Activity of at least 20 minutes above 800 m Mean Altitude. Tracked separately from Heat Acclimation.
_Avoid_: Altitude adaptation

**Heat Derating**:
How much slower the same effort runs in the heat: running time multiplied by 1 + 0.003 per °C of Ambient Temperature above 20 °C (1.5% per 5 °C). It uses temperature only, and acclimation does not change it yet. Predictions such as Race Prognosis and VO2max apply it.
_Avoid_: Heat penalty, heat adjustment

**VDOT**:
Jack Daniels' measure of running ability in ml/kg/min: the oxygen cost of a race pace (Daniels-Gilbert equation) divided by the share of VO2max sustainable for the race's duration. A 19:57 5K is VDOT 50. Read from a race result, or from it to a predicted time.
_Avoid_: VO2max (for a race's VDOT), running index

**VO2max Estimate**:
A Run's estimate of the VO2max it reflects, in ml/kg/min: over the steady part of its Moving Time (as for Aerobic Decoupling), each minute's Grade-Adjusted Pace, raised to its cool-weather equivalent by the Heat Derating of its Ambient Temperature, is turned into its oxygen cost on Daniels' curve and set against its share of heart-rate reserve from the resting and max heart-rate Thresholds; the line through rest at 3.5 is extended to 100 %, and raised to its sea-level value for the Run's Mean Altitude. Because it uses Daniels' curve, it is on the VDOT scale. Stored uncorrected; shown times its day's Correction Factor.
A Run has none, and records why, without Grade-Adjusted Pace, without both heart-rate Thresholds (or under 20 bpm apart), with heart rate on less than 80% of its Moving Time, under 20 minutes, with more than 20 m of climb per km, when its pace is not steady, below 50% of heart-rate reserve, with fewer than 10 usable minutes, or outside 20-90. Depends on the Thresholds, so recomputed with the Training Load, never edited.
_Avoid_: VO2max score, fitness level

**Correction Factor**:
What an athlete's VO2max Estimates are multiplied by so they agree with their racing: the VDOT of their best race of the last 365 days divided by their uncorrected 30-day VO2max mean on that race's day, kept between 0.8 and 1.2; 1.0 without a race. A race is a Run whose 5K, 10K or Half-Marathon Best Effort covers at least 90% of its distance, so it needs no estimate of its own. Calibrated automatically; it cannot be set by hand yet.
_Avoid_: Calibration factor, adjustment

**Effective VO2max**:
An athlete's VO2max on one of their days: the mean of the VO2max Estimates of their Runs on the 30 days ending that day, weighted by Moving Time, times that day's Correction Factor. Absent when none of those Runs has an estimate. Derived and rebuilt from the earliest affected day, never edited.
_Avoid_: VO2max score, fitness level, current VO2max

**Marathon Shape**:
How ready an athlete's training is for a marathon on one of their days, 0-100%: two thirds Volume Score and one third Long-Run Score, both measured against targets set by that day's Effective VO2max (after Runalyze). Absent without an Effective VO2max. Derived and rebuilt from the earliest affected day, never edited.
_Avoid_: Endurance score, marathon readiness

**Volume Score**:
The volume part of Marathon Shape, 0-1: the recorded distance of the athlete's Runs (manual ones included) over the 26 weeks ending that day, per week (over the weeks since their first Run when that is shorter, but never fewer than 4), against a weekly target of Effective VO2max^1.135 km. Capped at 1.
_Avoid_: Mileage score

**Long-Run Score**:
The long-run part of Marathon Shape, 0-1: each Run of at least 13 km on the 70 days ending that day scores ((distance − 13 km) / (target long run − 13 km))², at most 2, counting less the older it is (1 − age/70); the target long run is 12·ln(Effective VO2max / 4) − 6 km, at least 15 km. The sum against 5.5, what one target long run a week for 10 weeks scores, capped at 1.
_Avoid_: Long-run index

**Race Prognosis**:
An athlete's predicted finish times for the 5K, 10K, Half Marathon and Marathon on one of their days. The base time is what Daniels' VDOT tables give for that day's Effective VO2max. An endurance penalty lengthens it as Marathon Shape falls below 100%, growing with distance: nothing for the 5K, up to +20% for the Marathon at 0% (1 + 0.20 · (distance − 5 km) / 37.195 km · (1 − Marathon Shape / 100)). Today's can also take Course Derating. Absent without an Effective VO2max. Computed from the stored daily values whenever it is read, never stored or edited.
_Avoid_: Race predictor, race time estimate

**Course Derating**:
How much slower race-day conditions make a Race Prognosis: its time multiplied by the Heat Derating of the race-day temperature, and by 1 + 4 × the course's total climb / its distance (on gentle grades a metre of climb costs about 6 m of flat running and a metre of descent gives back about 2, for a course that finishes where it starts). Applied to today's prognosis only when the athlete enters conditions, never to the daily trend.
_Avoid_: Course adjustment, course correction

**Race Split Plan**:
A target time for every Split (each km or mile from the start, the last one partial) over an existing Activity's route that holds Grade-Adjusted effort even and sums exactly to a Goal Time. Each stretch of the Cleaned Activity Details takes time in proportion to its distance times Minetti's cost ratio at its grade (the inverse of Grade-Adjusted Pace), so climbs are slower; a descent is never planned more than 15% faster than the flat (a cost ratio of at least 0.85, which binds on anything steeper than about −3%). A Split Bias then tilts it. Only for a Run, Walk or Hike with a GPS route. Computed whenever it is asked for, never stored.
_Avoid_: PacePro, pace plan, pacing strategy

**Goal Time**:
The finish time a Race Split Plan's Splits add up to, from one minute to a day. It starts at the Activity's own time; "Use predicted time" takes the Race Prognosis of the race distance within 10% of the course's length, Course Derated for the course's climb and scaled to its length by Riegel's formula (time × (course / race distance)^1.06).
_Avoid_: Target time, race goal

**Split Bias**:
How much longer, in percent from −5 to +5, a Race Split Plan's second half takes than its first on even ground: positive is a positive split (slowing down), negative a negative split. The effort ramps evenly over the course, and the Goal Time is unchanged.
_Avoid_: Pacing bias, fade

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
