import type { z } from 'zod';
import { type ApiErrorResponse, isApiErrorResponse } from '../types/api';

export function getCsrfToken(): string | null {
  if (typeof document === 'undefined') return null;
  const match = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return match?.[1] ? decodeURIComponent(match[1]) : null;
}

export class ApiError extends Error {
  readonly errorResponse: ApiErrorResponse;
  readonly status: number;

  constructor(status: number, errorResponse: ApiErrorResponse) {
    super(errorResponse.message);
    this.name = 'ApiError';
    this.status = status;
    this.errorResponse = errorResponse;
  }
}

export async function apiClient<T>(url: string, init?: RequestInit): Promise<T> {
  const method = init?.method?.toUpperCase() ?? 'GET';
  const headers = new Headers(init?.headers);

  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  if (['POST', 'PUT', 'DELETE', 'PATCH'].includes(method)) {
    const csrf = getCsrfToken();
    if (csrf && !headers.has('X-XSRF-TOKEN')) {
      headers.set('X-XSRF-TOKEN', csrf);
    }
    if (init?.body && !headers.has('Content-Type') && typeof init.body === 'string') {
      headers.set('Content-Type', 'application/json');
    }
  }

  const response = await fetch(url, {
    ...init,
    headers,
    credentials: 'include',
  });

  if (!response.ok) {
    let errorData: unknown = null;
    try {
      errorData = await response.json();
    } catch {
      // Not JSON
    }

    if (isApiErrorResponse(errorData)) {
      throw new ApiError(response.status, errorData);
    }

    // A 429 without the Server's envelope comes from something in front of it, such as a reverse proxy.
    if (response.status === 429) {
      throw new ApiError(response.status, {
        kind: 'TOO_MANY_ATTEMPTS',
        message: 'Too many attempts. Please try again later.',
      });
    }

    throw new ApiError(response.status, {
      kind: response.status === 401 ? 'UNAUTHORIZED' : 'BAD_REQUEST',
      message: response.statusText || 'An unexpected error occurred',
    });
  }

  if (response.status === 204) {
    return undefined as unknown as T;
  }

  return response.json() as Promise<T>;
}

/**
 * Fetches a response and validates it against its generated (server-derived) Zod schema, so contract
 * drift fails loudly instead of rendering malformed data. The body is returned as the generated wire
 * type rather than the schema's output, because the generated schemas coerce int64 ids to `bigint`
 * while JSON (and the generated types) carry them as `number`.
 */
export async function validatedFetch<T>(schema: z.ZodTypeAny, url: string, init?: RequestInit) {
  const body = await apiClient<unknown>(url, init);
  const result = schema.safeParse(body);
  if (!result.success) {
    throw new Error('The server sent an unexpected response', { cause: result.error });
  }
  return body as T;
}
