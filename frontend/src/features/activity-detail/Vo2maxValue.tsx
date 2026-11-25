import { Link } from '@tanstack/react-router';
import type { RunMetricsDto } from '../../api/generated/types.gen';
import { MISSING, formatVo2max, formatVo2maxUnavailableReason } from '../../lib/format';

const NOTE_CLASS = 'mt-1 block text-xs font-medium text-slate-500 dark:text-slate-400';

/**
 * A Run's **VO2max Estimate** in ml/kg/min: the value times its day's **Correction Factor**, with the
 * uncorrected value in a tooltip; the uncorrected value, labelled, until the Correction Factor is known;
 * or a dash with the reason there is none, linking to the settings page when a heart-rate Threshold is
 * missing.
 */
export function Vo2maxValue({
  runMetrics,
}: {
  readonly runMetrics: Pick<
    RunMetricsDto,
    'vo2maxEstimate' | 'vo2maxEstimateRaw' | 'vo2maxUnavailableReason'
  >;
}) {
  const {
    vo2maxEstimate: corrected,
    vo2maxEstimateRaw: raw,
    vo2maxUnavailableReason: reason,
  } = runMetrics;
  if (corrected != null && raw != null) {
    return (
      <span title={`Before the Correction Factor: ${formatVo2max(raw)} ml/kg/min`}>
        {formatVo2max(corrected)}
      </span>
    );
  }
  if (raw != null) {
    return (
      <>
        {formatVo2max(raw)}
        <span className={NOTE_CLASS}>Uncorrected</span>
      </>
    );
  }
  return (
    <>
      {MISSING}
      {reason === 'NO_HEART_RATE_THRESHOLDS' ? (
        <Link
          to="/settings"
          className={`${NOTE_CLASS} underline hover:text-slate-700 dark:hover:text-slate-200`}
        >
          {formatVo2maxUnavailableReason(reason)}
        </Link>
      ) : (
        reason && <span className={NOTE_CLASS}>{formatVo2maxUnavailableReason(reason)}</span>
      )}
    </>
  );
}
