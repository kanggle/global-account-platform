# Task ID

TASK-BE-024

# Title

auth-service — device_session revoke 시 연결 refresh token cascade revoke (reuse 경로와 멱등 통합)

# Status

backlog

# Owner

backend

# Task Tags

- code
- db

# depends_on

- TASK-BE-023

---

# Goal

`device_sessions.revoked_at` 설정과 `refresh_tokens.revoked_at` 설정을 단일 경로로 통합한다. reuse-detection이 이미 revoke한 토큰을 device-level revoke가 다시 건드려도 멱등이어야 한다.

---

# Scope

## In Scope

- `RevokeRefreshTokenUseCase` 확장: `by = DEVICE_REVOKE | REUSE_DETECTION | USER_LOGOUT | ADMIN_FORCE_LOGOUT` reason 태그
- `RevokeSessionUseCase`가 해당 device의 refresh token들을 찾아 일괄 revoke (이미 revoked면 skip)
- `refresh_tokens.device_fingerprint` 컬럼을 deprecated로 표시하고 `device_id` 조인 경로 추가 (마이그레이션: `device_id` 컬럼 추가, 백필, index). 컬럼 DROP은 이후 릴리즈에서 수행 — 이번 태스크는 shadow write만.

## Out of Scope

- `device_fingerprint` 컬럼 DROP
- DeviceChangeRule 변경

---

# Acceptance Criteria

- [ ] `device_sessions.revoked_at` 설정 시 연결된 refresh_tokens 모두 revoked_at 설정 (이미 revoked면 변경 없음)
- [ ] reuse-detection이 먼저 revoke한 토큰에 device-revoke가 이어져도 예외 없음
- [ ] 신규 refresh token 발급 시 `device_id`가 채워짐 (shadow write)
- [ ] 기존 `device_fingerprint` 기반 조회 경로 여전히 동작 (backward compat)

---

# Related Specs

- `specs/services/auth-service/device-session.md`
- `specs/services/auth-service/architecture.md`

# Related Contracts

- `specs/contracts/events/auth-events.md`

---

# Target Service

- `apps/auth-service`

---

# Edge Cases

- 동일 refresh token에 대해 device-revoke와 reuse-revoke가 동시 발생 — 낙관적 락 또는 UPDATE ... WHERE revoked_at IS NULL로 해결

---

# Failure Scenarios

- 백필 마이그레이션 실패 시 `device_id`가 NULL인 기존 row는 null로 유지, 쿼리는 fingerprint fallback

---

# Test Requirements

- Unit: `RevokeRefreshTokenUseCase` 멱등성
- Integration: reuse → device revoke 순차 시나리오

---

# Definition of Done

- [ ] Implementation completed
- [ ] Tests added and passing
- [ ] Ready for review
