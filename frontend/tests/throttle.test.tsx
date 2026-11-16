import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { LoginForm } from '../src/components/LoginForm';
import { RegisterForm } from '../src/components/RegisterForm';

const THROTTLED_MESSAGE = 'Too many attempts. Please try again in 15 minutes.';

/** Answers every request as the Throttle does, or with a bare 429 when [envelope] is false. */
function stubThrottled(envelope = true) {
  const fetchMock = vi.fn(
    async () =>
      new Response(
        envelope ? JSON.stringify({ kind: 'TOO_MANY_ATTEMPTS', message: THROTTLED_MESSAGE }) : '',
        {
          status: 429,
          statusText: 'Too Many Requests',
          headers: { 'Content-Type': 'application/json', 'Retry-After': '900' },
        },
      ),
  );
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

async function signIn() {
  const user = userEvent.setup();
  const onLoginSuccess = vi.fn();
  render(<LoginForm onLoginSuccess={onLoginSuccess} />);
  await user.type(screen.getByLabelText(/username/i), 'runner');
  await user.type(screen.getByLabelText(/password/i), 'wrong');
  await user.click(screen.getByRole('button', { name: /log in/i }));
  return onLoginSuccess;
}

describe('the Throttle on the sign-in and sign-up screens', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('shows a throttled sign-in the message the Server sent', async () => {
    stubThrottled();
    const onLoginSuccess = await signIn();

    expect(await screen.findByRole('alert')).toHaveTextContent(THROTTLED_MESSAGE);
    expect(onLoginSuccess).not.toHaveBeenCalled();
  });

  it('shows a throttled sign-up the message the Server sent', async () => {
    stubThrottled();
    const user = userEvent.setup();
    const onRegisterSuccess = vi.fn();
    render(<RegisterForm onRegisterSuccess={onRegisterSuccess} />);

    await user.type(screen.getByLabelText(/^username/i), 'newrunner');
    await user.type(screen.getByLabelText(/^email/i), 'newrunner@example.com');
    await user.type(screen.getByLabelText(/^password/i), 'secret123');
    await user.click(screen.getByRole('button', { name: /register|create|sign up/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(THROTTLED_MESSAGE);
    expect(onRegisterSuccess).not.toHaveBeenCalled();
  });

  it('still says to try again later when something in front of the Server answers 429 itself', async () => {
    stubThrottled(false);
    await signIn();

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Too many attempts. Please try again later.',
    );
  });
});
