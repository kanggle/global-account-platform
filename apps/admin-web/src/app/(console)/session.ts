import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { OperatorSessionSchema, type OperatorSession } from '@/shared/api/admin-api';
import { clientEnv } from '@/shared/config/env';
import { logger, newRequestId } from '@/shared/lib/logger';

/**
 * Resolve the current operator session on the server.
 * Reads the HttpOnly accessToken cookie, forwards it to `admin-service /me`
 * on the gateway, and parses the response through zod.
 *
 * If the session is missing / invalid, redirects to `/login` with redirect query.
 */
export async function requireOperatorSession(redirectTo: string): Promise<OperatorSession> {
  const cookieStore = await cookies();
  const access = cookieStore.get('accessToken')?.value;
  if (!access) redirect(`/login?redirect=${encodeURIComponent(redirectTo)}`);

  const requestId = newRequestId();
  try {
    const res = await fetch(`${clientEnv.NEXT_PUBLIC_API_BASE_URL}/api/admin/me`, {
      headers: {
        Authorization: `Bearer ${access}`,
        'X-Request-Id': requestId,
      },
      cache: 'no-store',
    });
    if (!res.ok) {
      logger.warn('operator_session_unauthorized', { requestId, status: res.status });
      redirect(`/login?redirect=${encodeURIComponent(redirectTo)}`);
    }
    const data = await res.json();
    return OperatorSessionSchema.parse(data);
  } catch (err) {
    logger.error('operator_session_fetch_failed', { requestId, err: String(err) });
    redirect(`/login?redirect=${encodeURIComponent(redirectTo)}`);
  }
}
