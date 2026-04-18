'use client';

import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useRouter, useSearchParams } from 'next/navigation';
import { LoginSchema, type LoginInput } from '../schemas';
import { Button } from '@/shared/ui/button';
import { Input } from '@/shared/ui/input';
import { Label } from '@/shared/ui/label';
import { apiClient } from '@/shared/api/client';
import { ApiError, messageForCode } from '@/shared/api/errors';
import { TotpEnrollment } from './TotpEnrollment';

export function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [enrollmentToken, setEnrollmentToken] = useState<string | null>(null);
  const [credentials, setCredentials] = useState<LoginInput | null>(null);

  const form = useForm<LoginInput>({
    resolver: zodResolver(LoginSchema),
    defaultValues: { email: '', password: '' },
    mode: 'onBlur',
  });

  async function onSubmit(values: LoginInput) {
    setSubmitError(null);
    try {
      await apiClient.post('/api/auth/login', values, { skipAuthRetry: true });
      const redirect = params?.get('redirect') ?? '/accounts';
      router.push(redirect);
      router.refresh();
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.code === 'ENROLLMENT_REQUIRED' && err.extra.bootstrapToken) {
          setCredentials(values);
          setEnrollmentToken(err.extra.bootstrapToken as string);
          return;
        }
        setSubmitError(messageForCode(err.code, err.message));
      } else {
        setSubmitError('네트워크 오류가 발생했습니다.');
      }
    }
  }

  async function handleEnrollmentComplete() {
    // After 2FA enrollment + verify, re-login with the original credentials
    if (!credentials) return;
    try {
      await apiClient.post('/api/auth/login', credentials, { skipAuthRetry: true });
      const redirect = params?.get('redirect') ?? '/accounts';
      router.push(redirect);
      router.refresh();
    } catch {
      // If re-login fails, redirect to login page to retry
      setEnrollmentToken(null);
      setCredentials(null);
      setSubmitError('2FA 등록이 완료되었습니다. 다시 로그인해주세요.');
    }
  }

  if (enrollmentToken) {
    return (
      <TotpEnrollment
        bootstrapToken={enrollmentToken}
        onComplete={handleEnrollmentComplete}
      />
    );
  }

  return (
    <form
      aria-label="operator-login"
      onSubmit={form.handleSubmit(onSubmit)}
      className="flex w-full max-w-sm flex-col gap-4"
      noValidate
    >
      <div className="flex flex-col gap-1">
        <Label htmlFor="email">이메일</Label>
        <Input id="email" type="email" autoComplete="username" {...form.register('email')} />
        {form.formState.errors.email ? (
          <p role="alert" className="text-xs text-destructive">
            {form.formState.errors.email.message}
          </p>
        ) : null}
      </div>
      <div className="flex flex-col gap-1">
        <Label htmlFor="password">비밀번호</Label>
        <Input id="password" type="password" autoComplete="current-password" {...form.register('password')} />
        {form.formState.errors.password ? (
          <p role="alert" className="text-xs text-destructive">
            {form.formState.errors.password.message}
          </p>
        ) : null}
      </div>
      {submitError ? (
        <p role="alert" className="text-sm text-destructive">
          {submitError}
        </p>
      ) : null}
      <Button type="submit" disabled={form.formState.isSubmitting}>
        {form.formState.isSubmitting ? '로그인 중...' : '로그인'}
      </Button>
    </form>
  );
}
