# HTTP Contract: admin-service (Public API — Operator Only)

운영자 전용. 게이트웨이에서 `/api/admin/*` 경로에 **별도 인증 필터 체인** 적용. 일반 사용자 JWT로는 접근 불가.

base path: `/api/admin`

모든 요청에 필수: `X-Operator-Reason` 헤더 (감사 사유)

---

## POST /api/admin/accounts/{accountId}/lock

계정 강제 잠금.

**Auth required**: Yes (operator token, role: ACCOUNT_ADMIN 이상)

**Headers**:
- `Authorization: Bearer <operator-token>`
- `X-Operator-Reason: string (required, 감사 사유)`
- `Idempotency-Key: string (required)`

**Request**:
```json
{
  "reason": "string (required, 상세 사유)",
  "ticketId": "string (optional, 내부 티켓 번호)"
}
```

**Response 200**:
```json
{
  "accountId": "string",
  "previousStatus": "ACTIVE",
  "currentStatus": "LOCKED",
  "operatorId": "string",
  "lockedAt": "2026-04-12T10:00:00Z",
  "auditId": "string (admin_actions.id)"
}
```

**Errors**:

| Status | Code | 조건 |
|---|---|---|
| 401 | `TOKEN_INVALID` | operator token 만료/변조 |
| 403 | `PERMISSION_DENIED` | role 부족 |
| 400 | `STATE_TRANSITION_INVALID` | 이미 LOCKED 또는 DELETED 상태 |
| 400 | `REASON_REQUIRED` | X-Operator-Reason 또는 body reason 누락 |
| 404 | `ACCOUNT_NOT_FOUND` | 대상 계정 미존재 |
| 502 | `DOWNSTREAM_ERROR` | account-service 호출 실패 |

**Side Effects**: admin_actions 감사 기록 + `admin.action.performed` 이벤트 + account-service에 내부 HTTP lock 명령

---

## POST /api/admin/accounts/{accountId}/unlock

계정 잠금 해제.

**Auth required**: Yes (operator, ACCOUNT_ADMIN+)

**Headers**: Authorization + X-Operator-Reason + Idempotency-Key

**Request**:
```json
{
  "reason": "string (required)",
  "ticketId": "string (optional)"
}
```

**Response 200**:
```json
{
  "accountId": "string",
  "previousStatus": "LOCKED",
  "currentStatus": "ACTIVE",
  "operatorId": "string",
  "unlockedAt": "2026-04-12T10:00:00Z",
  "auditId": "string"
}
```

**Errors**: lock과 동일 구조. `STATE_TRANSITION_INVALID`는 LOCKED가 아닌 상태에서 unlock 시도 시.

---

## POST /api/admin/sessions/{accountId}/revoke

특정 계정의 모든 세션 강제 종료 (refresh token 전체 revoke).

**Auth required**: Yes (operator, ACCOUNT_ADMIN+)

**Headers**: Authorization + X-Operator-Reason + Idempotency-Key

**Request**:
```json
{
  "reason": "string (required)"
}
```

**Response 200**:
```json
{
  "accountId": "string",
  "revokedSessionCount": 3,
  "operatorId": "string",
  "revokedAt": "2026-04-12T10:00:00Z",
  "auditId": "string"
}
```

**Errors**:

| Status | Code | 조건 |
|---|---|---|
| 401 | `TOKEN_INVALID` | — |
| 403 | `PERMISSION_DENIED` | — |
| 404 | `ACCOUNT_NOT_FOUND` | — |
| 502 | `DOWNSTREAM_ERROR` | auth-service 호출 실패 |

**Side Effects**: auth-service에 내부 HTTP force-logout 명령 + admin_actions 기록

---

## GET /api/admin/audit

감사 로그 조회 (통합 뷰: admin_actions + login_history + suspicious_events).

**Auth required**: Yes (operator, AUDITOR 이상)

**Query Parameters**:

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `accountId` | string (optional) | 특정 계정 필터 |
| `actionCode` | string (optional) | ACCOUNT_LOCK, SESSION_REVOKE 등 |
| `from` | ISO 8601 datetime | 시작 시각 |
| `to` | ISO 8601 datetime | 종료 시각 |
| `source` | string (optional) | `admin` / `login_history` / `suspicious` |
| `page` | int (default 0) | — |
| `size` | int (default 20, max 100) | — |

**Response 200**:
```json
{
  "content": [
    {
      "source": "admin",
      "auditId": "string",
      "actionCode": "ACCOUNT_LOCK",
      "operatorId": "string",
      "targetId": "string",
      "reason": "string",
      "outcome": "SUCCESS",
      "occurredAt": "2026-04-12T10:00:00Z"
    },
    {
      "source": "login_history",
      "eventId": "string",
      "accountId": "string",
      "outcome": "FAILURE",
      "ipMasked": "192.168.1.***",
      "geoCountry": "KR",
      "occurredAt": "2026-04-12T09:58:00Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 150,
  "totalPages": 8
}
```

**Errors**:

| Status | Code | 조건 |
|---|---|---|
| 401 | `TOKEN_INVALID` | — |
| 403 | `PERMISSION_DENIED` | AUDITOR role 필요 |
| 422 | `VALIDATION_ERROR` | from > to, size > 100 등 |

**Note**: 이 조회 자체가 **meta-audit**로 기록됨 ([rules/traits/audit-heavy.md](../../../rules/traits/audit-heavy.md) A5). PII는 마스킹됨 (IP 일부, 이메일 미포함).

---

## Operator Roles

| Role | 권한 |
|---|---|
| `SUPER_ADMIN` | 모든 작업 |
| `ACCOUNT_ADMIN` | lock/unlock/revoke + 감사 조회 |
| `AUDITOR` | 감사 조회만 (읽기 전용) |

---

## Common Error Format

```json
{
  "code": "UPPER_SNAKE_CASE",
  "message": "Human-readable (no PII)",
  "timestamp": "2026-04-12T10:00:00Z"
}
```
