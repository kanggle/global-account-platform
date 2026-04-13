import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { RoleGuard } from '@/features/auth/guards/RoleGuard';

describe('RoleGuard', () => {
  it('renders children when operator has a permitted role', () => {
    render(
      <RoleGuard roles={['ACCOUNT_ADMIN']} allow={['SUPER_ADMIN', 'ACCOUNT_ADMIN']}>
        <button>잠금</button>
      </RoleGuard>,
    );
    expect(screen.getByRole('button', { name: '잠금' })).toBeInTheDocument();
  });

  it('hides children for AUDITOR when not in allow list', () => {
    render(
      <RoleGuard roles={['AUDITOR']} allow={['SUPER_ADMIN', 'ACCOUNT_ADMIN']}>
        <button>잠금</button>
      </RoleGuard>,
    );
    expect(screen.queryByRole('button', { name: '잠금' })).not.toBeInTheDocument();
  });

  it('renders fallback when no role matches', () => {
    render(
      <RoleGuard roles={['AUDITOR']} allow={['SUPER_ADMIN']} fallback={<span>권한 없음</span>}>
        <button>잠금</button>
      </RoleGuard>,
    );
    expect(screen.getByText('권한 없음')).toBeInTheDocument();
  });
});
