# HTTP Contract: gateway-service

게이트웨이는 자체 비즈니스 엔드포인트가 없다. 아래는 **라우트 매핑 + 게이트웨이 자체 응답** 정의.

---

## Route Map

| Public Path | Target Service | 인증 필요 | 비고 |
|---|---|---|---|
| `POST /api/auth/login` | auth-service | No | — |
| `POST /api/auth/logout` | auth-service | Yes (access token) | — |
| `POST /api/auth/refresh` | auth-service | No (refresh token in body) | — |
| `POST /api/accounts/signup` | account-service | No | — |
| `GET /api/accounts/me` | account-service | Yes | — |
| `PATCH /api/accounts/me/profile` | account-service | Yes | — |
| `GET /api/accounts/me/status` | account-service | Yes | — |
| `POST /api/admin/accounts/{id}/lock` | admin-service | Yes (operator token) | 별도 인증 필터 |
| `POST /api/admin/accounts/{id}/unlock` | admin-service | Yes (operator token) | 별도 인증 필터 |
| `POST /api/admin/sessions/{id}/revoke` | admin-service | Yes (operator token) | 별도 인증 필터 |
| `GET /api/admin/audit` | admin-service | Yes (operator token) | 별도 인증 필터 |
| `GET /actuator/health` | gateway 자체 | No | 헬스체크 |

---

## Gateway-Generated Responses

게이트웨이가 다운스트림 없이 직접 반환하는 응답들.

### 401 Unauthorized — JWT 검증 실패

```json
{
  "code": "TOKEN_INVALID",
  "message": "Access token is missing, expired, or has an invalid signature",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

발생 조건:
- `Authorization: Bearer <token>` 헤더 없음 (인증 필수 경로)
- JWT 서명 불일치 (JWKS kid mismatch 포함)
- JWT `exp` 만료
- JWT `nbf` 미도래

### 429 Too Many Requests — Rate Limit 초과

```json
{
  "code": "RATE_LIMITED",
  "message": "Too many requests. Try again later.",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

헤더: `Retry-After: <seconds>`

발생 조건: 토큰 버킷 소진 (scope별 — [redis-keys.md](../../services/gateway-service/redis-keys.md) 참조)

### 503 Service Unavailable — 다운스트림 장애

```json
{
  "code": "SERVICE_UNAVAILABLE",
  "message": "Downstream service is temporarily unavailable",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

발생 조건: circuit breaker open 또는 다운스트림 타임아웃

### 504 Gateway Timeout

```json
{
  "code": "GATEWAY_TIMEOUT",
  "message": "Downstream service did not respond in time",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

---

## Request Headers (gateway가 주입)

| 헤더 | 설명 |
|---|---|
| `X-Request-ID` | 없으면 UUID 생성, 있으면 전파 |
| `X-Account-ID` | JWT에서 추출한 account_id (인증 성공 시) |
| `X-Forwarded-For` | 원본 client IP |

다운스트림은 이 헤더를 신뢰한다. 외부에서 `X-Account-ID`를 직접 보내는 경우 **게이트웨이가 덮어씀** (spoofing 방지).

---

## CORS Policy

- Allowed origins: `CORS_ALLOWED_ORIGINS` 환경 변수 (콤마 구분)
- Allowed methods: `GET, POST, PATCH, DELETE, OPTIONS`
- Allowed headers: `Authorization, Content-Type, X-Request-ID, Idempotency-Key`
- Max age: 3600초
