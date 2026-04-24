import { test, expect } from '@playwright/test';

/**
 * Audit log journey: login → /audit → search → results.
 *
 * Requires a running admin-web + admin-service stack.
 * Skip by default unless E2E_ENABLED=1.
 *
 * To execute:
 *   pnpm dev     # start admin-web
 *   E2E_ENABLED=1 pnpm e2e
 */

const OP_EMAIL = process.env.E2E_OP_EMAIL ?? 'admin@example.com';
const OP_PASSWORD = process.env.E2E_OP_PASSWORD ?? 'password123';

async function loginAsSuperAdmin(page: import('@playwright/test').Page) {
  await page.goto('/login');
  await page.getByLabel('운영자 ID').fill(OP_EMAIL);
  await page.getByLabel('비밀번호').fill(OP_PASSWORD);
  await page.getByRole('button', { name: /로그인/ }).click();
  await expect(page).toHaveURL(/\/accounts/);
}

test.describe('audit log', () => {
  test.skip(!process.env.E2E_ENABLED, 'Set E2E_ENABLED=1 with the stack running');

  test('SUPER_ADMIN can load audit log list', async ({ page }) => {
    await loginAsSuperAdmin(page);

    await page.goto('/audit');
    await expect(page.getByRole('heading', { name: '감사 로그' })).toBeVisible();

    await page.getByRole('button', { name: '조회' }).click();
    await expect(page.locator('table, [role="table"]').first()).toBeVisible();
  });

  test('filtering by ACCOUNT_LOCK action code shows matching rows', async ({ page }) => {
    await loginAsSuperAdmin(page);
    await page.goto('/audit');

    await page.getByLabel('Action Code').fill('ACCOUNT_LOCK');
    await page.getByRole('button', { name: '조회' }).click();

    await expect(page.getByText('ACCOUNT_LOCK').first()).toBeVisible();
  });

  test('operator management actions appear in audit log', async ({ page }) => {
    await loginAsSuperAdmin(page);
    await page.goto('/audit');

    await page.getByLabel('Action Code').fill('OPERATOR_CREATE');
    await page.getByRole('button', { name: '조회' }).click();

    // Assert the filtered results actually contain the requested action code
    // rather than only checking that the table element is visible. The table
    // element is rendered regardless of result contents, so the previous
    // assertion did not verify filtering behaviour.
    await expect(page.getByText('OPERATOR_CREATE').first()).toBeVisible();
  });
});
