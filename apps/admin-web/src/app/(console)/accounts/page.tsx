import { AccountSearchForm } from '@/features/accounts/components/AccountSearchForm';

export const metadata = { title: '계정 검색 — Admin Console' };

export default function AccountsPage() {
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">계정 검색</h1>
      <AccountSearchForm />
    </section>
  );
}
