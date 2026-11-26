import { Link } from '@tanstack/react-router';
import { type ReactNode, useEffect, useId, useState } from 'react';
import type { UserResponseDto } from '../api/generated/types.gen';

interface AppShellProps {
  user: UserResponseDto | null;
  onLogout: () => void;
  children: ReactNode;
}

interface NavItem {
  readonly to: string;
  readonly label: string;
  readonly administratorOnly?: boolean;
}

const NAV_ITEMS: readonly NavItem[] = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/activities', label: 'Activities' },
  { to: '/heatmap', label: 'Heatmap' },
  { to: '/activities/manage', label: 'Manage' },
  { to: '/activities/import', label: 'Import' },
  { to: '/activities/import/history', label: 'Import History' },
  { to: '/activities/add_activity', label: 'Add Activity' },
  { to: '/settings', label: 'Settings' },
  { to: '/invites', label: 'Invites', administratorOnly: true },
];

const LINK_CLASS =
  'whitespace-nowrap px-3 py-2 rounded-md text-sm font-medium hover:bg-slate-100 dark:hover:bg-slate-800 transition';
const LOGOUT_CLASS =
  'whitespace-nowrap px-3 py-1.5 text-sm font-medium text-slate-700 dark:text-slate-300 hover:text-red-600 dark:hover:text-red-400 hover:bg-slate-100 dark:hover:bg-slate-800 rounded-md transition';

/**
 * The authenticated page frame. The full nav needs about 1,250 px, so it shows from the `xl` breakpoint;
 * narrower windows get a Menu disclosure with the same links, and on phones the username and Log out move
 * into it too, so no width scrolls sideways and every page stays reachable. Labels never wrap; a long
 * username truncates instead.
 */
export function AppShell({ user, onLogout, children }: AppShellProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuId = useId();
  const items = NAV_ITEMS.filter((item) => !item.administratorOnly || user?.administrator === true);

  useEffect(() => {
    if (!menuOpen) return;
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuOpen(false);
    };
    document.addEventListener('keydown', closeOnEscape);
    return () => document.removeEventListener('keydown', closeOnEscape);
  }, [menuOpen]);

  return (
    <div className="min-h-screen flex flex-col bg-slate-50 dark:bg-slate-950 text-slate-900 dark:text-slate-100">
      <header className="border-b border-slate-200 dark:border-slate-800 bg-white/80 dark:bg-slate-900/80 backdrop-blur sticky top-0 z-40 print:hidden">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 h-16 flex items-center justify-between gap-4">
          <div className="flex shrink-0 items-center gap-8">
            <Link
              to="/dashboard"
              className="text-xl font-bold tracking-tight text-blue-600 dark:text-blue-400 flex items-center gap-2"
            >
              <span className="tabular-nums">🏃‍♂️</span>
              <span>Stridehearth</span>
            </Link>

            <nav aria-label="Main" className="hidden xl:flex items-center gap-1">
              {items.map((item) => (
                <Link key={item.to} to={item.to} className={LINK_CLASS}>
                  {item.label}
                </Link>
              ))}
            </nav>
          </div>

          <div className="flex min-w-0 items-center gap-4">
            {user && (
              <span className="hidden sm:inline-block min-w-0 max-w-48 truncate text-sm font-medium px-2.5 py-1 rounded-full bg-slate-100 dark:bg-slate-800 border border-slate-200 dark:border-slate-700">
                {user.username}
              </span>
            )}
            <button type="button" onClick={onLogout} className={`hidden sm:block ${LOGOUT_CLASS}`}>
              Log out
            </button>
            <button
              type="button"
              aria-expanded={menuOpen}
              aria-controls={menuId}
              onClick={() => setMenuOpen((open) => !open)}
              className="xl:hidden px-3 py-1.5 rounded-md text-sm font-medium ring-1 ring-slate-300 dark:ring-slate-700 hover:bg-slate-100 dark:hover:bg-slate-800 transition"
            >
              Menu
            </button>
          </div>
        </div>

        {menuOpen && (
          <nav
            id={menuId}
            aria-label="Menu"
            className="xl:hidden border-t border-slate-200 dark:border-slate-800 max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-2"
          >
            <ul className="grid gap-1 sm:grid-cols-2 lg:grid-cols-3">
              {items.map((item) => (
                <li key={item.to}>
                  <Link
                    to={item.to}
                    onClick={() => setMenuOpen(false)}
                    className={`block ${LINK_CLASS}`}
                  >
                    {item.label}
                  </Link>
                </li>
              ))}
            </ul>
            <div className="sm:hidden mt-2 flex items-center justify-between gap-4 border-t border-slate-200 dark:border-slate-800 pt-2">
              {user && (
                <span className="min-w-0 truncate text-sm font-medium">{user.username}</span>
              )}
              <button type="button" onClick={onLogout} className={LOGOUT_CLASS}>
                Log out
              </button>
            </div>
          </nav>
        )}
      </header>

      <main className="flex-1 max-w-7xl w-full mx-auto px-4 sm:px-6 lg:px-8 py-6">{children}</main>
    </div>
  );
}
