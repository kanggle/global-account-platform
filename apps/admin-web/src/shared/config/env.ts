import { z } from 'zod';

const ClientEnvSchema = z.object({
  NEXT_PUBLIC_API_BASE_URL: z.string().url().default('http://localhost:8080'),
  NEXT_PUBLIC_GRAFANA_BASE_URL: z.string().url().default('https://grafana.internal/d/admin-web'),
});

const ServerEnvSchema = ClientEnvSchema.extend({
  LOG_LEVEL: z.enum(['debug', 'info', 'warn', 'error']).default('info'),
});

export const clientEnv = ClientEnvSchema.parse({
  NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL,
  NEXT_PUBLIC_GRAFANA_BASE_URL: process.env.NEXT_PUBLIC_GRAFANA_BASE_URL,
});

export function getServerEnv() {
  return ServerEnvSchema.parse({
    NEXT_PUBLIC_API_BASE_URL: process.env.NEXT_PUBLIC_API_BASE_URL,
    NEXT_PUBLIC_GRAFANA_BASE_URL: process.env.NEXT_PUBLIC_GRAFANA_BASE_URL,
    LOG_LEVEL: process.env.LOG_LEVEL,
  });
}
