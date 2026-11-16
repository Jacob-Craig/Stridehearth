import type { ReactNode } from 'react';

export interface TableColumn {
  readonly label: string;
  /** Right-aligns the column, matching its {@link NumericCell}s. */
  readonly numeric?: boolean;
}

interface TableSectionProps {
  /** Id of the heading that names both the section and its table. */
  readonly headingId: string;
  readonly title: string;
  readonly description?: string;
  readonly columns: readonly TableColumn[];
  /** Extra classes for the section card, e.g. rendering hints for below-the-fold tables. */
  readonly className?: string;
  /** The table's body rows. */
  readonly children: ReactNode;
}

/** A titled card holding one horizontally scrollable data table of the Activity detail view. */
export function TableSection({
  headingId,
  title,
  description,
  columns,
  className,
  children,
}: TableSectionProps) {
  return (
    <section
      aria-labelledby={headingId}
      className={`rounded-lg border border-slate-200 dark:border-slate-800 bg-white dark:bg-slate-900 shadow-sm${className ? ` ${className}` : ''}`}
    >
      <header className="border-b border-slate-200 dark:border-slate-800 px-4 py-3">
        <h2 id={headingId} className="text-lg font-bold">
          {title}
        </h2>
        {description && <p className="text-sm text-slate-600 dark:text-slate-400">{description}</p>}
      </header>
      <div className="overflow-x-auto">
        <table aria-labelledby={headingId} className="w-full text-sm">
          <thead className="bg-slate-50 dark:bg-slate-800/50 text-left text-xs uppercase tracking-wide text-slate-500 dark:text-slate-400">
            <tr>
              {columns.map(({ label, numeric }) => (
                <th
                  key={label}
                  scope="col"
                  className={numeric ? 'px-4 py-3 text-right' : 'px-4 py-3'}
                >
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-200 dark:divide-slate-800">{children}</tbody>
        </table>
      </div>
    </section>
  );
}

/** A right-aligned, tabular-figure cell for a formatted measurement. */
export function NumericCell({ children }: { readonly children: string }) {
  return <td className="px-4 py-3 text-right tabular-nums">{children}</td>;
}
