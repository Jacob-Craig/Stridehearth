import {
  createMemoryHistory,
  createRootRoute,
  createRouter,
  RouterProvider,
} from '@tanstack/react-router';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { UserResponseDto } from '../src/api/generated/types.gen';
import { AppShell } from '../src/components/AppShell';

const athlete = { id: 1, username: 'runner', administrator: false } as UserResponseDto;

/** Renders the shell inside a router, since its links need one. */
function renderShell(user: UserResponseDto = athlete, onLogout = vi.fn()) {
  const rootRoute = createRootRoute({
    component: () => (
      <AppShell user={user} onLogout={onLogout}>
        <p>Page</p>
      </AppShell>
    ),
  });
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  render(<RouterProvider router={router} />);
  return { onLogout };
}

const menuButton = () => screen.findByRole('button', { name: 'Menu' });

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
});
