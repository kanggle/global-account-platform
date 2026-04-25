import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ToastProvider } from '@/shared/ui/toast';
import { runAxe } from '../a11y/axe-helper';

const detailFixture = {
  id: 'acc-1',
  email: 'user@example.com',
  status: 'ACTIVE' as const,
  createdAt: '2026-01-01T12:34:56Z',
  lastLoginAt: '2026-04-01T09:00:00Z',
  recentLogins: [
    {
      eventId: 'evt-1',
      occurredAt: '2026-04-01T09:00:00Z',
      outcome: 'SUCCESS' as const,
      ipMasked: '10.0.0.x',
      geoCountry: 'KR',
    },
  ],
};

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/features/accounts/hooks/useAccountDetail', () => ({
  useAccountDetail: () => ({ data: detailFixture, isLoading: false, isError: false }),
}));

import { AccountDetail } from '@/features/accounts/components/AccountDetail';

function wrap(ui: React.ReactNode) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return (
    <QueryClientProvider client={qc}>
      <ToastProvider>{ui}</ToastProvider>
    </QueryClientProvider>
  );
}

describe('AccountDetail', () => {
  it('renders ISO timestamps via formatDateTime (not raw)', async () => {
    render(wrap(<AccountDetail accountId="acc-1" roles={['SUPER_ADMIN']} />));
    await waitFor(() => expect(screen.getByText('user@example.com')).toBeInTheDocument());
    // Raw ISO strings should not appear; formatted ones should.
    expect(screen.queryByText('2026-01-01T12:34:56Z')).not.toBeInTheDocument();
    expect(screen.getAllByText(/2026-01-01 \d{2}:\d{2}:\d{2}/).length).toBeGreaterThan(0);
  });

  it('hides lock/unlock controls for SUPPORT_READONLY role', async () => {
    render(wrap(<AccountDetail accountId="acc-1" roles={['SUPPORT_READONLY']} />));
    await waitFor(() => expect(screen.getByText('user@example.com')).toBeInTheDocument());
    expect(screen.queryByRole('button', { name: '잠금' })).not.toBeInTheDocument();
  });

  it('has no axe violations', async () => {
    const { container } = render(wrap(<AccountDetail accountId="acc-1" roles={['SUPER_ADMIN']} />));
    await waitFor(() => expect(screen.getByText('user@example.com')).toBeInTheDocument());
    const violations = await runAxe(container);
    expect(violations).toEqual([]);
  });
});
