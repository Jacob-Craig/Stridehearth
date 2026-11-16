import type { ReactNode } from 'react';

interface ErrorAlertProps {
  /** Headline for a page that could not be shown; omitted for an inline failure message. */
  readonly title?: string;
  readonly children: ReactNode;
}

/** An error announced to assistive technology (`role="alert"`): inline, or as a titled page-level box. */
export function ErrorAlert({ title, children }: ErrorAlertProps) {
  if (title === undefined) {
    return (
      <div
        role="alert"
        className="rounded-lg border border-red-200 dark:border-red-900 bg-red-50 dark:bg-red-950/50 p-3 text-sm text-red-600 dark:text-red-400"
      >
        {children}
      </div>
    );
  }
  return (
    <div
      role="alert"
      className="rounded-lg border border-red-200 dark:border-red-900 bg-red-50 dark:bg-red-950 p-4 text-red-700 dark:text-red-300"
    >
      <h1 className="font-bold">{title}</h1>
      <p className="mt-1 text-sm">{children}</p>
    </div>
  );
}
