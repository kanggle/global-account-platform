import { z } from 'zod';

/**
 * Manual TypeScript types + zod schemas derived from
 * `specs/contracts/http/admin-api.md`. Keep in sync when the contract changes.
 */

export const AccountStatusSchema = z.enum(['ACTIVE', 'LOCKED', 'DORMANT', 'DELETED']);
export type AccountStatus = z.infer<typeof AccountStatusSchema>;

export const OperatorRoleSchema = z.enum(['SUPER_ADMIN', 'ACCOUNT_ADMIN', 'AUDITOR']);
export type OperatorRole = z.infer<typeof OperatorRoleSchema>;

// ---- Lock / Unlock ----
export const LockRequestSchema = z.object({
  reason: z.string().min(1),
  ticketId: z.string().optional(),
});
export type LockRequest = z.infer<typeof LockRequestSchema>;

export const LockResponseSchema = z.object({
  accountId: z.string(),
  previousStatus: AccountStatusSchema,
  currentStatus: AccountStatusSchema,
  operatorId: z.string(),
  lockedAt: z.string(),
  auditId: z.string(),
});
export type LockResponse = z.infer<typeof LockResponseSchema>;

export const UnlockResponseSchema = LockResponseSchema.omit({ lockedAt: true }).extend({
  unlockedAt: z.string(),
});
export type UnlockResponse = z.infer<typeof UnlockResponseSchema>;

// ---- Session revoke ----
export const RevokeResponseSchema = z.object({
  accountId: z.string(),
  revokedSessionCount: z.number().int().nonnegative(),
  operatorId: z.string(),
  revokedAt: z.string(),
  auditId: z.string(),
});
export type RevokeResponse = z.infer<typeof RevokeResponseSchema>;

// ---- Audit ----
export const AuditAdminEntrySchema = z.object({
  source: z.literal('admin'),
  auditId: z.string(),
  actionCode: z.string(),
  operatorId: z.string(),
  targetId: z.string(),
  reason: z.string(),
  outcome: z.enum(['SUCCESS', 'FAILURE']),
  occurredAt: z.string(),
});

export const AuditLoginEntrySchema = z.object({
  source: z.literal('login_history'),
  eventId: z.string(),
  accountId: z.string(),
  outcome: z.enum(['SUCCESS', 'FAILURE']),
  ipMasked: z.string().optional(),
  geoCountry: z.string().optional(),
  occurredAt: z.string(),
});

export const AuditSuspiciousEntrySchema = z.object({
  source: z.literal('suspicious'),
  eventId: z.string(),
  accountId: z.string().optional(),
  reasonCode: z.string().optional(),
  occurredAt: z.string(),
});

export const AuditEntrySchema = z.discriminatedUnion('source', [
  AuditAdminEntrySchema,
  AuditLoginEntrySchema,
  AuditSuspiciousEntrySchema,
]);
export type AuditEntry = z.infer<typeof AuditEntrySchema>;

export const AuditPageSchema = z.object({
  content: z.array(AuditEntrySchema),
  page: z.number().int().nonnegative(),
  size: z.number().int().positive(),
  totalElements: z.number().int().nonnegative(),
  totalPages: z.number().int().nonnegative(),
});
export type AuditPage = z.infer<typeof AuditPageSchema>;

// ---- Accounts (admin-service / account-service projection) ----
export const AccountSummarySchema = z.object({
  id: z.string(),
  email: z.string(),
  status: AccountStatusSchema,
  createdAt: z.string(),
  lastLoginAt: z.string().optional(),
});
export type AccountSummary = z.infer<typeof AccountSummarySchema>;

export const AccountDetailSchema = AccountSummarySchema.extend({
  profile: z
    .object({
      displayName: z.string().optional(),
      phoneMasked: z.string().optional(),
    })
    .optional(),
  recentLogins: z.array(AuditLoginEntrySchema).default([]),
});
export type AccountDetail = z.infer<typeof AccountDetailSchema>;

// ---- Operator session (/me) ----
export const OperatorSessionSchema = z.object({
  operatorId: z.string(),
  email: z.string(),
  roles: z.array(OperatorRoleSchema),
});
export type OperatorSession = z.infer<typeof OperatorSessionSchema>;

// ---- Error envelope ----
export const ApiErrorBodySchema = z.object({
  code: z.string(),
  message: z.string(),
  timestamp: z.string().optional(),
});
export type ApiErrorBody = z.infer<typeof ApiErrorBodySchema>;
