import { OperatorInfo } from '@/features/auth/components/OperatorInfo';

export const metadata = { title: '운영자 정보 — Admin Console' };

export default function OperatorsPage() {
  return (
    <section className="flex flex-col gap-4">
      <h1 className="text-xl font-semibold">운영자 정보</h1>
      <OperatorInfo />
    </section>
  );
}
