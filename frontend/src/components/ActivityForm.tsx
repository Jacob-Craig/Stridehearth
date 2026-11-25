import { useForm } from '@tanstack/react-form';
import { type ReactNode, useState } from 'react';
import { match } from 'ts-pattern';
import {
  type ActivityFormErrors,
  type ActivityFormValues,
  type FormConversion,
  formErrorsFromApi,
  type ProtectedMetricFormField,
} from '../lib/activityForm';
import { ACTIVITY_SPORTS, type ActivitySport, formatSport } from '../lib/format';
import { getFieldError, parseApiError } from '../utils/error';
import { ErrorAlert } from './ErrorAlert';

interface MetricInput {
  readonly label: string;
  readonly type: 'text' | 'datetime-local';
  readonly inputMode?: 'decimal' | 'numeric';
  readonly placeholder?: string;
  readonly step?: number;
}

/**
 * Inputs for the **Protected Metrics**, which are read-only once an Activity has them. Keyed by form
 * field so adding a Protected Metric to the form values fails to compile until it has an input here.
 */
const METRIC_INPUTS: Readonly<Record<ProtectedMetricFormField, MetricInput>> = {
  startedAt: { label: 'Start time', type: 'datetime-local', step: 1 },
  distance: { label: 'Distance (km)', type: 'text', inputMode: 'decimal' },
  duration: { label: 'Duration (mm:ss or h:mm:ss)', type: 'text', placeholder: '45:00' },
  averagePaceKmh: { label: 'Average speed (km/h)', type: 'text', inputMode: 'decimal' },
  averageHeartRate: { label: 'Average heart rate (bpm)', type: 'text', inputMode: 'numeric' },
  calories: { label: 'Calories (kcal)', type: 'text', inputMode: 'numeric' },
};

const METRIC_FIELDS = Object.keys(METRIC_INPUTS) as readonly ProtectedMetricFormField[];

const inputClass =
  'w-full rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-800 px-3 py-2 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500 read-only:bg-slate-100 read-only:text-slate-500 dark:read-only:bg-slate-900 dark:read-only:text-slate-400';

interface ActivityFormProps<Request> {
  initialValues: ActivityFormValues;
  /** When true, the **Protected Metrics** are shown read-only and only title and sport are editable. */
  protectedMetricsLocked: boolean;
  submitLabel: string;
  /** Validates and converts the raw values into the request body. */
  convert: (values: ActivityFormValues) => FormConversion<Request>;
  /** Persists the request; a rejected `ApiError` is rendered against the form. */
  onSubmit: (request: Request) => Promise<void>;
}

export function ActivityForm<Request>({
  initialValues,
  protectedMetricsLocked,
  submitLabel,
  convert,
  onSubmit,
}: ActivityFormProps<Request>) {
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [serverErrors, setServerErrors] = useState<ActivityFormErrors>({});

  const form = useForm({
    defaultValues: initialValues,
    validators: {
      onSubmit: ({ value }) =>
        match(convert(value))
          .with({ kind: 'VALID' }, () => undefined)
          .with({ kind: 'INVALID' }, ({ fieldErrors }) => ({ fields: fieldErrors }))
          .exhaustive(),
    },
    onSubmit: async ({ value }) => {
      const result = convert(value);
      if (result.kind === 'INVALID') return;
      setGeneralError(null);
      setServerErrors({});
      try {
        await onSubmit(result.data);
      } catch (err: unknown) {
        const parsed = parseApiError(err);
        setGeneralError(parsed.generalError);
        setServerErrors(formErrorsFromApi(parsed.fieldErrors));
      }
    },
  });

  return (
    <form
      noValidate
      onSubmit={(e) => {
        e.preventDefault();
        e.stopPropagation();
        form.handleSubmit();
      }}
      className="space-y-4"
    >
      {generalError && <ErrorAlert>{generalError}</ErrorAlert>}

      <div className="grid gap-4 sm:grid-cols-2">
        <form.Field name="title">
          {(field) => (
            <FieldShell
              name={field.name}
              label="Title"
              error={getFieldError(serverErrors.title, field.state.meta.errors[0])}
            >
              {(describedBy, invalid) => (
                <input
                  id={field.name}
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(e) => field.handleChange(e.target.value)}
                  aria-invalid={invalid}
                  aria-describedby={describedBy}
                  className={inputClass}
                />
              )}
            </FieldShell>
          )}
        </form.Field>

        <form.Field name="sport">
          {(field) => (
            <FieldShell
              name={field.name}
              label="Sport"
              error={getFieldError(serverErrors.sport, field.state.meta.errors[0])}
            >
              {(describedBy, invalid) => (
                <select
                  id={field.name}
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(e) => field.handleChange(e.target.value as ActivitySport)}
                  aria-invalid={invalid}
                  aria-describedby={describedBy}
                  className={inputClass}
                >
                  {ACTIVITY_SPORTS.map((sport) => (
                    <option key={sport} value={sport}>
                      {formatSport(sport)}
                    </option>
                  ))}
                </select>
              )}
            </FieldShell>
          )}
        </form.Field>
      </div>

      <fieldset className="space-y-3">
        <legend className="text-sm font-semibold text-slate-700 dark:text-slate-300">
          Metrics
        </legend>
        {protectedMetricsLocked && (
          <p className="rounded-lg bg-amber-50 dark:bg-amber-950/40 p-3 text-sm text-amber-800 dark:text-amber-200">
            These Protected Metrics come from a recorded file or Activity Details and can't be
            edited. You can still change the title and sport.
          </p>
        )}
        <div className="grid gap-4 sm:grid-cols-2">
          {METRIC_FIELDS.map((name) => {
            const metric = METRIC_INPUTS[name];
            return (
              <form.Field key={name} name={name}>
                {(field) => (
                  <FieldShell
                    name={field.name}
                    label={metric.label}
                    error={getFieldError(serverErrors[name], field.state.meta.errors[0])}
                  >
                    {(describedBy, invalid) => (
                      <input
                        id={field.name}
                        name={field.name}
                        type={metric.type}
                        {...(metric.inputMode ? { inputMode: metric.inputMode } : {})}
                        {...(metric.placeholder ? { placeholder: metric.placeholder } : {})}
                        {...(metric.step ? { step: metric.step } : {})}
                        value={field.state.value}
                        readOnly={protectedMetricsLocked}
                        onBlur={field.handleBlur}
                        onChange={(e) => field.handleChange(e.target.value)}
                        aria-invalid={invalid}
                        aria-describedby={describedBy}
                        className={`${inputClass} tabular-nums`}
                      />
                    )}
                  </FieldShell>
                )}
              </form.Field>
            );
          })}
        </div>
      </fieldset>

      <form.Subscribe selector={(state) => state.isSubmitting}>
        {(isSubmitting) => (
          <button
            type="submit"
            disabled={isSubmitting}
            className="rounded-lg bg-blue-600 px-4 py-2.5 font-medium text-white shadow hover:bg-blue-700 disabled:opacity-50"
          >
            {isSubmitting ? 'Saving…' : submitLabel}
          </button>
        )}
      </form.Subscribe>
    </form>
  );
}

function FieldShell({
  name,
  label,
  error,
  children,
}: {
  name: string;
  label: string;
  error: string | undefined;
  children: (describedBy: string | undefined, invalid: boolean) => ReactNode;
}) {
  const errorId = `${name}-error`;
  return (
    <div>
      <label
        htmlFor={name}
        className="mb-1 block text-sm font-medium text-slate-700 dark:text-slate-300"
      >
        {label}
      </label>
      {children(error ? errorId : undefined, Boolean(error))}
      {error && (
        <p id={errorId} className="mt-1 text-xs text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  );
}
