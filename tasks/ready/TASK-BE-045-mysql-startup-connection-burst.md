# Task ID

TASK-BE-045-mysql-startup-connection-burst

# Title

서비스 동시 기동 시 MySQL 커넥션 타임아웃 — HikariCP 튜닝 및/또는 기동 순서 제어

# Status

ready

# Owner

backend

# Task Tags

- deploy
- fix

# depends_on

- (없음)

---

# Goal

E2E compose 환경에서 4개 서비스(admin/auth/account/security)가 동시에 mysql에 커넥션을 열며 일부 서비스에서 `com.mysql.cj.jdbc.exceptions.CommunicationsException: Communications link failure` 또는 `java.net.SocketException: Connection attempt exceeded defined timeout`이 발생해 기동 실패한다. mysql 컨테이너는 healthy 상태이지만 초기 warmup 구간에서 동시 커넥션 부하에 대응하지 못해 드롭되는 것으로 보인다.

---

# Scope

## In Scope

1. 재현: `docker compose -f docker-compose.e2e.yml down -v && up -d` 5회 반복에서 실패율 관측
2. 원인 가설:
   - MySQL 컨테이너 `max_connections` 기본값에 대비 4개 서비스 HikariCP pool 합계 과다
   - 첫 `checkFailFast` 커넥션이 mysql 내부 초기화 완료 전에 doGetConnection 호출
   - Docker Desktop Windows TCP 레이어의 초기 지연
3. 해결 후보(최소 영향 우선):
   - 각 서비스 application-e2e.yml `spring.datasource.hikari.connection-timeout: 30000ms`, `initialization-fail-timeout: -1` (재시도 허용)로 상향
   - mysql 컨테이너 `command: ["--max_connections=500", "--log-bin-trust-function-creators=1"]`
   - compose에서 서비스 depends_on 체인을 단계화(auth → account → admin → security)하여 동시 connect를 분산
4. 적용 후 5회 연속 down -v / up -d 성공 검증

## Out of Scope

- 프로덕션 배포용 MySQL 파라미터 튜닝
- JVM DNS 관련(BE-044 범위)

---

# Acceptance Criteria

- [ ] 원인 재현 로그 포함 보고서
- [ ] 튜닝/순서화 적용 후 5회 연속 compose up -d → 4 서비스 healthy
- [ ] 기존 단위 테스트 회귀 없음

---

# Related Specs

- `platform/service-types/rest-api.md`
- `platform/testing-strategy.md` (Testcontainers 병행 고려)

---

# Target Service

- 루트 `docker-compose.e2e.yml`
- 4개 서비스 `application-e2e.yml`

---

# Edge Cases

- HikariCP `initialization-fail-timeout: -1` 설정 시 hang 방지를 위해 `connection-timeout`은 유한값 유지 필요

---

# Failure Scenarios

- 단계화가 cold start를 크게 늘리면 CI 시간 증가 — 허용 trade-off 기록

---

# Test Requirements

- 수동 재현 5회 + 기존 테스트 회귀 확인

---

# Definition of Done

- [ ] 튜닝 적용 + 반복 기동 성공
- [ ] Ready for review
