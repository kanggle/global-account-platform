'use client';

import { useState } from 'react';
import { useAccountDetail } from '../hooks/useAccountDetail';
import { LockDialog } from './LockDialog';
import { UnlockDialog } from './UnlockDialog';
import { Button } from '@/shared/ui/button';
import { Badge } from '@/shared/ui/badge';
import { RoleGuard } from '@/features/auth/guards/RoleGuard';
import type { OperatorRole } from '@/shared/api/admin-api';

interface Props {
  accountId: string;
  roles: OperatorRole[];
}

export function AccountDetail({ accountId, roles }: Props) {
  const [lockOpen, setLockOpen] = useState(false);
  const [unlockOpen, setUnlockOpen] = useState(false);
  const { data, isLoading, isError } = useAccountDetail(accountId);

  if (isLoading) return <p>로딩 중...</p>;
  if (isError || !data) return <p role="alert" className="text-destructive">계정 정보를 불러오지 못했습니다.</p>;

  return (
    <div className="flex flex-col gap-6">
      <header className="flex items-center gap-4">
        <h1 className="text-xl font-semibold">{data.email}</h1>
        <Badge>{data.status}</Badge>
      </header>

      <dl className="grid grid-cols-2 gap-2 text-sm">
        <dt className="text-muted-foreground">ID</dt>
        <dd>{data.id}</dd>
        <dt className="text-muted-foreground">가입일</dt>
        <dd>{data.createdAt}</dd>
        <dt className="text-muted-foreground">최근 로그인</dt>
        <dd>{data.lastLoginAt ?? '—'}</dd>
      </dl>

      <section>
        <h2 className="mb-2 text-sm font-semibold">최근 로그인 이력</h2>
        <ul className="text-sm">
          {data.recentLogins.length === 0 ? <li>이력 없음</li> : null}
          {data.recentLogins.map((h) => (
            <li key={h.eventId}>
              {h.occurredAt} — {h.outcome} ({h.ipMasked ?? '-'}, {h.geoCountry ?? '-'})
            </li>
          ))}
        </ul>
      </section>

      <RoleGuard roles={roles} allow={['SUPER_ADMIN', 'ACCOUNT_ADMIN']}>
        <div className="flex gap-2">
          <Button variant="destructive" onClick={() => setLockOpen(true)} disabled={data.status === 'LOCKED'}>
            잠금
          </Button>
          <Button variant="outline" onClick={() => setUnlockOpen(true)} disabled={data.status !== 'LOCKED'}>
            해제
          </Button>
        </div>
      </RoleGuard>

      <LockDialog open={lockOpen} onOpenChange={setLockOpen} accountId={accountId} />
      <UnlockDialog open={unlockOpen} onOpenChange={setUnlockOpen} accountId={accountId} />
    </div>
  );
}
