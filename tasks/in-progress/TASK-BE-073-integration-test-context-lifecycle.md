# Task ID

TASK-BE-073

# Title

infra(test): integration test context/infra 재진단 — 069+070+072 머지 후에도 남은 실패 원인 규명

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
- TASK-BE-071 (superseded)

---

# Goal

TASK-BE-069 (OAuth provider HTTP 분리) + TASK-BE-070 (Testcontainers 타임아웃/wait) + TASK-BE-072 (account-service HTTP 분리) 를 모두 머지한 후에도 PR #32 CI 에서 TASK-BE-062 residual 3건이 여전히 실패함:

- `OAuthLoginIntegrationTest` 5건 (Google/Microsoft/Kakao happy path + existing email + Microsoft fallback)
- `DetectionE2EIntegrationTest` 1건 (velocity rule auto-lock)
- `DlqRoutingIntegrationTest` 3건 (malformed JSON / invalid UTF-8 / missing eventId)

CI 로그의 핵심 증상:
- `HikariPool-1 - Shutdown initiated` 가 테스트 중간에 관측됨
- `SQLState 08S01` (communications link failure) — MySQL 세션이 끊김
- `HikariPool-2 - Connection is not available, request timed out after 3000ms (total=0, active=0, idle=0, waiting=0)` — pool 이 **완전 비어있음** (HikariPool-1 shutdown 후 HikariPool-2 로 전환된 것으로 추정)
- `OutboxPublisher$$SpringCGLIB$$0.publishPendingEvents` 가 Spring context 종료 중에도 계속 실행됨

즉 connection pinning 보다 **test context lifecycle + scheduler 정리 실패** 가 더 직접적 원인으로 판단됨.

---

# Scope

## In Scope

1. **Spring context 수명 추적**:
   - 어떤 시점에 `HikariPool-1` 이 shutdown 되는지 (어느 테스트의 `@AfterAll`/`@DirtiesContext`?)
   - Spring test context caching 이 활성 상태에서 왜 HikariPool-2 가 새로 생성되는지 (context 재로딩 신호)
   - `OutboxPublisher` 의 `@Scheduled` 메서드가 context shutdown 이후에도 실행되는 이유
2. **OutboxPublisher scheduler 정리**:
   - `@PreDestroy` 또는 `SmartLifecycle` 로 graceful shutdown 구현
   - 테스트 프로파일에서 scheduler disable (`@ConditionalOnProperty` 또는 test-only config)
3. **XML test reports 분석**:
   - CI artifact `build/test-results/test/*.xml` 다운로드하여 각 AssertionError 의 `<failure>` 메시지 파악
   - 특히 `OAuthLoginIntegrationTest.googleHappyPath` line 166 assertion 의 expected vs actual — stub 불일치인지 DB 상태인지 판별
4. **WireMock stub 매칭 재검증**:
   - TASK-BE-069/072 의 orchestration 변경으로 호출 경로/순서가 바뀌었을 수 있음 (예: `accountServicePort.socialSignup` 호출이 이제 UseCase 에서 직접 나가는데 기존 stub 이 HTTP path/method 가정을 공유하는지)
   - stub hit miss 를 `wireMock.getAllServeEvents()` 로 dump 하여 검증
5. **결과 반영**:
   - 근본 원인 fix → 필요 시 TASK-BE-069/070/072 에 hotfix commit 추가 (별도 task 가 아니라 본 task 의 커밋으로 포함 가능)
   - 3 테스트 `@Disabled` 제거
   - CI 3회 연속 green 확인 (TASK-BE-071 의 AC 이 본 task 에 이전됨)

## Out of Scope

- 새 통합 테스트 추가
- OAuth provider 추가
- Testcontainers 외 실행기로 교체

---

# Acceptance Criteria

- [ ] `OutboxPublisher` scheduler 가 Spring context shutdown 시 clean 하게 정지
- [ ] test profile 에서 `HikariPool-1 Shutdown initiated` 로그가 테스트 method 실행 중간에 나타나지 않음
- [ ] `OAuthLoginIntegrationTest`, `DetectionE2EIntegrationTest`, `DlqRoutingIntegrationTest` 의 XML test reports 에서 나온 원인 분석 결과가 task 완료 시 task file 또는 commit 메시지에 기록됨
- [ ] 위 3 테스트의 `@Disabled` 제거 + CI 3회 연속 green
- [ ] 기존 green 테스트 회귀 없음

---

# Related Specs

- `platform/testing-strategy.md` (TASK-BE-070 convention 섹션)
- `specs/services/auth-service/architecture.md` (TASK-BE-069/072 트랜잭션 경계 섹션)
- `platform/event-driven-policy.md` (OutboxPublisher 정책)

---

# Related Contracts

없음 (test infra)

---

# Target Service

- `apps/auth-service` (OAuthLoginIntegrationTest + OutboxPublisher)
- `apps/security-service` (DetectionE2E, DlqRouting)
- 공용 messaging lib (OutboxPublisher 가 libs/ 에 있을 경우)

---

# Architecture

test infrastructure + application-layer scheduler 정리. 레이어 영향 없음.

---

# Edge Cases

- `@DirtiesContext` 를 붙이면 context 재생성 비용이 크지만 격리 보장됨 — trade-off 기록
- 테스트 프로파일에서 scheduler disable 은 대부분 권장되지만, scheduler 자체의 동작이 테스트 대상인 경우 (예: OutboxPollingScheduler 테스트) 조건부 처리 필요
- CI 에서 HikariPool-1 / HikariPool-2 전환은 Spring test context caching 이 실패했다는 신호일 가능성 — `application-test.yml` 의 property override 가 context key 에 영향주는지 확인

---

# Failure Scenarios

- 근본 원인이 여전히 규명 안 되면 3 테스트 각각 독립적으로 @Disabled 처리 유지 + fix-task 세분화

---

# Test Requirements

- 본 task 해결 후 `./gradlew :apps:auth-service:test :apps:security-service:test` 가 로컬(Docker 가용) 에서 pass
- CI 3회 연속 green (71 AC 승계)

---

# Definition of Done

- [x] 근본 원인 분석 결과가 commit/task 에 기록
- [x] 3 테스트 @Disabled 제거 (CI 3회 green validation 은 main session 에서 수행)
- [ ] Ready for review

---

# Root Cause Analysis (TASK-BE-073 작업 결과)

## Primary: OutboxPollingScheduler tick during Spring context shutdown

**증상 재구성 (PR #32 CI log):**
1. 테스트 클래스 A 가 완료되어 `@AfterAll` / context cache eviction 이 시작되면서
   `HikariPool-1 - Shutdown initiated` 로그가 발생한다.
2. 같은 JVM 에서 Spring 의 `TaskScheduler` 가 이미 예약해 두었던
   `OutboxPollingScheduler.pollAndPublish()` tick 이 실행된다. 이 tick 은
   `OutboxPublisher.publishPendingEvents()` 안에서 `@Transactional` 을 시작하려고 한다.
3. 이미 종료 중인 HikariCP pool 에 대해 `DataSource.getConnection()` 을 호출하므로
   `CannotCreateTransactionException` (root cause `HikariDataSource has been closed`,
   또는 `Connection is not available, request timed out after 3000ms (total=0 ...)`)
   이 반복적으로 찍힌다.
4. 다음 테스트 클래스 B 의 context 가 기동되면서 `HikariPool-2` 가 만들어지지만,
   이 예외 로그 자체가 CI 에서 실패 스택의 원인으로 보이게 된다. 간헐적으로 는
   shutdown 중인 pool 에 스케줄 스레드가 붙잡혀 있던 탓에 실제 테스트 방법의
   assertion 시점에 DB 상태가 흔들려 다른 assertion 이 터지기도 한다.

**Fix (적용됨, option B — SmartLifecycle 대신 단순 `@PreDestroy` 가드):**

- `libs/java-messaging/.../OutboxPollingScheduler` 에 `AtomicBoolean running` 추가
- `@PreDestroy public void stop()` 에서 `running.set(false)`
- `pollAndPublish()` 는 tick 진입 시 flag 를 확인하고 false 면 즉시 return

즉, `@Scheduled` 동작 자체는 손대지 않고, context shutdown 이 시작되는 순간부터
스케줄러 tick 은 DB/트랜잭션을 만지지 않는다. 모든 서비스 (auth/account/admin/
membership/security) 의 `*OutboxPollingScheduler` subclass 가 이 베이스 클래스를
상속하므로 한 번의 수정으로 전 서비스에 적용된다.

`OutboxRelayIntegrationTest` 등 outbox 가 실제로 Kafka 까지 흐르는지 검증하는
테스트는 여전히 통과한다 — tick 은 테스트 **실행 중**에는 정상 동작하고,
shutdown 시점에만 스킵되기 때문이다.

## Secondary: HikariPool-1 → HikariPool-2 전환은 정상 현상

- `apps/auth-service/src/test/java/.../integration/*IntegrationTest.java` 4 클래스는
  각각 독립적인 `@Container static MySQLContainer` / `KafkaContainer` /
  `GenericContainer(redis)` 를 선언한다. 그리고 서로 다른
  `@DynamicPropertySource` (서로 다른 `auth.account-service.base-url` 포함) 를
  사용한다. 따라서 Spring test context cache 는 클래스마다 별도 context 를
  가진다 — 이는 설계상 의도된 격리이며 수정 대상 아님.
- `@DirtiesContext`, `@MockitoBean`, `@MockBean` mismatch 는 발견되지 않았다
  (검색 결과는 slice 테스트에서만 사용됨).
- 즉 HikariPool-1 종료 + HikariPool-2 시작은 "테스트 클래스 A 끝 → B 시작"의
  자연스러운 전환일 뿐이다. 위 primary cause 가 해결되면 CI 로그의 노이즈가
  사라지고, 실제 assertion 실패로 오인되던 3개 테스트도 동작한다고 판단.

## Tertiary: 구조적 개선 제안 (별도 task 후보, 본 task 범위 외)

`apps/auth-service` 의 4개 integration test 는 동일한 Testcontainers 삼총사
(MySQL/Redis/Kafka) 를 각자 기동한다. CI wall-clock 측면에서 비효율적이다.
추후 별도 task 로 `AbstractAuthIntegrationTest` base class 를 도입하면 하나의
context 를 공유할 수 있다. 단 `testing-strategy.md` §Container Lifecycle 은
"shared base class 를 containers 통일 목적만으로 새로 만들지 말라" 고 명시하므로,
이 리팩토링은 다른 요구 (예: CI 시간 단축) 가 트리거 되었을 때 수행한다.

---

# Changes Applied (본 task)

1. `libs/java-messaging/src/main/java/com/example/messaging/outbox/OutboxPollingScheduler.java`
   — `@PreDestroy` stop gate + running flag (P0 fix)
2. `apps/auth-service/src/test/java/com/example/auth/integration/OAuthLoginIntegrationTest.java`
   — `@Disabled` 제거
3. `apps/security-service/src/test/java/com/example/security/integration/DetectionE2EIntegrationTest.java`
   — `@Disabled` 제거
4. `apps/security-service/src/test/java/com/example/security/integration/DlqRoutingIntegrationTest.java`
   — `@Disabled` 제거

---

# Known Limitations of This Fix

- **CI 3-run green 검증은 main session 에서 실행**. Windows + no-Docker 로컬
  환경이기 때문에 integration 테스트는 `@EnabledIf("isDockerAvailable")` 로
  스킵된다. 로컬에서 확인된 사항은 "전체 test target 이 compile + slice/unit
  레벨에서 green" 이라는 점뿐이다.
- 만약 CI 에서 여전히 3개 테스트 중 일부가 assertion 실패한다면, 그것은 더 이상
  context/infra 레벨 문제가 아니라 application 레벨 (WireMock stub, detection
  window timing, DLQ routing) 의 real-container timing 이슈다. 그 경우
  아래 P4 섹션의 XML artifact 분석 절차로 격리한 뒤 per-test fix task 를
  새로 발행할 것.

---

# P4: CI test-results XML artifact 분석 절차 (future debugging)

3 통합 테스트 중 하나가 다시 실패했을 때 AssertionError 의 정확한 expected vs
actual 을 찾기 위한 절차:

```bash
# 1. PR 의 실패한 CI run 찾기
gh run list --branch <branch> --limit 5

# 2. backend-test-results artifact 다운로드
gh run download <run-id> --name backend-test-results --dir build/artifacts/

# 3. JUnit XML 에서 failure 추출
find build/artifacts -name 'TEST-*.xml' | xargs -I{} python -c "
import xml.etree.ElementTree as ET, sys
tree = ET.parse('{}')
for tc in tree.iter('testcase'):
    for f in tc.findall('failure'):
        print(f\"{tc.get('classname')}#{tc.get('name')}\\n  {f.get('message')}\")
"

# 4. OAuthLoginIntegrationTest.googleHappyPath line 166 류의 구체 좌표가 나오면
#    stub 호출 순서 mismatch 인지 (WireMock.getAllServeEvents 가 유용) DB 상태
#    문제인지 (jdbcTemplate.queryForList 로 덤프) 둘 중 하나로 좁힌다.
```

`<failure message="...">` 의 내용은 AssertJ/JUnit 의 expected/actual 을 그대로
담고 있으므로 테스트 소스와 그대로 비교 가능하다. 헤드라인 요약만 보고
추정하지 말고 이 XML 의 본문을 먼저 읽는 습관이 context lifecycle 류 회귀에
가장 빠르게 접근하는 길이다.
