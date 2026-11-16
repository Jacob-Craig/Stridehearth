import { queryOptions, useQueryClient } from '@tanstack/react-query';
import { apiClient, validatedFetch } from './client';
import type {
  LoginRequestDto,
  RegistrationStatusDto,
  UserFormDto,
  UserResponseDto,
} from './generated/types.gen';
import { zRegistrationStatusResponse } from './generated/zod.gen';

export async function loginUser(credentials: LoginRequestDto): Promise<UserResponseDto> {
  return apiClient<UserResponseDto>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(credentials),
  });
}

export async function registerUser(data: UserFormDto): Promise<UserResponseDto> {
  return apiClient<UserResponseDto>('/api/auth/register', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function logoutUser(): Promise<void> {
  await apiClient<void>('/api/auth/logout', {
    method: 'POST',
  });
}

export async function getCurrentUser(): Promise<UserResponseDto> {
  return apiClient<UserResponseDto>('/api/auth/me', {
    method: 'GET',
  });
}

/**
 * Whether this Server would accept a sign-up right now, as its Registration Mode decides. The sign-in and
 * sign-up screens use it to offer or withhold the sign-up form; the server checks every sign-up again.
 */
export const registrationStatusQueryOptions = queryOptions({
  queryKey: ['registrationStatus'] as const,
  queryFn: () =>
    validatedFetch<RegistrationStatusDto>(zRegistrationStatusResponse, '/api/auth/registration'),
  retry: false,
});

/**
 * Marks the registration status stale after a sign-up: on a closed Server the first account closes registration
 * behind it.
 */
export function useRefreshRegistrationStatus(): () => void {
  const queryClient = useQueryClient();
  return () => {
    queryClient.invalidateQueries({ queryKey: registrationStatusQueryOptions.queryKey });
  };
}
