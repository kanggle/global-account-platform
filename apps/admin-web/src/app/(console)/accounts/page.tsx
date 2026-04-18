import { AccountSearchForm } from '@/features/accounts/components/AccountSearchForm';
import { BulkLockButton } from '@/features/accounts/components/BulkLockButton';

export const metadata = { title: '계정 검색 — Admin Console' };

export default function AccountsPage() {
  return (
    <section className="flex flex-col gap-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">계정 검색</h1>
        <BulkLockButton />
      </div>
      <AccountSearchForm />
    </section>
  );
}
