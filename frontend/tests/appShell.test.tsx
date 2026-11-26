import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { act } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { UserResponseDto } from '../src/api/generated/types.gen';
import { AppShell } from '../src/components/AppShell';

const athlete = { id: 1, username: 'runner', administrator: false } as UserResponseDto;

/** Renders the shell inside a router, since its links need one. */
function renderShell(user: UserResponseDto = athlete, onLogout = vi.fn(), entry = '/') {
  const rootRoute = createRootRoute({
    component: () => (
      <AppShell user={user} onLogout={onLogout}>
        <p>Page</p>
      </AppShell>
    ),
  });
  const history = createMemoryHistory({ initialEntries: [entry] });
  const router = createRouter({ routeTree: rootRoute, history });
  render(<RouterProvider router={router} />);
  return { onLogout, router, history };
}

const menuButton = () => screen.findByRole('button', { name: 'Menu' });

/** Stubs `matchMedia` with a wide-window query the test can flip, notifying the shell's listener. */
function stubWideWindow() {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  const query = {
    matches: false,
    media: '(min-width: 1280px)',
    addEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) =>
      listeners.add(listener),
    removeEventListener: (_type: string, listener: (event: MediaQueryListEvent) => void) =>
      listeners.delete(listener),
  };
  const matchMedia = vi.fn(() => query);
  vi.stubGlobal('matchMedia', matchMedia);
  const setWide = (matches: boolean) => {
    query.matches = matches;
    for (const listener of listeners) listener({ matches } as MediaQueryListEvent);
  };
  return Object.assign(setWide, { matchMedia });
}

afterEach(() => vi.unstubAllGlobals());

describe('AppShell navigation menu', () => {
  it('starts closed', async () => {
    renderShell();
    expect(await menuButton()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();
  });

  it('opens to every page, Settings included, for the narrow widths that hide the full nav', async () => {
    renderShell();
    fireEvent.click(await menuButton());

    expect(await menuButton()).toHaveAttribute('aria-expanded', 'true');
    const menu = screen.getByRole('navigation', { name: 'Menu' });
    expect(
      within(menu)
        .getAllByRole('link')
        .map((link) => link.textContent),
    ).toEqual([
      'Dashboard',
      'Activities',
      'Heatmap',
      'Manage',
      'Import',
      'Import History',
      'Add Activity',
      'Settings',
    ]);
    expect(within(menu).getByRole('link', { name: 'Settings' })).toHaveAttribute(
      'href',
      '/settings',
    );
  });

  it('lists Invites in the menu for an administrator only', async () => {
    renderShell({ ...athlete, administrator: true });
    fireEvent.click(await menuButton());
    const menu = screen.getByRole('navigation', { name: 'Menu' });
    expect(within(menu).getByRole('link', { name: 'Invites' })).toBeInTheDocument();
  });

  it('closes when a page is picked', async () => {
    renderShell();
    fireEvent.click(await menuButton());
    const menu = screen.getByRole('navigation', { name: 'Menu' });
    fireEvent.click(within(menu).getByRole('link', { name: 'Settings' }));
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();
  });

  it('closes on Escape', async () => {
    renderShell();
    fireEvent.click(await menuButton());
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();
    expect(await menuButton()).toHaveAttribute('aria-expanded', 'false');
  });

  it('offers the username and Log out inside the menu, for phones', async () => {
    const { onLogout } = renderShell();
    fireEvent.click(await menuButton());
    const menu = screen.getByRole('navigation', { name: 'Menu' });
    expect(within(menu).getByText('runner')).toBeInTheDocument();
    fireEvent.click(within(menu).getByRole('button', { name: 'Log out' }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('closes when the page changes from outside the menu, such as the logo or Back', async () => {
    const { router, history } = renderShell();
    fireEvent.click(await menuButton());
    await act(() => router.navigate({ to: '/settings' }));
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();

    fireEvent.click(await menuButton());
    await act(async () => {
      history.back();
      await router.load();
    });
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();
  });

  it('returns focus to the Menu button when Escape closes the menu from a link', async () => {
    renderShell();
    fireEvent.click(await menuButton());
    const menu = screen.getByRole('navigation', { name: 'Menu' });
    within(menu).getByRole('link', { name: 'Settings' }).focus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(await menuButton()).toHaveFocus();
  });

  it('closes when the window widens to the full nav, so it does not reappear open', async () => {
    const setWide = stubWideWindow();
    renderShell();
    fireEvent.click(await menuButton());
    expect(setWide.matchMedia).toHaveBeenCalledWith('(min-width: 1280px)');
    act(() => setWide(true));
    act(() => setWide(false));
    expect(await menuButton()).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();
  });

  it('closes when the logo is clicked on the page it links to', async () => {
    renderShell(athlete, vi.fn(), '/dashboard');
    fireEvent.click(await menuButton());
    fireEvent.click(screen.getByRole('link', { name: /Stridehearth/ }));
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();
  });

  it('leaves focus alone when Escape closes the menu while focus is elsewhere', async () => {
    renderShell();
    const field = document.body.appendChild(document.createElement('input'));
    fireEvent.click(await menuButton());
    field.focus();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByRole('navigation', { name: 'Menu' })).toBeNull();
    expect(field).toHaveFocus();
    field.remove();
  });
});
