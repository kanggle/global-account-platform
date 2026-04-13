'use client';

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import Link from 'next/link';
import { AccountSearchSchema, type AccountSearchInput } from '../schemas';
import { useAccountSearch } from '../hooks/useAccountSearch';
import { Button } from '@/shared/ui/button';
import { Input } from '@/shared/ui/input';
import { Label } from '@/shared/ui/label';
import { Table, THead, TBody, TR, TH, TD } from '@/shared/ui/table';
import { Badge } from '@/shared/ui/badge';

export function AccountSearchForm() {
  const [email, setEmail] = useState<string | undefined>();
  const form = useForm<AccountSearchInput>({
    resolver: zodResolver(AccountSearchSchema),
    defaultValues: { email: '' },
    mode: 'onBlur',
  });
  const query = useAccountSearch(email);

  return (
    <div className="flex flex-col gap-6">
      <form
        aria-label="account-search"
        onSubmit={form.handleSubmit((v) => setEmail(v.email))}
        className="flex items-end gap-2"
        noValidate
      >
        <div className="flex flex-col gap-1">
          <Label htmlFor="search-email">이메일</Label>
          <Input id="search-email" type="email" {...form.register('email')} />
          {form.formState.errors.email ? (
            <p role="alert" className="text-xs text-destructive">
              {form.formState.errors.email.message}
            </p>
          ) : null}
        </div>
        <Button type="submit">검색</Button>
      </form>

      {query.isLoading ? <p>검색 중...</p> : null}
      {query.isError ? (
        <p role="alert" className="text-sm text-destructive">
          조회에 실패했습니다.
        </p>
      ) : null}
      {query.data && query.data.length === 0 ? <p>결과가 없습니다.</p> : null}

      {query.data && query.data.length > 0 ? (
        <Table>
          <THead>
            <TR>
              <TH>ID</TH>
              <TH>이메일</TH>
              <TH>상태</TH>
              <TH>가입일</TH>
              <TH />
            </TR>
          </THead>
          <TBody>
            {query.data.map((a) => (
              <TR key={a.id}>
                <TD>{a.id}</TD>
                <TD>{a.email}</TD>
                <TD><Badge>{a.status}</Badge></TD>
                <TD>{a.createdAt}</TD>
                <TD>
                  <Link className="text-primary underline" href={`/accounts/${a.id}`}>
                    상세
                  </Link>
                </TD>
              </TR>
            ))}
          </TBody>
        </Table>
      ) : null}
    </div>
  );
}
