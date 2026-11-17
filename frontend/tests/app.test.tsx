import { act, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { type ComponentType, StrictMode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// The real app, mounted the way `main.tsx` mounts it: inside StrictMode, with browser history and
// the real AuthProvider. Each test gets a fresh module graph so the app's QueryClient starts empty.
async function renderApp(path: string) {
  window.history.replaceState(null, '', path);
  vi.resetModules();
  const { App } = (await import('../src/App')) as { App: ComponentType };
  return render(
    <StrictMode>
      <App />
    </StrictMode>,
  );
}

// Route content must appear *and stay*: the regression (#63) rendered a page for a moment and then
// left the router idle with no matches and an empty #root.
async function expectToSettleOn(assertion: () => void) {
  await waitFor(assertion, { timeout: 3000 });
  await act(() => new Promise((resolve) => setTimeout(resolve, 150)));
  assertion();
}

const runner = { id: 7, username: 'late_runner', email: 'late@example.com' };

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe('App: auth resolving after the first render', () => {
  let me: ReturnType<typeof deferred<Response>>;

  beforeEach(() => {
    me = deferred<Response>();
    vi.stubGlobal(
      'fetch',
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes('/api/auth/me')) return me.promise;
        if (url.includes('/api/auth/login')) return json(runner);
        if (url.includes('/api/dashboard')) return json({ personalRecordSlots: [] });
        if (url.includes('/api/athlete/personal-record-progression')) {
          return json({ progressions: [] });
        }
        return json({});
      }),
    );
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders the Dashboard once a late /api/auth/me returns the signed-in user', async () => {
    await renderApp('/dashboard');

    me.resolve(json(runner));

    await expectToSettleOn(() => {
      expect(screen.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument();
      expect(screen.getByText('late_runner')).toBeInTheDocument();
    });
  });

  it('renders the login page after a late 401, then the original destination after logging in', async () => {
    const user = userEvent.setup();
    await renderApp('/dashboard');

    me.resolve(json({ kind: 'UNAUTHORIZED', message: 'Not logged in' }, 401));

    await expectToSettleOn(() => {
      expect(screen.getByLabelText(/username/i)).toBeInTheDocument();
      expect(window.location.pathname).toBe('/login');
      expect(new URLSearchParams(window.location.search).get('redirect')).toBe('/dashboard');
    });

    await user.type(screen.getByLabelText(/username/i), 'late_runner');
    await user.type(screen.getByLabelText(/password/i), 'secret123');
    await user.click(screen.getByRole('button', { name: /log in|sign in/i }));

    await expectToSettleOn(() => {
      expect(screen.getByRole('heading', { name: 'Dashboard', level: 1 })).toBeInTheDocument();
      expect(screen.getByText('late_runner')).toBeInTheDocument();
      expect(window.location.pathname).toBe('/dashboard');
    });
  });
});
