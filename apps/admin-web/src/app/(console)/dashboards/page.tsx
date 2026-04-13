import { getServerEnv } from '@/shared/config/env';

export const metadata = { title: '대시보드 — Admin Console' };

export default function DashboardsPage() {
  const env = getServerEnv();
  return (
    <section className="flex h-full flex-col gap-4">
      <h1 className="text-xl font-semibold">대시보드</h1>
      <iframe
        title="Grafana dashboard"
        src={env.GRAFANA_DASHBOARD_URL}
        className="h-[80vh] w-full border border-border"
      />
    </section>
  );
}
