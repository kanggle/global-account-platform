import { Suspense } from 'react';
import { LoginForm } from '@/features/auth/components/LoginForm';

export const metadata = { title: '로그인 — Admin Console' };
export const dynamic = 'force-dynamic';

export default function LoginPage() {
  return (
    <main className="flex min-h-screen items-center justify-center p-6">
      <div className="flex w-full max-w-sm flex-col gap-6">
        <h1 className="text-2xl font-semibold">운영자 로그인</h1>
        <Suspense fallback={<div>로그인 폼 로딩...</div>}>
          <LoginForm />
        </Suspense>
      </div>
    </main>
  );
}
