import { Link } from '@tanstack/react-router';
import type { ReactNode } from 'react';
import type { UserResponseDto } from '../api/generated/types.gen';

interface AppShellProps {
  user: UserResponseDto | null;
  onLogout: () => void;
  children: ReactNode;
}

export function AppShell({ user, onLogout, children }: AppShellProps) {
  return (
    <div className="min-h-screen flex flex-col bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100">
      <header className="border-b border-slate-200 dark:border-slate-800 bg-white/80 dark:bg-slate-900/80 backdrop-blur sticky top-0 z-40">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between">
          <div className="flex items-center gap-8">
            <Link
              to="/dashboard"
              className="text-xl font-bold tracking-tight text-blue-600 dark:text-blue-400 flex items-center gap-2"
            >
              <span className="tabular-nums">🏃‍♂️</span>
              <span>Stridehearth</span>
            </Link>

            <nav className="hidden md:flex items-center gap-1">
              <Link
                to="/dashboard"
                className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Dashboard
              </Link>
              <Link
                to="/activities"
                className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Activities
              </Link>
              <Link
                to="/heatmap"
                className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Heatmap
              </Link>
              <Link
                to="/activities/manage"
                className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Manage
              </Link>
              <Link
                to="/activities/import"
                className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Import
              </Link>
              <Link
                to="/activities/import/history"
                className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Import History
              </Link>
              <Link
                to="/activities/add_activity"
                className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
              >
                Add Activity
              </Link>
              {user?.administrator === true && (
                <Link
                  to="/invites"
                  className="px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition"
                >
                  Invites
                </Link>
              )}
            </nav>
          </div>

          <div className="flex items-center gap-4">
            {user && (
              <span className="text-sm font-medium px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700">
                {user.username}
              </span>
            )}
            <button
              type="button"
              onClick={onLogout}
              className="px-3 py-1.5 text-sm font-medium text-slate-700 dark:text-slate-300 hover:text-red-600 dark:hover:text-red-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-md transition"
            >
              Log out
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6">{children}</main>
    </div>
  );
}
