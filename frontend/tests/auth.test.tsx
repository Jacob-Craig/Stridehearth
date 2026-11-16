import {
  RouterProvider,
  createMemoryHistory,
  createRootRoute,
  createRouter,
} from '@tanstack/react-router';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactElement } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as authApi from '../src/api/auth';
import { ApiError } from '../src/api/client';
import { AppShell } from '../src/components/AppShell';
import { LoginForm } from '../src/components/LoginForm';
import { RegisterForm } from '../src/components/RegisterForm';

async function renderWithRouter(ui: ReactElement) {
  const rootRoute = createRootRoute({
    component: () => ui,
  });
  const router = createRouter({
    routeTree: rootRoute,
    history: createMemoryHistory({ initialEntries: ['/'] }),
  });
  await router.load();
  return render(<RouterProvider router={router} />);
}

vi.mock('../src/api/auth');

describe('Auth & App Shell Components', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('LoginForm', () => {
    it('submits credentials and invokes onLoginSuccess', async () => {
      const user = userEvent.setup();
      const onLoginSuccess = vi.fn();
      vi.mocked(authApi.loginUser).mockResolvedValueOnce({
        id: 1,
        username: 'runner',
        email: 'runner@example.com',
      });

      render(<LoginForm onLoginSuccess={onLoginSuccess} />);

      await user.type(screen.getByLabelText(/username/i), 'runner');
      await user.type(screen.getByLabelText(/password/i), 'secret123');
      await user.click(screen.getByRole('button', { name: /log in|sign in/i }));

      await waitFor(() => {
        expect(authApi.loginUser).toHaveBeenCalledWith({
          username: 'runner',
          password: 'secret123',
        });
        expect(onLoginSuccess).toHaveBeenCalled();
      });
    });

    it('displays error banner when authentication fails with 401 UNAUTHORIZED', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.loginUser).mockRejectedValueOnce(
        new ApiError(401, {
          kind: 'UNAUTHORIZED',
          message: 'Invalid username or password',
        }),
      );

      render(<LoginForm onLoginSuccess={vi.fn()} />);

      await user.type(screen.getByLabelText(/username/i), 'runner');
      await user.type(screen.getByLabelText(/password/i), 'wrongpassword');
      await user.click(screen.getByRole('button', { name: /log in|sign in/i }));

      await waitFor(() => {
        expect(screen.getByText(/invalid username or password/i)).toBeInTheDocument();
      });
    });
  });

  describe('RegisterForm', () => {
    it('submits registration fields and calls onRegisterSuccess', async () => {
      const user = userEvent.setup();
      const onRegisterSuccess = vi.fn();
      vi.mocked(authApi.registerUser).mockResolvedValueOnce({
        id: 2,
        username: 'newrunner',
        email: 'newrunner@example.com',
      });

      render(<RegisterForm onRegisterSuccess={onRegisterSuccess} />);

      await user.type(screen.getByLabelText(/^username/i), 'newrunner');
      await user.type(screen.getByLabelText(/^email/i), 'newrunner@example.com');
      await user.type(screen.getByLabelText(/^password/i), 'password123');
      await user.click(screen.getByRole('button', { name: /register|sign up/i }));

      await waitFor(() => {
        expect(authApi.registerUser).toHaveBeenCalledWith({
          username: 'newrunner',
          email: 'newrunner@example.com',
          password: 'password123',
          firstName: '',
          lastName: '',
        });
        expect(onRegisterSuccess).toHaveBeenCalled();
      });
    });

    it('maps DUPLICATE_RESOURCE fieldErrors directly to the form fields', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.registerUser).mockRejectedValueOnce(
        new ApiError(400, {
          kind: 'DUPLICATE_RESOURCE',
          message: 'Resource already exists',
          fieldErrors: {
            username: 'Username is already taken',
          },
        }),
      );

      render(<RegisterForm onRegisterSuccess={vi.fn()} />);

      await user.type(screen.getByLabelText(/^username/i), 'runner');
      await user.type(screen.getByLabelText(/^email/i), 'runner@example.com');
      await user.type(screen.getByLabelText(/^password/i), 'password123');
      await user.click(screen.getByRole('button', { name: /register|sign up/i }));

      await waitFor(() => {
        expect(screen.getByText('Username is already taken')).toBeInTheDocument();
      });
    });

    it('maps DUPLICATE_RESOURCE email fieldError directly to the email input', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.registerUser).mockRejectedValueOnce(
        new ApiError(400, {
          kind: 'DUPLICATE_RESOURCE',
          message: 'Resource already exists',
          fieldErrors: {
            email: 'Email is already registered',
          },
        }),
      );

      render(<RegisterForm onRegisterSuccess={vi.fn()} />);

      await user.type(screen.getByLabelText(/^username/i), 'runner');
      await user.type(screen.getByLabelText(/^email/i), 'runner@example.com');
      await user.type(screen.getByLabelText(/^password/i), 'password123');
      await user.click(screen.getByRole('button', { name: /register|sign up/i }));

      await waitFor(() => {
        expect(screen.getByText('Email is already registered')).toBeInTheDocument();
      });
    });

    it('maps VALIDATION_FAILED fieldErrors directly to the form fields', async () => {
      const user = userEvent.setup();
      vi.mocked(authApi.registerUser).mockRejectedValueOnce(
        new ApiError(400, {
          kind: 'VALIDATION_FAILED',
          message: 'Validation failed',
          fieldErrors: {
            email: 'Email must be valid',
          },
        }),
      );

      render(<RegisterForm onRegisterSuccess={vi.fn()} />);

      await user.type(screen.getByLabelText(/^username/i), 'validname');
      await user.type(screen.getByLabelText(/^email/i), 'bad-email');
      await user.type(screen.getByLabelText(/^password/i), 'password123');
      await user.click(screen.getByRole('button', { name: /register|sign up/i }));

      await waitFor(() => {
        expect(screen.getByText('Email must be valid')).toBeInTheDocument();
      });
    });
  });

  describe('AppShell', () => {
    const mockUser = {
      id: 1,
      username: 'runner_alice',
      email: 'alice@example.com',
    };

    it('renders all required navigation links and user badge', async () => {
      await renderWithRouter(
        <AppShell user={mockUser} onLogout={vi.fn()}>
          <div>Content</div>
        </AppShell>,
      );

      expect(screen.getByRole('link', { name: /dashboard/i })).toHaveAttribute(
        'href',
        '/dashboard',
      );
      expect(screen.getByRole('link', { name: /^activities$/i })).toHaveAttribute(
        'href',
        '/activities',
      );
      expect(screen.getByRole('link', { name: /manage/i })).toHaveAttribute(
        'href',
        '/activities/manage',
      );
      expect(screen.getByRole('link', { name: /^import$/i })).toHaveAttribute(
        'href',
        '/activities/import',
      );
      expect(screen.getByRole('link', { name: /import history/i })).toHaveAttribute(
        'href',
        '/activities/import/history',
      );
      expect(screen.getByRole('link', { name: /add activity/i })).toHaveAttribute(
        'href',
        '/activities/add_activity',
      );
      expect(screen.getByText('runner_alice')).toBeInTheDocument();
      expect(screen.getByText('Content')).toBeInTheDocument();
    });

    it('calls onLogout when logout button is clicked', async () => {
      const user = userEvent.setup();
      const onLogout = vi.fn();

      await renderWithRouter(
        <AppShell user={mockUser} onLogout={onLogout}>
          <div>Content</div>
        </AppShell>,
      );

      await user.click(screen.getByRole('button', { name: /log out|sign out/i }));
      expect(onLogout).toHaveBeenCalled();
    });
  });
});
