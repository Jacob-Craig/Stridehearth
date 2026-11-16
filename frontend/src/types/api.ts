import type { ApiErrorResponse as GeneratedApiErrorResponse } from '../api/generated/types.gen';
import { zApiErrorResponse } from '../api/generated/zod.gen';

/** The backend's `ApiErrorKind`, taken from the generated client so a new kind needs no edit here. */
type ApiErrorKind = NonNullable<GeneratedApiErrorResponse['kind']>;

/** The kinds whose response carries a message per offending request field. */
type KindWithFieldErrors = 'VALIDATION_FAILED' | 'DUPLICATE_RESOURCE';

export type ApiErrorResponse = {
  [K in ApiErrorKind]: K extends KindWithFieldErrors
    ? {
        readonly kind: K;
        readonly message: string;
        readonly fieldErrors: Readonly<Record<string, string>>;
      }
    : { readonly kind: K; readonly message: string };
}[ApiErrorKind];

const apiErrorKinds: readonly string[] = zApiErrorResponse.shape.kind.unwrap().options;

export function isApiErrorResponse(data: unknown): data is ApiErrorResponse {
  if (typeof data !== 'object' || data === null) {
    return false;
  }
  const obj = data as { kind?: unknown };
  return typeof obj.kind === 'string' && apiErrorKinds.includes(obj.kind);
}
