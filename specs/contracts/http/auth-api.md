# HTTP Contract: auth-service (Public API)

모든 엔드포인트는 gateway 경유. base path: `/api/auth`

---

## POST /api/auth/login

사용자 로그인. 이메일·패스워드를 검증하고 JWT access/refresh token pair를 발급한다.

**Auth required**: No

**Request**:
```json
{
  "email": "string (required, email format)",
  "password": "string (required, min 8)"
}
```

**Response 200**:
```json
{
  "accessToken": "string (JWT)",
  "refreshToken": "string (JWT)",
  "expiresIn": 1800,
  "tokenType": "Bearer"
}
```

**Errors**:

| Status | Code | 조건 |
|---|---|---|
| 401 | `CREDENTIALS_INVALID` | 이메일 미존재 또는 패스워드 불일치. **구체 원인 노출 금지** ([rules/domains/saas.md](../../../rules/domains/saas.md)) |
| 403 | `ACCOUNT_LOCKED` | 계정 잠김 상태 |
| 403 | `ACCOUNT_DORMANT` | 휴면 상태 (별도 복구 흐름 필요) |
| 403 | `ACCOUNT_DELETED` | 삭제된 계정 |
| 429 | `LOGIN_RATE_LIMITED` | 로그인 실패 횟수 초과 (Redis 카운터) |
| 422 | `VALIDATION_ERROR` | 이메일/패스워드 형식 오류 |

**Side Effects**:
- 성공: `auth.login.succeeded` 이벤트 발행 (outbox)
- 실패: `auth.login.failed` 이벤트 발행, Redis 실패 카운터 증가
- 모든 시도: `auth.login.attempted` 이벤트 발행

---

## POST /api/auth/logout

현재 세션 종료. refresh token을 블랙리스트에 등록한다.

**Auth required**: Yes (access token)

**Request**:
```json
{
  "refreshToken": "string (required, 현재 세션의 refresh token)"
}
```

**Response 204**: No Content

**Errors**:

| Status | Code | 조건 |
|---|---|---|
| 401 | `TOKEN_INVALID` | access token 만료/변조 |
| 400 | `VALIDATION_ERROR` | refreshToken 누락 |

**Side Effects**: `refresh:blacklist:{jti}` Redis SET

---

## POST /api/auth/refresh

Refresh token rotation. 기존 refresh token을 소비하고 새 access/refresh pair를 발급한다.

**Auth required**: No (refresh token을 body로 전달)

**Request**:
```json
{
  "refreshToken": "string (required)"
}
```

**Response 200**:
```json
{
  "accessToken": "string (JWT, new)",
  "refreshToken": "string (JWT, new — 기존 token은 즉시 무효)",
  "expiresIn": 1800,
  "tokenType": "Bearer"
}
```

**Errors**:

| Status | Code | 조건 |
|---|---|---|
| 401 | `TOKEN_EXPIRED` | refresh token 만료 |
| 401 | `TOKEN_REUSE_DETECTED` | 이미 rotation된 refresh token 재사용. **해당 account의 모든 세션 즉시 무효화** |
| 401 | `SESSION_REVOKED` | 명시적으로 revoke된 세션 |
| 403 | `ACCOUNT_LOCKED` | 계정 잠김 (refresh 차단) |

**Side Effects**:
- 성공: `auth.token.refreshed` 이벤트, DB에 새 refresh_token row + 기존 row의 `rotated_from` 체인 갱신
- 재사용 탐지: `auth.token.reuse.detected` 이벤트 + 해당 account의 모든 refresh_tokens `revoked=TRUE`

---

## Token Specification

### Access Token (JWT)

| Claim | 값 |
|---|---|
| `sub` | account_id (UUID) |
| `iss` | `global-account-platform` |
| `iat` | 발급 시각 (epoch seconds) |
| `exp` | `iat + 1800` (30분) |
| `jti` | UUID |
| `scope` | `user` (일반 사용자) 또는 `admin` (운영자) |

서명: RS256. 공개 키는 JWKS 엔드포인트로 배포.

### Refresh Token (JWT)

| Claim | 값 |
|---|---|
| `sub` | account_id |
| `jti` | UUID (DB의 `refresh_tokens.jti`와 일치) |
| `iat` | 발급 시각 |
| `exp` | `iat + 604800` (7일) |
| `type` | `refresh` |

---

## Common Error Format

모든 에러 응답:

```json
{
  "code": "UPPER_SNAKE_CASE",
  "message": "Human-readable description (no PII)",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

[platform/error-handling.md](../../../platform/error-handling.md) 표준.
