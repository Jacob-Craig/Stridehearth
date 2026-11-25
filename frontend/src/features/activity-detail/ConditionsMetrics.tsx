import type { ReactNode } from 'react';
import type { ActivityConditionsDto } from '../../api/generated/types.gen';
import { formatAltitudeM, formatStaminaPercent, formatTemperatureC } from '../../lib/format';

interface Tile {
  readonly label: string;
  readonly value: ReactNode;
  readonly note?: string;
}

/**
 * The heat and altitude an Activity was done in, as their own group of tiles after the summary
 * metrics: the **Ambient Temperature** with the watch reading and **Wrist-Heat Correction** it
 * came from, the **Mean Altitude**, and the athlete's **Heat Acclimation** and **Altitude
 * Acclimation** on the day while above 0. A value that is unknown has no tile, and with no tile
 * the group is not rendered.
 */
export function ConditionsMetrics({ conditions }: { conditions: ActivityConditionsDto | null }) {
  if (!conditions) return null;
  const {
    deviceTemperatureC,
    ambientTemperatureC,
    wristHeatCorrectionC,
    meanAltitudeM,
    heatAcclimationPct,
    altitudeAcclimationPct,
  } = conditions;
  const tiles: Tile[] = [];
  if (ambientTemperatureC != null && deviceTemperatureC != null) {
    tiles.push({
      label: 'Temperature',
      value: `≈${formatTemperatureC(ambientTemperatureC)}`,
      note: `watch ${formatTemperatureC(deviceTemperatureC)}, −${formatTemperatureC(wristHeatCorrectionC)} wrist correction`,
    });
  }
  if (meanAltitudeM != null)
    tiles.push({ label: 'Avg Altitude', value: formatAltitudeM(meanAltitudeM) });
  if (heatAcclimationPct != null && heatAcclimationPct > 0) {
    tiles.push({ label: 'Heat Acclimation', value: formatStaminaPercent(heatAcclimationPct) });
  }
  if (altitudeAcclimationPct != null && altitudeAcclimationPct > 0) {
    tiles.push({
      label: 'Altitude Acclimation',
      value: formatStaminaPercent(altitudeAcclimationPct),
    });
  }
  if (tiles.length === 0) return null;

  return (
    <section aria-labelledby="conditions-heading" className="space-y-2">
      <h2
        id="conditions-heading"
        className="text-sm font-semibold uppercase tracking-wide text-slate-500 dark:text-slate-400"
      >
        Conditions
      </h2>
      <ul aria-label="Conditions" className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
        {tiles.map(({ label, value, note }) => (
          <li
            key={label}
            className="rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 p-4 shadow-sm"
          >
            <span className="block text-xs font-medium uppercase tracking-wide text-slate-500 dark:text-slate-400">
              {label}
            </span>
            <span className="mt-1 block text-xl font-bold tabular-nums">{value}</span>
            {note && (
              <span className="mt-1 block text-xs text-slate-500 dark:text-slate-400 tabular-nums">
                {note}
              </span>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}
