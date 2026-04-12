# Service Architecture — admin-service

## Service

`admin-service`

## Service Type

`rest-api` — 운영자 전용 관리 서비스. lock/unlock, 강제 로그아웃, 감사 조회 프록시. **일반 사용자 트래픽과 완전히 분리된 인증 경계** 뒤에 배치.

적용되는 규칙: [platform/service-types/rest-api.md](../../../platform/service-types/rest-api.md)

## Architecture Style

**Thin Layered (Command Gateway)** — `presentation / application / infrastructure` 3계층. 자체 도메인 데이터를 거의 보유하지 않는 **명령 오케스트레이션 서비스**. 도메인 상태는 downstream(auth / account / security)이 소유하고, admin-service는 다음의 흐름만 담당:

1. 운영자 인증 + 권한 검사
2. 명령 파라미터 검증
3. 다운스트림 서비스에 내부 HTTP 호출
4. 모든 행위를 감사 로그 + `admin.action.performed` 이벤트로 기록

## Why This Architecture

- **도메인 상태가 없음**: 운영자 작업의 "대상"은 모두 다운스트림(auth의 세션, account의 계정 상태, security의 감사 로그). admin-service 자체에 aggregate가 없으므로 `domain/` 레이어가 불필요.
- **격리가 핵심 속성**: [rules/domains/saas.md](../../../rules/domains/saas.md) S5는 admin 경로가 별도 인증 경계 + 감사 로그 필수. 아키텍처적으로 thin하게 유지해서 attack surface 최소화.
- **변경 사유 = 새 운영 명령 추가**: admin-service가 바뀌는 패턴은 "새 운영 기능" 또는 "새 감사 조회 요구"가 대부분. command-oriented 구조가 이 변경 축과 잘 정렬됨.
- **감사가 first-class**: 모든 admin command는 [rules/traits/audit-heavy.md](../../../rules/traits/audit-heavy.md) A1·A5의 auditable action이며, 본 서비스가 감사 기록의 **issuer**이자 **gateway**.

## Internal Structure Rule

```
apps/admin-service/src/main/java/com/example/admin/
├── AdminApplication.java
│
├── presentation/
│   ├── AccountAdminController.java      ← lock/unlock/delete
│   ├── SessionAdminController.java      ← force-logout/session revoke
│   ├── AuditController.java             ← 감사 조회 프록시
│   ├── dto/
│   │   ├── LockAccountRequest.java
│   │   ├── UnlockAccountRequest.java
│   │   ├── RevokeSessionRequest.java
│   │   └── AuditQueryParams.java
│   └── exception/
│       └── AdminExceptionHandler.java
│
├── application/                         ← 명령 오케스트레이션
│   ├── LockAccountCommand.java
│   ├── UnlockAccountCommand.java
│   ├── RevokeSessionCommand.java
│   ├── QueryAuditCommand.java
│   ├── OperatorContext.java             ← 현재 실행 중인 운영자 + 사유 + 티켓 ID
│   ├── AdminActionAuditor.java          ← 모든 command 전·후 감사 기록
│   └── event/
│       └── AdminEventPublisher.java     ← admin.action.performed outbox
│
└── infrastructure/
    ├── client/                          ← 내부 HTTP 클라이언트
    │   ├── AuthServiceClient.java       ← 강제 로그아웃 호출
    │   ├── AccountServiceClient.java    ← lock/unlock/delete 호출
    │   └── SecurityServiceClient.java   ← 감사 조회 호출
    ├── security/
    │   ├── OperatorAuthenticationFilter.java   ← 별도 인증 경계
    │   └── OperatorRoleResolver.java
    ├── persistence/                     ← 최소한의 로컬 상태만
    │   ├── AdminActionJpaEntity.java    ← 감사 원장 (append-only)
    │   └── AdminActionJpaRepository.java
    └── config/
```

**`domain/` 레이어 없음**. 필요 시 단순 값 객체는 `application/` 안에 두고, 별도 패키지로 분리하지 않는다.

## Allowed Dependencies

```
presentation → application → infrastructure/client
                    ↓
          infrastructure/persistence (append-only audit)
                    ↓
            infrastructure/security (인증 필터)
```

- `application/AdminActionAuditor`는 모든 command의 **before/after**를 `AdminActionJpaEntity`로 기록 + outbox 이벤트 발행
- `infrastructure/client/*`는 다른 서비스의 내부 HTTP 엔드포인트만 호출 ([specs/contracts/http/internal/](../../contracts/http/internal/))

## Forbidden Dependencies

- ❌ 다른 서비스의 DB에 직접 접근 — 반드시 내부 HTTP 경유
- ❌ 공개 게이트웨이와 **같은 인증 경계** 사용 — admin 전용 operator 토큰은 별도 발급 경로 (별도 IdP, 또는 auth-service의 admin-scope 토큰)
- ❌ `AdminActionJpaEntity`의 UPDATE/DELETE 경로 존재 ([rules/traits/audit-heavy.md](../../../rules/traits/audit-heavy.md) A3)
- ❌ 도메인 로직 이식 (예: 계정 상태 기계를 admin-service 안에 복제) — 상태 기계는 account-service 단일 소유
- ❌ 감사 기록 없이 명령 실행 — fail-closed ([rules/traits/audit-heavy.md](../../../rules/traits/audit-heavy.md) A10)

## Boundary Rules

### presentation/
- 엔드포인트 prefix: `/api/admin/*`. 게이트웨이 라우트에서 **별도 인증 필터 체인** 적용
- 모든 요청은 `X-Operator-Reason` 헤더 또는 body 필드 필수 — 사유 없는 운영 작업 거부
- DTO에 운영자 ID는 주입되지 않음 — `OperatorAuthenticationFilter`가 JWT claim에서 추출하여 `OperatorContext`에 담음

### application/
- `LockAccountCommand`:
  1. `OperatorContext`로부터 operator id/role 검증
  2. `AdminActionAuditor.begin(action=ACCOUNT_LOCK, target=accountId, reason=...)`
  3. `AccountServiceClient.lock(accountId, reason, idempotencyKey=requestId)` 호출
  4. 결과에 따라 `AdminActionAuditor.complete(outcome=SUCCESS|FAILURE, detail=...)`
  5. 전체 흐름은 **단일 트랜잭션**: audit row + outbox event가 함께 커밋
  6. 실패 시 audit row는 `outcome=FAILURE`로 남김 (감사 누락 금지)
- `QueryAuditCommand`: 읽기 전용이지만 **조회 액션 자체도 감사 기록** (meta-audit)

### infrastructure/client/
- 내부 HTTP 호출에 반드시 `Idempotency-Key` 헤더 전달 ([rules/traits/transactional.md](../../../rules/traits/transactional.md) T1)
- 타임아웃·재시도·circuit breaker는 [rules/traits/integration-heavy.md](../../../rules/traits/integration-heavy.md) I1-I3 준수
- 응답은 내부 도메인 모델로 번역 (I8) — 다운스트림 DTO를 그대로 presentation에 노출 금지

### infrastructure/security/
- `OperatorAuthenticationFilter`: admin-scope JWT 검증 + 2FA 확인 (선택)
- `OperatorRoleResolver`: role 기반 권한 (SUPER_ADMIN / ACCOUNT_ADMIN / AUDITOR 등) → 엔드포인트 접근 제어

### infrastructure/persistence/
- `admin_actions` 테이블은 **append-only** (DB 트리거 또는 권한 제한으로 UPDATE/DELETE 차단)
- 조회는 `AuditController`만 가능 (다른 서비스 불가)

## Integration Rules

- **HTTP 컨트랙트 (외부)**: [specs/contracts/http/admin-api.md](../../contracts/http/) — admin 전용 엔드포인트
- **HTTP 컨트랙트 (out-going)**:
  - [specs/contracts/http/internal/admin-to-auth.md](../../contracts/http/internal/) — 강제 로그아웃
  - [specs/contracts/http/internal/admin-to-account.md](../../contracts/http/internal/) — lock/unlock/delete
  - security-service의 query 엔드포인트 사용 (내부)
- **이벤트 발행**: [specs/contracts/events/admin-events.md](../../contracts/events/) — `admin.action.performed` (항상 발행)
- **퍼시스턴스**: MySQL — `admin_actions` (append-only 감사 원장), `outbox_events`
- **Redis**: 필요 시 operator rate limit, 세션 nonce

## Testing Expectations

| 레이어 | 목적 | 도구 |
|---|---|---|
| Unit | Command 오케스트레이션 흐름, 실패 시 audit 기록 유지 | JUnit 5 + Mockito |
| Integration | 전체 lock 흐름 → AccountServiceClient mock → audit row + 이벤트 | Testcontainers + WireMock |
| Security | 비-admin 토큰으로 `/api/admin/*` 호출 시 403 | Spring Security 테스트 |
| Audit immutability | `admin_actions` UPDATE/DELETE 시도 → 거부 | DB trigger test |
| Fail-closed | 감사 기록 실패 시 command 전체 실패 | Integration |

**필수 시나리오**: operator 없이 호출 → 401 / role 부족 → 403 / reason 없음 → 400 / 다운스트림 5xx → command 실패 + audit row는 FAILURE로 기록 / 중복 요청 Idempotency-Key 재사용 → 다운스트림이 멱등 응답 / meta-audit: 감사 조회도 `admin_actions`에 row.

## Change Rule

1. 새 운영 명령 추가는 [specs/features/admin-operations.md](../../features/) + 해당 내부 컨트랙트 ([specs/contracts/http/internal/](../../contracts/http/internal/)) 업데이트 선행
2. 권한 모델(role) 추가·변경은 [specs/features/admin-operations.md](../../features/)의 role matrix 갱신
3. 감사 스키마 변경은 [rules/traits/audit-heavy.md](../../../rules/traits/audit-heavy.md) A2 표준 필드 준수 확인 + Flyway migration
4. 이 서비스는 **도메인 로직을 수용하지 않는다**. 운영 명령이 복잡해지면 해당 도메인 소유 서비스(auth / account / security)로 로직을 이동하고 admin-service는 호출만 유지
