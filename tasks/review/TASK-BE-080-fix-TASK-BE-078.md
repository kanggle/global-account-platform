# Task ID

TASK-BE-080

# Title

fix(test): TASK-BE-078 review — LoginHistoryImmutabilityTest 미이행 마이그레이션

# Status

ready

# Owner

backend

# Task Tags

- test
- infra

# depends_on

- TASK-BE-078 (done)

---

# Goal

TASK-BE-078 리뷰에서 발견된 누락 항목을 수정한다.

`apps/security-service/.../LoginHistoryImmutabilityTest` 가 여전히 자체 `@Container static MySQLContainer` / `KafkaContainer` (`confluentinc/cp-kafka:7.5.0`) 를 선언하고 있다.
TASK-BE-078 Scope C 에는 "LoginHistoryImmutabilityTest 도 `7.5.0` 을 직접 참조하는 경우 동일하게 마이그레이션 적용" 이라고 명시되어 있으나 구현이 누락되었다.

---

# Scope

## In Scope

- `apps/security-service/.../LoginHistoryImmutabilityTest` 를 `AbstractIntegrationTest` 를 상속하도록 수정
- 자체 `@Container static MySQLContainer` / `@Container static KafkaContainer` 선언 및 관련 `@DynamicPropertySource` 에서 MySQL/Kafka 속성 제거
- Redis `@Container` 및 관련 `@DynamicPropertySource` 는 유지
- `confluentinc/cp-kafka:7.5.0` 직접 참조 제거 (base class 의 `7.6.0` 자동 사용)

## Out of Scope

- 다른 테스트 클래스 수정
- 기존 기능 변경

---

# Acceptance Criteria

- [ ] `LoginHistoryImmutabilityTest` 가 `AbstractIntegrationTest` 를 extend
- [ ] 클래스에서 자체 `@Container MySQLContainer` / `KafkaContainer` 선언 제거
- [ ] `@DynamicPropertySource` 에서 MySQL/Kafka 관련 속성 제거
- [ ] `confluentinc/cp-kafka:7.5.0` 직접 참조 없음
- [ ] `platform/testing-strategy.md` Container Lifecycle MUST 규칙 위반 없음
- [ ] 기존 green 테스트 회귀 없음 (`./gradlew :apps:security-service:test`)

---

# Related Specs

- `platform/testing-strategy.md` (Container Lifecycle 섹션)
- `platform/shared-library-policy.md`

---

# Related Contracts

없음 (test infra)

---

# Target Service

- `apps/security-service`

---

# Architecture

test infrastructure. application code 무영향.

---

# Edge Cases

- Redis `@Container` 와 관련 `@DynamicPropertySource` 는 subclass 에 유지해야 함
- `AbstractIntegrationTest` 를 상속 후 `@Testcontainers` annotation 은 subclass 에 유지 (Redis 컨테이너 관리를 위해)

---

# Failure Scenarios

- `AbstractIntegrationTest` 상속 후 `testFixtures` 의존성 누락으로 컴파일 오류 시 → `apps/security-service/build.gradle` 에 `testImplementation testFixtures(project(':libs:java-test-support'))` 확인
