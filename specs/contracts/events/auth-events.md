# Event Contract: auth-service

auth-service가 발행하는 모든 Kafka 이벤트. security-service가 primary consumer.

**발행 방식**: Outbox 패턴 — DB 트랜잭션 커밋 후 relay가 Kafka에 발행 ([rules/traits/transactional.md](../../../rules/traits/transactional.md) T3)
**파티션 키**: `account_id` (같은 계정의 이벤트 순서 보장)

---

## Event Envelope (공통)

모든 이벤트는 [libs/java-messaging](../../../libs/java-messaging)의 표준 envelope을 따른다:

```json
{
  "eventId": "string (UUID v7)",
  "eventType": "auth.login.attempted",
  "source": "auth-service",
  "occurredAt": "2026-04-12T10:00:00.123Z",
  "schemaVersion": 1,
  "partitionKey": "string (account_id or email_hash)",
  "payload": { ... }
}
```

---

## auth.login.attempted

모든 로그인 시도에 발행 (성공·실패 불문). security-service가 login_history에 기록.

**Topic**: `auth.login.attempted`

**Payload**:
```json
{
  "accountId": "string | null (미존재 이메일이면 null)",
  "emailHash": "string (SHA256[:10])",
  "ipMasked": "192.168.1.***",
  "userAgentFamily": "Chrome 120",
  "deviceFingerprint": "string (hashed)",
  "geoCountry": "KR",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

**Consumers**: security-service

---

## auth.login.failed

로그인 실패 시 발행. attempted와 별도로 발행되며, 실패 원인을 포함.

**Topic**: `auth.login.failed`

**Payload**:
```json
{
  "accountId": "string | null",
  "emailHash": "string",
  "failureReason": "CREDENTIALS_INVALID | ACCOUNT_LOCKED | ACCOUNT_DORMANT | ACCOUNT_DELETED | RATE_LIMITED",
  "failCount": 3,
  "ipMasked": "192.168.1.***",
  "userAgentFamily": "Chrome 120",
  "deviceFingerprint": "string",
  "geoCountry": "KR",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

**Consumers**: security-service (VelocityRule 평가)

---

## auth.login.succeeded

로그인 성공 시 발행.

**Topic**: `auth.login.succeeded`

**Payload**:
```json
{
  "accountId": "string",
  "ipMasked": "192.168.1.***",
  "userAgentFamily": "Chrome 120",
  "deviceFingerprint": "string",
  "geoCountry": "KR",
  "sessionJti": "string (발급된 refresh token의 jti)",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

**Consumers**: security-service (GeoAnomalyRule, DeviceChangeRule 평가, login_history 기록)

---

## auth.token.refreshed

Refresh token rotation 성공 시 발행.

**Topic**: `auth.token.refreshed`

**Payload**:
```json
{
  "accountId": "string",
  "previousJti": "string (소비된 토큰)",
  "newJti": "string (새로 발급된 토큰)",
  "ipMasked": "192.168.1.***",
  "deviceFingerprint": "string",
  "timestamp": "2026-04-12T10:00:00Z"
}
```

**Consumers**: security-service (login_history에 outcome=REFRESH 기록)

---

## auth.token.reuse.detected

이미 rotation된 refresh token의 재사용 탐지. **보안 critical 이벤트**.

**Topic**: `auth.token.reuse.detected`

**Payload**:
```json
{
  "accountId": "string",
  "reusedJti": "string (재사용 시도된 토큰)",
  "originalRotationAt": "2026-04-12T09:50:00Z",
  "reuseAttemptAt": "2026-04-12T10:00:00Z",
  "ipMasked": "192.168.1.***",
  "deviceFingerprint": "string",
  "sessionsRevoked": true,
  "revokedCount": 5
}
```

**Consumers**: security-service → 즉시 `auto.lock.triggered` 발행 (최고 우선순위)

---

## Consumer Rules

- **멱등 처리 필수**: `eventId`(UUID v7) 기반 dedupe. Redis + MySQL 이중 방어 (T8)
- **순서 보장**: 같은 `account_id`의 이벤트는 같은 파티션에 도착. 교차 계정 순서 보장은 하지 않음
- **schema tolerance**: 알 수 없는 필드는 무시 (forward-compatible). `schemaVersion`이 지원 범위 밖이면 DLQ로 이관
- **DLQ**: `<topic>.dlq`. 3회 지수 백오프 재시도 후 이관 ([rules/traits/integration-heavy.md](../../../rules/traits/integration-heavy.md) I5)
- **trace propagation**: envelope 또는 Kafka 헤더의 `traceparent`를 MDC로 복원
