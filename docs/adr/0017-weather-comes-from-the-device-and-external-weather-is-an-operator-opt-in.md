# 0017. Weather Comes from the Device; External Weather Is an Operator Opt-In

Status: accepted. External weather: issue #83.

Race Prognosis and VO2max adjust for heat and altitude, so every Activity needs the temperature and altitude it was done in (issue #43). Two sources exist: what the device recorded in the FIT file (`record.temperature`, stored in `activity_details.temperature` since V5, and altitude), and an external weather service looked up at the Activity's start coordinates and time, such as the Open-Meteo historical (ERA5) archive. A lookup sends the athlete's location and the time they were there to a third party, and Stridehearth otherwise makes no outbound call at all.

We take the conditions **from the device only**, and ship **no weather provider**:

- The derived-data refresh (ADR-0014) stores three values per Activity in `activity_derived_metrics`: `device_temperature_c`, the median of the recorded temperature samples (at least 60, covering at least half the Activity); `ambient_temperature_c`, that less a **Wrist-Heat Correction** of 6 °C; and `mean_altitude_m`, the mean of the cleaned altitude (ADR-0013). Each is NULL when the details cannot support it. Temperature is not cleaned: the median already resists a watch warming up at the start or a spell indoors.
- The correction applies to **every** Activity. Neither the parser nor any table records the device or where it is worn (the FIT `file_id` manufacturer and product only feed `external_id`), so "wrist-worn or unknown" is always "unknown". It is a code constant, not a setting: changing it raises `DERIVED_DATA_VERSION`, so stored values and the acclimation built from them never disagree with the constant in force.
- **Heat Acclimation** and **Altitude Acclimation** are an athlete rollup (`athlete_acclimation_daily`, ADR-0016) over the stored values: an athlete-local day with an Activity of at least 20 minutes above 22 °C ambient (or 800 m mean altitude) adds 100/14 points, any other day takes away 100/21, clamped to 0–100.
- **Heat Derating** (`HeatAltitude.heatPaceFactor`, 1.5% per 5 °C above 20 °C) uses temperature only, because the device records no humidity. Acclimation is shown but does not change it yet.
- No class in `src/main` refers to an HTTP client, and the application context holds none; `NoOutboundWeatherCallsTest` keeps it that way.

External weather (issue #83, built after this ADR was first accepted) is an **Operator opt-in** with this design:

- An Operator setting `stridehearth.weather.provider`, default `none`; `open-meteo` uses the ERA5 archive API. Athletes cannot turn it on for themselves, and nothing is sent while it is `none`.
- Coordinates are rounded to 0.1° (about 11 km) before they leave the Server, and only the start point and hour are sent, never the route.
- Lookups run in their own background stage **after commit**, never inside `DerivedDataRefresh`'s transaction: network latency, rate limits and ERA5's roughly five-day lag must not hold the athlete's row locks or fail an import.
- Results are cached per Activity in an `activity_weather` table (temperature, dew point, humidity and wind), so a lookup is made once and a WBGT or temperature-plus-dew-point derating can later replace the temperature-only one. The device values stay the default whenever no lookup exists.

Decisions made building #83:

- A looked-up temperature is the Ambient Temperature **only for Activities without a device temperature**. A device temperature always wins, and the Activities that have one are never looked up (so less leaves the Server). The weather is air temperature, so it takes no Wrist-Heat Correction.
- Only Activities with a GPS start point and derived data of the current version are looked up, oldest first, up to 100 per minute; the first failure ends the run and the Activity is retried at the next one. An Activity started within `stridehearth.weather.archive-lag` (default 6 days) is not asked for. A lookup the archive answers with no value is still cached, so it is not repeated.
- The request carries the rounded start point and the UTC date of the start (`start_date` = `end_date`); the start hour is picked from the answer on the Server, so the hour never leaves it.
- Caching a result refreshes that Activity's derived data in the same short transaction ([DerivedDataRefresh] reads `activity_weather`), which stores the new Ambient Temperature and marks the athlete's rollups, so Heat Derating and Heat Acclimation follow through the ADR-0014 path. `DERIVED_DATA_VERSION` and `ROLLUP_VERSION` are not raised: while the provider is `none` the cache is empty and every stored value is unchanged. An Operator who enables the provider after importing gets the values as the stage caches each Activity.
- The HTTP client (`RestClient`) lives only in the `weather` package and is a bean only for `open-meteo`; `NoOutboundWeatherCallsTest` asserts that for the default.

## Considered Options

- **External weather first, the device as a fallback** (the research notes' preference): Rejected. It would make every athlete's location leave the Server by default, and it needs network I/O on the import path. Epic #29 decided device first.
- **Build the Open-Meteo provider now, off by default**: Rejected for this issue. It is a separable, Operator-facing feature with its own failure modes, and shipping no client makes "no outbound calls unless enabled" true by construction.
- **A per-athlete or Operator setting for the wrist correction**: Deferred. A runtime setting would silently make stored values inconsistent with the correction in force unless it also triggered a recompute; a constant keeps one recompute path (the version bump).
- **The mean device temperature**: Rejected. A watch reads warm for its first minutes and indoors; the median ignores both.

## Consequences

- A chest strap or a footpod's temperature is corrected as if it were on a wrist: such an Activity's Ambient Temperature reads about 6 °C low.
- An Activity whose device records no temperature has no Ambient Temperature, counts as no heat exposure, and gets no Heat Derating, unless an Operator enables a provider and its weather is cached (#83).
- Changing the correction, the median rule or the altitude mean raises `DERIVED_DATA_VERSION` and, because acclimation is built from the stored values, `ROLLUP_VERSION`.
