# Task ID

TASK-BE-074

# Title

infra(test): OAuthLogin/DetectionE2E/DlqRouting 테스트 body 레벨 실패 원인 진단 — XML artifact 기반

# Status

ready

# Owner

backend

# Task Tags

- test
- infra

# depends_on

- TASK-BE-069 (merged)
- TASK-BE-070 (merged)
- TASK-BE-072 (merged)
- TASK-BE-073 (superseded — P0 scheduler fix만 유효)

---

# Goal

PR #37 CI 실측(run 24696679115) 결과, TASK-BE-069/070/072/073 모두 머지한 상태에서도 9건이 동일 assertion 실패 재현:

- `OAuthLoginIntegrationTest.java:166` (Google happy path)
- `OAuthLoginIntegrationTest.java:204` (Kakao happy path)
- `OAuthLoginIntegrationTest.java:221` (Microsoft happy path)
- `OAuthLoginIntegrationTest.java:238` (Microsoft email absent → preferred_username fallback)
- `OAuthLoginIntegrationTest.java:261` (Microsoft existing email)
- `DetectionE2EIntegrationTest.java:154` (velocity rule auto-lock)
- `DlqRoutingIntegrationTest` 3건 (malformed JSON / invalid UTF-8 / missing eventId)

가설이 틀렸음: OutboxPollingScheduler shutdown noise 는 부수적 현상이었고, 실제 실패는 **test body 내부 assertion** 에서 발생. stdout 로그에는 stack trace 요약만 있고 expected vs actual 은 **XML artifact 에만 기록**됨.

---

# Scope

## In Scope

### P0 — CI XML artifact 확보 (선결 조건)

1. 최신 실패 CI run 의 test-results artifact 다운로드:
   ```bash
   gh run download <run-id> --name backend-test-results --dir build/artifacts/
   ```
   실패한 최신 run 예: 24696679115 (master 기준 PR #37 최종 실측)
2. `build/artifacts/**/TEST-*.xml` 에서 각 `<failure>` 의 `message` + `<system-out>` 확인
3. 각 실패의 실제 **expected vs actual** 메시지를 본 task file 에 기록

### P1 — 실패 카테고리 분류

각 실패가 어느 카테고리에 해당하는지 판별:

- **WireMock stub mismatch**: 테스트가 기대한 HTTP path/method 와 실제 코드가 보낸 요청이 다름
  (TASK-BE-069/072 orchestration 변경 가능성 — 예: 이제 UseCase 에서 직접 account-service 호출)
- **Assertion data drift**: DB 상태가 기대와 다름 (FK 변경, column default 변경 등)
- **Timing / race**: outbox write 와 assertion 사이 타이밍 race
- **Testcontainers infra**: 여전히 container 불안정

P2 진단 경로는 카테고리에 따라 분기.

### P2 — 카테고리별 fix

WireMock mismatch 인 경우:
- `wireMock.getAllServeEvents()` 로 실제 요청 dump → 기대 stub 과 diff
- UseCase 변경(069/072) 으로 HTTP path/body 가 바뀌었다면 테스트 stub 수정

Assertion data drift 인 경우:
- 기대값과 실제값 차이 분석 → 테스트 수정 또는 구현 수정

Timing/race:
- Awaitility 적용 또는 outbox polling 을 테스트에서 명시 trigger

Testcontainers infra:
- TASK-BE-070 추가 튜닝 (context cache, DirtiesContext 등)

### P3 — 3 테스트 @Disabled 제거 + CI 3회 연속 green

## Out of Scope

- 새 통합 테스트 추가
- TASK-BE-069/072 롤백 (그 설계는 연구원 분석 대상 아님, 가설의 fix 로만 사용)
- Testcontainers 실행기 교체

---

# Acceptance Criteria

- [ ] 최신 CI 실패 run 의 XML artifact 다운로드 + 실제 assertion 메시지 9건 전부 task file 에 기록
- [ ] 각 실패를 4 카테고리 중 하나로 분류
- [ ] 카테고리별 fix 적용 (mismatch 면 stub 수정 / drift 면 assertion/구현 수정 등)
- [ ] 3 테스트 `@Disabled` 제거 + CI 3회 연속 green
- [ ] 회귀 없음

---

# Related Specs

- `platform/testing-strategy.md`
- `specs/services/auth-service/architecture.md` (TASK-BE-069/072 로직)
- `platform/event-driven-policy.md`

---

# Related Contracts

없음 (test/infra 레이어)

---

# Target Service

- `apps/auth-service` (OAuthLoginIntegrationTest)
- `apps/security-service` (DetectionE2E, DlqRouting)

---

# Architecture

test + possibly application-adjacent. 레이어 변경 없음.

---

# Edge Cases

- XML artifact 가 `backend-test-results` 가 아닌 다른 이름일 수 있음 — `gh run view <run-id>` 로 아티팩트 목록 확인 선행
- 실패 카테고리가 섞여 있을 수 있음 (9건 중 5건 WireMock mismatch, 3건 timing, 1건 drift 식) — 카테고리별로 병렬 fix 해야 함
- 069 이전 commit 에서 돌리면 통과하는 테스트가 069 이후 실패라면 그 자체가 강력한 단서

---

# Failure Scenarios

- artifact 가 CI 에 업로드되지 않은 경우 → `gradle-actions/setup-gradle` test report 업로드 설정 확인 후 재실행
- 3회 연속 green 미달성 시 test-by-test 단위 @Disabled 재부착하고 fix-task 세분화

---

# Test Requirements

- CI 3회 연속 green (071/073 AC 승계)
- 로컬 에서는 Docker 가용 시 integration 실행 + pass

---

# Definition of Done

- [ ] 9건 실패의 실제 원인 메시지가 task file 에 기록
- [ ] 카테고리별 fix 적용 + CI 3회 green
- [ ] Ready for review
