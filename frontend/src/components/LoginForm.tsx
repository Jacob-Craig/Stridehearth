import { useForm } from '@tanstack/react-form';
import { useState } from 'react';
import { loginUser } from '../api/auth';
import type { UserResponseDto } from '../api/generated/types.gen';
import { zLoginRequestDto } from '../api/generated/zod.gen';
import { getFieldError, parseApiError } from '../utils/error';

interface LoginFormProps {
  onLoginSuccess: (user: UserResponseDto) => void;
}

export function LoginForm({ onLoginSuccess }: LoginFormProps) {
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const form = useForm({
    defaultValues: {
      username: '',
      password: '',
    },
    validators: {
      onChange: ({ value }) => {
        const result = zLoginRequestDto.safeParse(value);
        if (!result.success) {
          return result.error.issues[0]?.message;
        }
        return undefined;
      },
    },
    onSubmit: async ({ value }) => {
      setGeneralError(null);
      setFieldErrors({});

      try {
        const user = await loginUser(value);
        onLoginSuccess(user);
      } catch (err: unknown) {
        const parsed = parseApiError(err);
        setGeneralError(parsed.generalError);
        setFieldErrors(parsed.fieldErrors);
      }
    },
  });

  return (
    <div className="w-full max-w-md p-8 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-slate-200 dark:border-slate-800">
      <h2 className="text-2xl font-bold text-center mb-6 text-slate-900 dark:text-slate-100">
        Log in to Stridehearth
      </h2>

      {generalError && (
        <div
          role="alert"
          className="mb-4 p-3 rounded-lg bg-red-50 dark:bg-red-950/50 border border-red-200 dark:border-red-900 text-sm text-red-600 dark:text-red-400"
        >
          {generalError}
        </div>
      )}

      <form
        onSubmit={(e) => {
          e.preventDefault();
          e.stopPropagation();
          form.handleSubmit();
        }}
        className="space-y-4"
      >
        <form.Field
          name="username"
          validators={{
            onChange: ({ value }) => (!value ? 'Username cannot be empty' : undefined),
          }}
        >
          {(field) => {
            const error = getFieldError(fieldErrors.username, field.state.meta.errors[0]);
            return (
              <div>
                <label
                  htmlFor={field.name}
                  className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1"
                >
                  Username
                </label>
                <input
                  id={field.name}
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(e) => field.handleChange(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                {error && <p className="mt-1 text-xs text-red-600 dark:text-red-400">{error}</p>}
              </div>
            );
          }}
        </form.Field>

        <form.Field
          name="password"
          validators={{
            onChange: ({ value }) => (!value ? 'Password cannot be empty' : undefined),
          }}
        >
          {(field) => {
            const error = getFieldError(fieldErrors.password, field.state.meta.errors[0]);
            return (
              <div>
                <label
                  htmlFor={field.name}
                  className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1"
                >
                  Password
                </label>
                <input
                  type="password"
                  id={field.name}
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(e) => field.handleChange(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
                {error && <p className="mt-1 text-xs text-red-600 dark:text-red-400">{error}</p>}
              </div>
            );
          }}
        </form.Field>

        <form.Subscribe selector={(state) => [state.canSubmit, state.isSubmitting]}>
          {([canSubmit, isSubmitting]) => (
            <button
              type="submit"
              disabled={!canSubmit || isSubmitting}
              className="w-full mt-2 py-2.5 px-4 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white font-medium rounded-lg shadow transition focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
            >
              {isSubmitting ? 'Logging in...' : 'Log in'}
            </button>
          )}
        </form.Subscribe>
      </form>
    </div>
  );
}
