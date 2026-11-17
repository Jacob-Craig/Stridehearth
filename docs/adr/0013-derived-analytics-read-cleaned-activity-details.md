# 0013. Derived Analytics Read Cleaned Activity Details, Computed on Demand

Status: accepted.

Stored Activity Details stay exactly as the device recorded them. Derived analytics (Grade-Adjusted Pace and the metrics built on it) do not read them directly: they call Telemetry Cleaning, a pure function from Activity Details and sport to Cleaned Activity Details, every time they compute. Cleaned Activity Details are never stored and never shown in place of Activity Details. When the cleaning rules or an analytic's formula change, the derived data is recomputed from the stored details, the way Best Efforts are (ADR-0003); the Parser Version rises only when decoding the same activity file would produce different Activity Details or Laps (issues #26, #31).

Telemetry Cleaning rejects GPS spikes (a speed above a sport-keyed ceiling, or a distance step that implies one, goes backwards or takes no time), treats heart rate as missing when it reads 0, steps more than 30 bpm per second or tracks step rate within 3 bpm for 60 s or more (cadence-lock), interpolates speed and heart-rate gaps whose good neighbours are at most 10 s apart, and averages altitude over a centred 15 m window of cleaned distance. A distance spike removes only its own excess: the bad step is replaced by the distance the cleaned speed covers and later samples shift by the difference, so samples outside a spike keep their recorded step lengths.

## Considered Options

- **Clean during ingestion and store the result as the Activity Details**: Rejected. It destroys what the device recorded, so a better rule could never be applied to Activities already imported without their files, and every rule change would have to raise the Parser Version and Upgrade every Activity.
- **Store Cleaned Activity Details beside the Activity Details**: Rejected. It doubles the largest table for series that cost a few linear passes to rebuild, and adds a second copy to keep in step on every Upgrade, Backfill and sport edit.
- **Let each analytic do its own filtering**: Rejected. Grade-Adjusted Pace, Training Load, peaks and interval detection would each pick different thresholds and disagree about the same Activity.
- **Compare each distance sample with the last good one**: Rejected. A teleport offsets every later sample, so this would discard the rest of the Activity instead of one step.

## Consequences

- Any stored derived data must record which version of its algorithm produced it, so a change to Telemetry Cleaning can trigger a recompute; that version is separate from the Parser Version.
- Best Efforts and Personal Records keep scanning the recorded distance. Moving them to Cleaned Activity Details would silently change existing Personal Records, so it needs its own decision.
- An Activity with no GPS fix keeps its speed, distance and altitude as recorded; only the heart-rate rules apply to it.
- The speed ceilings are 8.5 m/s for a Run, 5 m/s for a Walk or Hike, 30 m/s for a Ride and 3 m/s for a Swim. A Workout has none, because it is the fallback for unrecognised sports.
- Stored cadence is strides per minute of one leg, so cadence-lock compares heart rate with twice the cadence plus its fractional part, and only for a Run, Walk or Hike.
- Accepted risk: a steady effort whose true heart rate sits within 3 bpm of step rate for a minute is discarded as cadence-lock.
- Speed gaps longer than 10 s stay missing, so consumers must handle missing samples instead of assuming a continuous series.
