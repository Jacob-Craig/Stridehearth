import { useId, useState } from 'react';
import type { ImportBatchSummaryDto } from '../../api/generated/types.gen';
import { describeItemUpgrades } from '../../lib/activityChange';
import { countOf } from './countOf';

export interface ItemOutcome {
  readonly filename: string;
  readonly message: string;
}

interface ItemOutcomeDisclosureProps {
  readonly summary: string;
  readonly outcomes: readonly ItemOutcome[];
  readonly tone: 'error' | 'upgrade';
}

/**
 * Collapsible per-file **Import Batch Item** outcomes (errors or **Parser Version** upgrades), using the
 * WAI-ARIA disclosure pattern: a button toggling `aria-expanded` over the region it controls.
 */
export function ItemOutcomeDisclosure({ summary, outcomes, tone }: ItemOutcomeDisclosureProps) {
  const [expanded, setExpanded] = useState(false);
  const panelId = useId();
  if (outcomes.length === 0) return null;

  const toneClass =
    tone === 'error' ? 'text-red-700 dark:text-red-300' : 'text-indigo-700 dark:text-indigo-300';

  return (
    <div>
      <button
        type="button"
        aria-expanded={expanded}
        aria-controls={panelId}
        onClick={() => setExpanded((open) => !open)}
        className={`text-sm font-semibold underline-offset-2 hover:underline ${toneClass}`}
      >
        <span aria-hidden="true">{expanded ? '▾' : '▸'} </span>
        {summary}
      </button>
      <ul id={panelId} hidden={!expanded} className="mt-1 space-y-0.5 pl-4 text-sm">
        {expanded &&
          outcomes.map((outcome, index) => (
            // biome-ignore lint/suspicious/noArrayIndexKey: filenames can repeat within a Batch and the list never reorders
            <li key={`${outcome.filename}-${index}`}>
              <span className="font-medium">{outcome.filename}</span>
              <span className="text-slate-500 dark:text-slate-400">: </span>
              <span className="text-slate-600 dark:text-slate-300">{outcome.message}</span>
            </li>
          ))}
      </ul>
    </div>
  );
}

/** The per-file errors and **Parser Version** upgrades of one **Import Batch**, each as a disclosure. */
export function ImportBatchItemOutcomes({ batch }: { batch: ImportBatchSummaryDto }) {
  return (
    <div className="space-y-1">
      <ItemOutcomeDisclosure
        tone="error"
        summary={countOf(batch.itemErrors.length, 'file error')}
        outcomes={batch.itemErrors.map((e) => ({ filename: e.filename, message: e.errorMessage }))}
      />
      <ItemOutcomeDisclosure
        tone="upgrade"
        summary={countOf(batch.itemUpgrades.length, 'Parser Version upgrade')}
        outcomes={batch.itemUpgrades.map((u) => ({
          filename: u.filename,
          message: describeItemUpgrades(u.changes),
        }))}
      />
    </div>
  );
}
