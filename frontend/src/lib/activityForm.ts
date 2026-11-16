/**
 * Pure conversion between the **Activity** form's raw text inputs and the API request bodies.
 * Converted requests are validated against the generated (server-derived) Zod schemas so the client
 * and server share one contract.
 */
import { z } from 'zod';
import type {
  ActivityCreateRequestDto,
  ActivitySummaryDto,
  ActivityUpdateRequestDto,
} from '../api/generated/types.gen';
import { zActivityCreateRequestDto, zActivityUpdateRequestDto } from '../api/generated/zod.gen';
import { type ActivitySport, formatDuration, pad2 } from './format';

/** Raw form inputs; `startedAt` is a `datetime-local` value in the viewer's time zone. */
export interface ActivityFormValues {
  readonly title: string;
  readonly sport: ActivitySport;
  readonly startedAt: string;
  readonly distance: string;
  readonly duration: string;
  readonly averagePaceKmh: string;
  readonly averageHeartRate: string;
  readonly calories: string;
}

export type ActivityFormField = keyof ActivityFormValues;
/** The form fields holding **Protected Metrics**, which are read-only once an Activity has them. */
export type ProtectedMetricFormField = Exclude<ActivityFormField, 'title' | 'sport'>;
export type ActivityFormErrors = Partial<Record<ActivityFormField, string>>;

export type FormConversion<T> =
  | { readonly kind: 'VALID'; readonly data: T }
  | { readonly kind: 'INVALID'; readonly fieldErrors: ActivityFormErrors };

export function emptyActivityFormValues(): ActivityFormValues {
  return {
    title: '',
    sport: 'RUN',
    startedAt: '',
    distance: '',
    duration: '',
    averagePaceKmh: '',
    averageHeartRate: '',
    calories: '',
  };
}

/**
 * Formats an ISO instant as a `datetime-local` input value in the viewer's time zone, keeping its
 * seconds so an edit that leaves the start time alone sends it back unchanged.
 */
function toDateTimeLocal(iso: string): string {
  const d = new Date(iso);
  const date = `${d.getFullYear()}-${pad2(d.getMonth() + 1)}-${pad2(d.getDate())}`;
  return `${date}T${pad2(d.getHours())}:${pad2(d.getMinutes())}:${pad2(d.getSeconds())}`;
}

const optionalText = (value: number | null): string => (value === null ? '' : String(value));

export function formValuesFromActivity(activity: ActivitySummaryDto): ActivityFormValues {
  return {
    title: activity.title,
    sport: activity.sport,
    startedAt: toDateTimeLocal(activity.startedAt),
    distance: String(activity.distance),
    duration: formatDuration(activity.timeTakenSeconds),
    averagePaceKmh: optionalText(activity.averagePaceKmh),
    averageHeartRate: optionalText(activity.averageHeartRate),
    calories: optionalText(activity.calories),
  };
}

const DURATION = /^(?:(\d+):([0-5]\d)|(\d+)):([0-5]\d)$/;

/** Parses `mm:ss` or `h:mm:ss` into whole seconds; null when malformed or zero. */
export function parseDuration(input: string): number | null {
  const match = DURATION.exec(input.trim());
  if (!match) return null;
  const [, hours, minutesWithHours, minutesOnly, seconds] = match;
  const total =
    Number(hours ?? 0) * 3600 + Number(minutesWithHours ?? minutesOnly) * 60 + Number(seconds);
  return total > 0 ? total : null;
}

/** Longest **Activity** title the server accepts. */
export const TITLE_MAX_LENGTH = 255;

const title = z
  .string()
  .trim()
  .min(1, 'Title cannot be empty')
  .max(TITLE_MAX_LENGTH, 'Title is too long');

const startedAt = z.string().transform((raw, ctx) => {
  if (raw.trim() === '') {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Start time is required' });
    return z.NEVER;
  }
  const date = new Date(raw);
  if (Number.isNaN(date.getTime())) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Enter a valid start time' });
    return z.NEVER;
  }
  return date.toISOString();
});

const duration = z.string().transform((raw, ctx) => {
  const seconds = parseDuration(raw);
  if (seconds === null) {
    ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Enter a duration as mm:ss or h:mm:ss' });
    return z.NEVER;
  }
  return seconds;
});

function requiredNumber(isValid: (value: number) => boolean, message: string) {
  return z.string().transform((raw, ctx) => {
    const text = raw.trim();
    const value = Number(text);
    if (text === '' || !Number.isFinite(value) || !isValid(value)) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message });
      return z.NEVER;
    }
    return value;
  });
}

function optionalNumber(isValid: (value: number) => boolean, message: string) {
  const parse = requiredNumber(isValid, message);
  return z.string().transform((raw, ctx) => {
    if (raw.trim() === '') return null;
    const result = parse.safeParse(raw);
    if (!result.success) {
      ctx.addIssue({ code: z.ZodIssueCode.custom, message });
      return z.NEVER;
    }
    return result.data;
  });
}

const positive = (value: number) => value > 0;
const positiveInteger = (value: number) => Number.isInteger(value) && value > 0;
const nonNegativeInteger = (value: number) => Number.isInteger(value) && value >= 0;

const createFormSchema = z
  .object({
    title,
    sport: zActivityCreateRequestDto.shape.sport,
    startedAt,
    distance: requiredNumber(positive, 'Distance must be greater than zero'),
    duration,
    averagePaceKmh: optionalNumber(positive, 'Average speed must be greater than zero'),
    averageHeartRate: optionalNumber(positiveInteger, 'Enter a whole number of bpm'),
    calories: optionalNumber(nonNegativeInteger, 'Enter a whole number of kcal'),
  })
  .transform(
    ({ duration: timeTakenSeconds, ...rest }): ActivityCreateRequestDto => ({
      ...rest,
      timeTakenSeconds,
    }),
  )
  .pipe(zActivityCreateRequestDto);

const metadataFormSchema = z
  .object({ title, sport: zActivityUpdateRequestDto.shape.sport })
  .pipe(zActivityUpdateRequestDto);

/** The form edits the API's `timeTakenSeconds` as `duration`; every other field shares its API name. */
const formFieldOf = (apiField: string): string =>
  apiField === 'timeTakenSeconds' ? 'duration' : apiField;

/** Maps errors keyed by API field name (e.g. an `ApiErrorResponse`'s `fieldErrors`) onto form fields. */
export function formErrorsFromApi(
  fieldErrors: Readonly<Record<string, string>>,
): ActivityFormErrors {
  const errors: Partial<Record<string, string>> = {};
  for (const [field, message] of Object.entries(fieldErrors)) {
    errors[formFieldOf(field)] ??= message;
  }
  return errors;
}

function toFieldErrors(error: z.ZodError): ActivityFormErrors {
  const errors: Partial<Record<string, string>> = {};
  for (const issue of error.issues) {
    errors[formFieldOf(String(issue.path[0]))] ??= issue.message;
  }
  return errors;
}

function convert<T>(schema: z.ZodType<T, z.ZodTypeDef, unknown>, values: ActivityFormValues) {
  const result = schema.safeParse(values);
  return result.success
    ? ({ kind: 'VALID', data: result.data } as const)
    : ({ kind: 'INVALID', fieldErrors: toFieldErrors(result.error) } as const);
}

/** Converts form values into a request that logs or fully edits an Activity. */
export function toCreateRequest(
  values: ActivityFormValues,
): FormConversion<ActivityCreateRequestDto> {
  return convert(createFormSchema, values);
}

/** Converts form values into an edit of only the title and sport, leaving **Protected Metrics** untouched. */
export function toMetadataUpdateRequest(
  values: ActivityFormValues,
): FormConversion<ActivityUpdateRequestDto> {
  return convert(metadataFormSchema, values);
}
