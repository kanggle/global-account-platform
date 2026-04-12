# Internal HTTP Contract: auth-service → account-service

auth-service가 로그인 처리 중 account-service를 호출하여 credential을 조회하고 계정 상태를 확인한다.

**호출 방향**: auth-service (client) → account-service (server)
**노출 경로**: `/internal/accounts/*` — 게이트웨이 퍼블릭 라우트에 노출 금지 ([rules/domains/saas.md](../../../rules/domains/saas.md) S2)
**인증**: mTLS 또는 내부 서비스 토큰 (구현 시 결정)

---

## GET /internal/accounts/credentials

이메일로 credential 정보 조회. auth-service가 로그인 시 패스워드 해시를 가져오기 위해 사용.

**Query Parameters**:

| 파라미터 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `email` | string | Yes | 로그인 이메일 |

**Response 200**:
```json
{
  "accountId": "string (UUID)",
  "credentialHash": "string (argon2id hash)",
  "hashAlgorithm": "argon2id",
  "accountStatus": "ACTIVE"
}
```

**Response 404**: 이메일에 해당하는 계정 없음
```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "message": "No account found for the given email",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

**주의**: 이 응답에는 `credentialHash`가 포함된다. **반드시 내부 네트워크에서만** 전송되어야 하며, 로그에 기록하면 안 된다 ([rules/traits/regulated.md](../../../rules/traits/regulated.md) R4). 분류 등급: **restricted**.

---

## GET /internal/accounts/{accountId}/status

특정 계정의 현재 상태 조회. auth-service가 로그인/refresh 시 계정이 여전히 활성 상태인지 확인.

**Path Parameters**:

| 파라미터 | 타입 | 설명 |
|---|---|---|
| `accountId` | string (UUID) | 대상 계정 |

**Response 200**:
```json
{
  "accountId": "string",
  "status": "ACTIVE | LOCKED | DORMANT | DELETED",
  "statusChangedAt": "2026-04-12T10:00:00Z"
}
```

**Response 404**: 계정 미존재
```json
{
  "code": "ACCOUNT_NOT_FOUND",
  "message": "Account not found",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

---

## Caller Constraints (auth-service 측)

- 타임아웃: 연결 3s, 읽기 5s
- 재시도: 2회 (지수 백오프 + jitter). **404는 재시도 금지** (4xx)
- Circuit breaker: 실패율 50% / 10초 → open → 30초 half-open
- account-service 장애 시 **로그인 불가** (fail-closed)
