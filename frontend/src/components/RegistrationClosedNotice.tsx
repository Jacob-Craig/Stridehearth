/** What a closed Server says in place of a sign-up form (ADR-0011). */
export const REGISTRATION_CLOSED_MESSAGE = 'Registration is closed on this Server.';

/** The sign-up screen of a Server whose Registration Mode no longer accepts sign-ups. */
export function RegistrationClosedNotice() {
  return (
    <div className="w-full max-w-md p-8 bg-white dark:bg-slate-900 rounded-xl shadow-lg border border-slate-200 dark:border-slate-800">
      <h2 className="text-2xl font-bold text-center mb-4 text-slate-900 dark:text-slate-100">
        Registration is closed
      </h2>
      <p className="text-sm text-center text-slate-600 dark:text-slate-400">
        {REGISTRATION_CLOSED_MESSAGE} Only its first account could register itself, and that account
        already exists.
      </p>
    </div>
  );
}
