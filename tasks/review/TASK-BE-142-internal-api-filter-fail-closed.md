# Task ID

TASK-BE-142

# Title

InternalApiFilter 빈 토큰 시 fail-closed 로 전환 (Critical C-3)

# Status

review

# Owner

backend

# Task Tags

- security
- critical

---

# Required Sections (must exist)

- Goal
- Scope (in/out)
- Acceptance Criteria
- Related Specs
- Related Contracts
- Edge Cases
- Failure Scenarios

---

# Goal

2026-04-27 보안 리뷰(C-3, Critical): `InternalApiFilter` 가 토큰 미설정 시 모든 `/internal/**` 요청을 통과시킴.

- `apps/account-service/src/main/java/com/example/account/infrastructure/config/InternalApiFilter.java:32-33`
- 동일 패턴이 `membership-service`, `security-service` 에도 존재
- 코멘트 명시: "When not configured, skip (dev/test mode)"
- `application.yml` 의 `internal.api.token` 기본값 공백

`/internal/**` 엔드포인트는 GDPR 삭제, 계정 잠금/해제, 관리자 검색(이메일 노출), 소셜 가입 등 민감 작업을 포함. pod-to-pod 네트워크가 방화벽 안 된 컨테이너 환경에서는 외부 호출자가 인증 없이 호출 가능.

본 태스크는 모든 InternalApiFilter 를 fail-closed 로 전환하고, 비-test 프로파일에서 토큰 미설정 시 부팅 자체를 거부한다.

---

# Scope

## In Scope

- `apps/account-service/.../InternalApiFilter.java`
- `apps/membership-service/.../InternalApiFilter.java`
- `apps/security-service/.../InternalApiFilter.java`
- 다른 서비스에 동일 필터가 있다면 그것도 포함 (전수 grep 으로 확인)
- 동작 변경:
  - 토큰 공백/null + `/internal/**` 요청 → 401 반환
  - 토큰 설정됨 + 헤더 일치 → 통과 (기존)
  - 토큰 설정됨 + 헤더 불일치/누락 → 401 (기존)
- 부팅 가드: `@PostConstruct` 에서 비-test 프로파일이면서 토큰 공백이면 `IllegalStateException`

## Out of Scope

- `/internal/**` 엔드포인트 자체의 인가 로직 변경 없음
- 새로운 인증 메커니즘(예: mTLS) 도입은 별도 태스크

---

# Acceptance Criteria

- [ ] 3개 서비스 모두 `InternalApiFilter` 가 토큰 공백 시 401 반환
- [ ] `application.yml` 의 `internal.api.token` 기본값을 빈 문자열에서 placeholder(예: `""` → 미설정 의도 명시) 또는 제거하고 env 만 받도록 변경
- [ ] 비-test 프로파일 + 빈 토큰 → 부팅 시 `IllegalStateException`
- [ ] test 프로파일은 토큰 없이도 정상 부팅 (기존 통합 테스트 유지)
- [ ] 단위 테스트: 빈 토큰 + `/internal/**` 요청 → 401 검증
- [ ] `:apps:account-service:test`, `:apps:membership-service:test`, `:apps:security-service:test` 통과

---

# Related Specs

- `specs/services/account-service/architecture.md`
- `specs/services/membership-service/architecture.md`
- `specs/services/security-service/architecture.md`

# Related Skills

- `.claude/skills/backend/security/SKILL.md` (있으면)

---

# Related Contracts

- `specs/contracts/api/internal/*` 가 존재한다면 인증 정책 섹션 갱신

---

# Target Service

- `account-service`, `membership-service`, `security-service`

---

# Architecture

Follow each service's architecture.md. application 레이어 보안 필터.

---

# Implementation Notes

- fail-closed 변경:
  ```java
  if (path.startsWith("/internal/")) {
      if (expectedToken == null || expectedToken.isBlank()) {
          response.setStatus(HttpStatus.UNAUTHORIZED.value());
          response.setContentType("application/json");
          response.getWriter().write(
              "{\"code\":\"UNAUTHORIZED\",\"message\":\"Internal API not configured\"}");
          return;
      }
      String token = request.getHeader(INTERNAL_TOKEN_HEADER);
      if (!expectedToken.equals(token)) {
          // 401 응답 (기존)
          return;
      }
  }
  ```
- 부팅 가드: 별도 `@Component InternalApiTokenValidator` 또는 SecurityConfig 의 `@PostConstruct`.
- 테스트 프로파일 분기: `environment.acceptsProfiles(Profiles.of("test", "integration-test"))`.

---

# Edge Cases

- 로컬 개발 시 토큰 미설정으로 `/internal/**` 직접 호출하던 흐름이 깨질 수 있음 — local 프로파일도 명시적 토큰 요구 또는 `local` 프로파일 한정 우회.
- `INTERNAL_API_TOKEN` env 가 모든 마이크로서비스에 동일하게 주입돼야 함 — 운영 secret 관리 검토.

---

# Failure Scenarios

- 부팅 가드를 적용했는데 CI 가 토큰 미주입으로 깨짐 → CI workflow secrets 갱신 필요.
- /internal/** 호출자(다른 서비스) 가 토큰 미주입 상태에서 401 받기 시작 → 구체적 호출 현황 사전 점검 필요.

---

# Test Requirements

- 각 서비스의 `InternalApiFilterTest`:
  - 빈 토큰 + /internal/foo → 401
  - 빈 토큰 + /api/foo → 통과
  - 토큰 일치 → 통과
  - 토큰 불일치 → 401
- 부팅 가드 테스트: prod 프로파일 + 빈 토큰 → 컨텍스트 시작 실패.

---

# Definition of Done

- [ ] 3개 서비스 필터 fail-closed 적용
- [ ] 부팅 가드 적용
- [ ] 모든 단위/통합 테스트 통과
- [ ] CI/배포 환경에 INTERNAL_API_TOKEN 주입 확인 안내 문서화
- [ ] Ready for review
