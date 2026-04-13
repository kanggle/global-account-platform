# HTTP Contract: admin-service (Public API — Operator Only)

운영자 전용. 게이트웨이에서 `/api/admin/*` 경로에 **별도 인증 필터 체인** 적용. 일반 사용자 JWT로는 접근 불가.

base path: `/api/admin`

모든 요청에 필수: `X-Operator-Reason` 헤더 (감사 사유)

---

## Authorization Model

모든 mutation 및 read endpoint는 필요한 **permission key**를 선언한다 ([specs/services/admin-service/rbac.md](../../services/admin-service/rbac.md)). 운영자의 role 집합이 보유한 permission 합집합에 요청 endpoint의 permission이 포함되어야 통과한다. 누락 시 `403 PERMISSION_DENIED`.

- Permission key catalog: `account.lock`, `account.unlock`, `account.force_logout`, `audit.read`, `security.event.read`
- Annotation이 선언되지 않은 endpoint는 **fail-closed로 deny**되며 `admin_actions`에 `outcome=DENIED, permission_used="<missing>"` 기록
- 권한 거부는 request 단위로 감사 row 1건 기록 (dedup 없음)
- Operator 식별자는 JWT `sub` 클레임에서 추출 (`token_type = "admin"` 필수)

---

## POST /api/admin/accounts/{accountId}/lock

계정 강제 잠금.

**Auth required**: Yes (operator token, `token_type=admin`)
**Required permission**: `account.lock`
**Granted to roles**: `SUPER_ADMIN`, `SUPPORT_LOCK`

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

**Auth required**: Yes (operator token, `token_type=admin`)
**Required permission**: `account.unlock`
**Granted to roles**: `SUPER_ADMIN`, `SUPPORT_LOCK`

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

**Auth required**: Yes (operator token, `token_type=admin`)
**Required permission**: `account.force_logout`
**Granted to roles**: `SUPER_ADMIN`, `SUPPORT_LOCK`, `SECURITY_ANALYST`

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

**Auth required**: Yes (operator token, `token_type=admin`)
**Required permission**: `audit.read` (기본). `source=login_history` 또는 `source=suspicious` 필터 사용 시 `security.event.read`도 **추가로** 요구 (union이 아닌 intersection 검증 — 두 권한 모두 필요)
**Granted to roles**:
- `audit.read` only: `SUPPORT_LOCK` (admin_actions만 조회 가능, security source 필터 사용 시 403)
- `audit.read` + `security.event.read`: `SUPER_ADMIN`, `SUPPORT_READONLY`, `SECURITY_ANALYST` (모든 source 조회 가능)

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
      "ipMasked": "192.168.*.*",
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
| 403 | `PERMISSION_DENIED` | `audit.read` 또는 source별 추가 권한(`security.event.read`) 부족 |
| 422 | `VALIDATION_ERROR` | from > to, size > 100 등 |

**Note**: 이 조회 자체가 **meta-audit**로 기록됨 ([rules/traits/audit-heavy.md](../../../rules/traits/audit-heavy.md) A5). PII는 마스킹됨 (IP 일부, 이메일 미포함).

---

## Operator Roles

**Reference matrix**: Role × permission 매트릭스의 canonical source는 [specs/services/admin-service/rbac.md — Seed Matrix](../../services/admin-service/rbac.md#seed-matrix-role--permission) 한 곳이다. 본 계약에서는 중복 테이블을 유지하지 않는다 (drift 방지).

### 403 Response Shape

`PERMISSION_DENIED` 응답은 본 문서의 [Common Error Format](#common-error-format)을 따른다. permission 관련 추가 필드는 응답에 **노출하지 않는다** (attack surface 축소 — 클라이언트는 어떤 permission이 누락되었는지 알 수 없다). 거부 상세는 `admin_actions.detail`에만 기록된다.

```json
{
  "code": "PERMISSION_DENIED",
  "message": "Operator is not authorized to perform this action.",
  "timestamp": "2026-04-13T10:00:00Z"
}
```

---

## Common Error Format

```json
{
  "code": "UPPER_SNAKE_CASE",
  "message": "Human-readable (no PII)",
  "timestamp": "2026-04-12T10:00:00Z"
}
```
