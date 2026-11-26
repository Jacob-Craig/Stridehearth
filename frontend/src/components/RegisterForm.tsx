import { useForm } from '@tanstack/react-form';
import { type ReactNode, useState } from 'react';
import { z } from 'zod';
import { registerUser } from '../api/auth';
import type { UserFormDto, UserResponseDto } from '../api/generated/types.gen';
import { zUserFormDto } from '../api/generated/zod.gen';
import { getFieldError, parseApiError } from '../utils/error';

/**
 * The registration contract, except that the server alone judges an email address: Zod's email
 * check rejects addresses its `@Email` accepts (such as `runner@localhost`), and this form-level
 * result only enables Register, so a stricter check here would disable it without saying why.
 */
const registrationSchema = zUserFormDto.extend({ email: z.string().min(1) });

interface RegisterFormProps {
  onRegisterSuccess: (user: UserResponseDto) => void;
  /** Creates the account; an ordinary sign-up unless given, such as one with an Invite. */
  submit?: (data: UserFormDto) => Promise<UserResponseDto>;
  heading?: string;
  /** Shown between the heading and the form. */
  intro?: ReactNode;
}

export function RegisterForm({
  onRegisterSuccess,
  submit = registerUser,
  heading = 'Create an Account',
  intro,
}: RegisterFormProps) {
  const [generalError, setGeneralError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const form = useForm({
    defaultValues: {
      username: '',
      email: '',
      password: '',
      firstName: '',
      lastName: '',
    },
    validators: {
      onChange: ({ value }) => {
        const result = registrationSchema.safeParse(value);
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
        const user = await submit(value);
        onRegisterSuccess(user);
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
        {heading}
      </h2>

      {intro && (
        <p className="-mt-3 mb-6 text-sm text-center text-slate-600 dark:text-slate-400">{intro}</p>
      )}

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
          name="email"
          validators={{
            onChange: ({ value }) =>
              !value
                ? 'Email cannot be empty'
                : !value.includes('@')
                  ? 'Email must be valid'
                  : undefined,
          }}
        >
          {(field) => {
            const error = getFieldError(fieldErrors.email, field.state.meta.errors[0]);
            return (
              <div>
                <label
                  htmlFor={field.name}
                  className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1"
                >
                  Email
                </label>
                <input
                  type="email"
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

        <div className="grid grid-cols-2 gap-3">
          <form.Field name="firstName">
            {(field) => (
              <div>
                <label
                  htmlFor={field.name}
                  className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1"
                >
                  First Name
                </label>
                <input
                  id={field.name}
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(e) => field.handleChange(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            )}
          </form.Field>

          <form.Field name="lastName">
            {(field) => (
              <div>
                <label
                  htmlFor={field.name}
                  className="block text-sm font-medium text-slate-700 dark:text-slate-300 mb-1"
                >
                  Last Name
                </label>
                <input
                  id={field.name}
                  name={field.name}
                  value={field.state.value}
                  onBlur={field.handleBlur}
                  onChange={(e) => field.handleChange(e.target.value)}
                  className="w-full px-3 py-2 border rounded-lg bg-white dark:bg-slate-800 border-slate-300 dark:border-slate-700 text-slate-900 dark:text-slate-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
                />
              </div>
            )}
          </form.Field>
        </div>

        <form.Subscribe selector={(state) => [state.canSubmit, state.isSubmitting]}>
          {([canSubmit, isSubmitting]) => (
            <button
              type="submit"
              disabled={!canSubmit || isSubmitting}
              className="w-full mt-2 py-2.5 px-4 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white font-medium rounded-lg shadow transition focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
            >
              {isSubmitting ? 'Registering...' : 'Register'}
            </button>
          )}
        </form.Subscribe>
      </form>
    </div>
  );
}
