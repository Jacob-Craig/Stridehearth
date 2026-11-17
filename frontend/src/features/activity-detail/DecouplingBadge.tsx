import type { RunMetricsDto } from '../../api/generated/types.gen';
import {
  MISSING,
  decouplingStatus,
  formatDecouplingPercent,
  formatDecouplingUnavailableReason,
} from '../../lib/format';

const STATUS_STYLES = {
  coupled: {
    label: 'Coupled',
    className: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900 dark:text-emerald-200',
  },
  drifting: {
    label: 'Drifting',
    className: 'bg-amber-100 text-amber-800 dark:bg-amber-900 dark:text-amber-200',
  },
} as const;

/**
 * A Run's **Aerobic Decoupling**: the percentage with a green "Coupled" badge below 5 % or an amber
 * "Drifting" badge at 5 % or more (the words carry the meaning, not only the colour), or a dash
 * with the reason it is unavailable.
 */
export function DecouplingBadge({
  runMetrics,
}: {
  readonly runMetrics: Pick<
    RunMetricsDto,
    'aerobicDecouplingPercent' | 'decouplingUnavailableReason'
  >;
}) {
  const { aerobicDecouplingPercent: percent, decouplingUnavailableReason: reason } = runMetrics;
  if (percent === null) {
    return (
      <>
        {MISSING}
        {reason && (
          <span className="mt-1 block text-xs font-medium text-slate-500 dark:text-slate-400">
            {formatDecouplingUnavailableReason(reason)}
          </span>
        )}
      </>
    );
  }
  const status = decouplingStatus(percent);
  const { label, className } = STATUS_STYLES[status];
  return (
    <span className="flex flex-wrap items-center gap-2">
      {formatDecouplingPercent(percent)}
      <span
        data-status={status}
        className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${className}`}
      >
        {label}
      </span>
    </span>
  );
}
