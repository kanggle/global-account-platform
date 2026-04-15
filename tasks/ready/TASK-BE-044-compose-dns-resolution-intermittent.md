# Task ID

TASK-BE-044-compose-dns-resolution-intermittent

# Title

docker-compose.e2e.yml — 서비스 재기동 시 mysql 호스트 DNS 해석 실패 조사 및 안정화

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

E2E 런타임 검증 중 account-service와 security-service가 `java.net.UnknownHostException: mysql: Temporary failure in name resolution` 으로 간헐적 기동 실패. depends_on: mysql (condition: service_healthy) 가 설정돼 있음에도 `docker compose up -d <service>` 방식으로 부분 재기동 시 재현. 원인 파악 후 안정화한다.

---

# Scope

## In Scope

1. 재현 시나리오 명세화
2. 원인 가설 검증:
   - compose partial restart 시 네트워크 DNS 캐시 stale
   - `depends_on: service_healthy` 이 partial up에서 평가되지 않는 경우
   - mysql healthcheck 가 "pingable but DNS propagation not yet visible" 창 구간
3. 해결안:
   - 각 서비스 JVM 옵션에 `networkaddress.cache.ttl=0` 추가 검토
   - 서비스 기동 스크립트 wrapper 에서 DNS 해석을 wait-for-host 로 사전 체크
   - 또는 `restart: on-failure` 와 재시도 backoff 조합
4. 수정안 적용 후 재현 실패 확인 (반복 10회 성공)

## Out of Scope

- 인프라(MySQL/Redis/Kafka) 이미지 변경
- compose 외 배포 환경 DNS 정책

---

# Acceptance Criteria

- [ ] 재현 스텝 및 원인 문서화
- [ ] 수정 적용 후 `down -v` / `up -d` / `up -d <svc>` 여러 시나리오 반복 10회에서 DNS 실패 0건
- [ ] 기존 단위 테스트 회귀 없음

---

# Related Specs

- `platform/service-types/rest-api.md`

---

# Target Service

- 루트 `docker-compose.e2e.yml`
- 필요 시 4개 서비스 Dockerfile 또는 entrypoint

---

# Edge Cases

- Docker Desktop for Windows 특유 DNS 동작 — Linux CI와 차이 문서화

---

# Failure Scenarios

- 해결안이 부분 재기동만 안정화하고 cold start 시 지연이 생기면 허용 trade-off 문서화

---

# Test Requirements

- 수동 재현 시나리오 10회

---

# Definition of Done

- [ ] 안정화 후 보고서 및 Ready for review
