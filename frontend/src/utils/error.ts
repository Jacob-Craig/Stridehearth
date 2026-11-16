import { match } from 'ts-pattern';
import { ApiError } from '../api/client';
import type { ApiErrorResponse } from '../types/api';

export function parseApiError(err: unknown): {
  generalError: string;
  fieldErrors: Record<string, string>;
} {
  if (err instanceof ApiError) {
    const apiErr: ApiErrorResponse = err.errorResponse;
    return match(apiErr)
      .with({ kind: 'VALIDATION_FAILED' }, (e) => ({
        generalError: e.message || 'Validation failed',
        fieldErrors: { ...e.fieldErrors },
      }))
      .with({ kind: 'DUPLICATE_RESOURCE' }, (e) => ({
        generalError: e.message || 'Resource already exists',
        fieldErrors: { ...e.fieldErrors },
      }))
      .with({ kind: 'INVALID_ACTIVITY_FILE' }, (e) => ({
        generalError: e.message || 'Invalid activity file',
        fieldErrors: {},
      }))
      .with({ kind: 'RESOURCE_NOT_FOUND' }, (e) => ({
        generalError: e.message || 'Resource not found',
        fieldErrors: {},
      }))
      .with({ kind: 'BAD_REQUEST' }, (e) => ({
        generalError: e.message || 'Bad request',
        fieldErrors: {},
      }))
      .with({ kind: 'UNAUTHORIZED' }, (e) => ({
        generalError: e.message || 'Invalid username or password',
        fieldErrors: {},
      }))
      .with({ kind: 'REGISTRATION_CLOSED' }, (e) => ({
        generalError: e.message || 'Registration is closed on this Server.',
        fieldErrors: {},
      }))
      .with({ kind: 'TOO_MANY_ATTEMPTS' }, (e) => ({
        generalError: e.message || 'Too many attempts. Please try again later.',
        fieldErrors: {},
      }))
      .with({ kind: 'INVITE_UNUSABLE' }, (e) => ({
        generalError: e.message || 'This invite link cannot be used.',
        fieldErrors: {},
      }))
      .with({ kind: 'ADMINISTRATOR_ONLY' }, (e) => ({
        generalError: e.message || 'Only the Administrator can manage invites.',
        fieldErrors: {},
      }))
      .with({ kind: 'CONCURRENT_CHANGE' }, (e) => ({
        generalError:
          e.message || 'Another change to your activities is in progress. Please try again.',
        fieldErrors: {},
      }))
      .exhaustive();
  }

  if (err instanceof Error) {
    return { generalError: err.message, fieldErrors: {} };
  }

  return { generalError: 'An unexpected error occurred', fieldErrors: {} };
}

export function getFieldError(
  serverError: string | undefined,
  formError: unknown,
): string | undefined {
  if (serverError) {
    return serverError;
  }
  if (!formError) {
    return undefined;
  }
  if (typeof formError === 'string') {
    return formError;
  }
  if (
    typeof formError === 'object' &&
    'message' in formError &&
    typeof (formError as { message: unknown }).message === 'string'
  ) {
    return (formError as { message: string }).message;
  }
  return String(formError);
}
