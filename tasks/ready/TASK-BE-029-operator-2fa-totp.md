# Task ID

TASK-BE-029

# Title

admin-service — operator 2FA (TOTP) enrollment 및 로그인 강제

# Status

backlog

# Owner

backend

# Task Tags

- code
- api
- db

# depends_on

- TASK-BE-028

---

# Goal

admin operator 로그인에 TOTP 2FA를 추가한다. role 단위로 enforcement 플래그를 두어 SUPER_ADMIN, SECURITY_ANALYST는 필수로 한다.

---

# Scope

## In Scope

- Flyway 마이그레이션: `admin_operator_totp` (operator_id, secret_encrypted, recovery_codes_hashed, enrolled_at, last_used_at), `admin_roles.require_2fa` boolean
- 엔드포인트:
  - `POST /api/admin/auth/2fa/enroll` — secret 생성, QR URI 반환
  - `POST /api/admin/auth/2fa/verify` — enrollment 확정
  - `POST /api/admin/auth/login` 확장 — role에 require_2fa=true면 TOTP 코드 필수
- recovery codes 10개, one-time-use, hashed 저장
- `admin_actions`에 `2fa_used` 기록

## Out of Scope

- WebAuthn / passkey
- SMS OTP
- 공개 사용자 2FA

---

# Acceptance Criteria

- [ ] SUPER_ADMIN이 2FA 미등록 상태로 로그인 시도 → enrollment 강제 플로우
- [ ] enrollment 후 TOTP 코드 없이 로그인 → 401
- [ ] 유효 TOTP로 로그인 → 성공, `admin_actions.2fa_used=TRUE`
- [ ] recovery code 1회 사용 후 재사용 → 401

---

# Related Specs

- `specs/services/admin-service/rbac.md`
- `specs/services/admin-service/architecture.md`

# Related Contracts

- `specs/contracts/http/admin-api.md`

---

# Target Service

- `apps/admin-service`

---

# Edge Cases

- clock skew ±30s 허용
- secret은 AES-GCM 암호화 저장 (key는 KMS/Vault, 현 단계에서는 application secret placeholder)

---

# Failure Scenarios

- recovery code DB 무결성 손상 → 관리자 개입 필요, 자동 복구 없음

---

# Test Requirements

- Unit: TOTP 검증 (TimeBasedOneTimePasswordUtil 계열)
- Integration: enrollment + login + recovery flow

---

# Definition of Done

- [ ] Implementation completed
- [ ] Tests added and passing
- [ ] Ready for review
