# admin-service — Data Model

## Design Decision

admin-service는 도메인 상태를 거의 소유하지 않는 **command gateway**이다 ([architecture.md](./architecture.md)). 자체 DB에는 다음만 보관한다:

1. **RBAC 메타데이터** — operator / role / permission 바인딩 (본 서비스 내부 폐쇄 모델)
2. **감사 원장** — `admin_actions` append-only
3. **Outbox** — `admin.action.performed` 발행 버퍼

운영자 계정은 일반 사용자(`account-service.accounts`)와 **물리적으로 분리된 테이블**(`admin_operators`)에 저장한다. operator 인증 경계는 일반 사용자 인증 경계와 완전히 다른 JWT 발급 경로를 사용하며([rbac.md](./rbac.md) JWT claim 절), 두 식별자 공간은 절대 혼용되지 않는다.

RBAC의 의사결정(권한 평가 알고리즘, seed role 매트릭스, missing-annotation 정책, DENIED 기록 규칙)은 [rbac.md](./rbac.md)에서 정의한다. 본 문서는 **테이블 DDL과 데이터 분류만** 담당한다 (단일 책임 분리).

---

## Tables

### `admin_operators`

운영자 계정. 일반 사용자 `accounts`와 완전히 분리된 네임스페이스.

| 컬럼 | 타입 | 제약 | 분류 등급 | 설명 |
|---|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | internal | 내부 PK |
| `operator_id` | VARCHAR(36) | UNIQUE, NOT NULL | internal | UUID v7. JWT `sub` 클레임에 실리는 외부 식별자 ([rbac.md](./rbac.md) JWT claim 절) |
| `email` | VARCHAR(255) | UNIQUE, NOT NULL | **confidential** | 로그인 식별자. PII ([rules/traits/regulated.md](../../../rules/traits/regulated.md) R1). 응답에서 기본 마스킹 |
| `password_hash` | VARCHAR(255) | NOT NULL | **restricted** | argon2id 해시. 평문 저장 금지 (R2) |
| `display_name` | VARCHAR(120) | NOT NULL | **confidential** | 감사 UI에 표시되는 이름. 개인 식별 가능 (R1) |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'ACTIVE' | internal | `ACTIVE` / `DISABLED` / `LOCKED`. 소프트 비활성 플래그 |
| `totp_secret_encrypted` | VARBINARY(255) | NULL | **restricted** | TOTP 시크릿 (envelope encryption). **TASK-BE-029 예약 컬럼** — 본 태스크에서는 NULL만 허용 |
| `totp_enrolled_at` | DATETIME(6) | NULL | internal | TOTP 등록 완료 시각. **TASK-BE-029 예약 컬럼** — 본 태스크에서는 NULL만 허용 |
| `last_login_at` | DATETIME(6) | NULL | internal | 마지막 operator JWT 발급 시각 |
| `created_at` | DATETIME(6) | NOT NULL | internal | — |
| `updated_at` | DATETIME(6) | NOT NULL | internal | — |
| `version` | INT | NOT NULL, DEFAULT 0 | internal | 낙관적 락 (T5) |

**인덱스**:
- `uk_admin_operators_operator_id` UNIQUE (`operator_id`)
- `uk_admin_operators_email` UNIQUE (`email`)
- `idx_admin_operators_status` (`status`) — 활성 운영자 조회

### `admin_roles`

역할 카탈로그. Seed 값은 [rbac.md](./rbac.md) "Seed Roles" 절에서 정의한다.

| 컬럼 | 타입 | 제약 | 분류 등급 | 설명 |
|---|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | internal | — |
| `name` | VARCHAR(40) | UNIQUE, NOT NULL | internal | `SUPER_ADMIN` / `SUPPORT_READONLY` / `SUPPORT_LOCK` / `SECURITY_ANALYST`. UPPER_SNAKE_CASE |
| `description` | VARCHAR(255) | NOT NULL | internal | 운영자 관리 UI용 설명 |
| `require_2fa` | BOOLEAN | NOT NULL, DEFAULT FALSE | internal | 역할 보유 시 2FA 강제 플래그. **TASK-BE-029 예약 컬럼** — 본 태스크에서는 읽기만 하고 강제 평가는 TASK-BE-029에서 활성화 |
| `created_at` | DATETIME(6) | NOT NULL | internal | — |

**인덱스**:
- `uk_admin_roles_name` UNIQUE (`name`)

### `admin_role_permissions`

역할 ↔ 권한 키 바인딩. 권한 키 카탈로그는 [rbac.md](./rbac.md) "Permission Keys" 절이 canonical.

| 컬럼 | 타입 | 제약 | 분류 등급 | 설명 |
|---|---|---|---|---|
| `role_id` | BIGINT | NOT NULL, FK → `admin_roles.id` ON DELETE CASCADE | internal | — |
| `permission_key` | VARCHAR(80) | NOT NULL | internal | 예: `account.lock`, `audit.read`. 값 검증은 애플리케이션 레벨 상수와 대조 |

**Primary Key**: (`role_id`, `permission_key`) 복합 PK
**인덱스**:
- `idx_admin_role_permissions_permission` (`permission_key`) — 특정 권한을 가진 role 역추적

### `admin_operator_roles`

운영자 ↔ 역할 바인딩. 다대다.

| 컬럼 | 타입 | 제약 | 분류 등급 | 설명 |
|---|---|---|---|---|
| `operator_id` | BIGINT | NOT NULL, FK → `admin_operators.id` ON DELETE CASCADE | internal | **`admin_operators.id` (BIGINT PK) 참조**. 외부 UUID `operator_id` 컬럼이 아님 |
| `role_id` | BIGINT | NOT NULL, FK → `admin_roles.id` ON DELETE RESTRICT | internal | role 삭제는 해당 role의 운영자 바인딩이 모두 제거된 뒤에만 허용 |
| `granted_at` | DATETIME(6) | NOT NULL | internal | 역할 부여 시각 |
| `granted_by` | BIGINT | NULL, FK → `admin_operators.id` | internal | 부여자 operator. seed 투입 시 NULL |

**Primary Key**: (`operator_id`, `role_id`) 복합 PK
**인덱스**:
- `idx_admin_operator_roles_role` (`role_id`) — 역할별 운영자 역검색

### `admin_actions`

감사 원장. **append-only** ([architecture.md](./architecture.md) Forbidden Dependencies, [rules/traits/audit-heavy.md](../../../rules/traits/audit-heavy.md) A3).

본 태스크(TASK-BE-027)에서 **추가되는 컬럼**: `operator_id`, `permission_used`. 기존 `outcome` enum은 `DENIED` 값을 추가하도록 **확장**한다.

| 컬럼 | 타입 | 제약 | 분류 등급 | 설명 |
|---|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | internal | — |
| `action_code` | VARCHAR(40) | NOT NULL, INDEX | internal | `ACCOUNT_LOCK` / `ACCOUNT_UNLOCK` / `SESSION_REVOKE` / `AUDIT_QUERY` / `SECURITY_EVENT_QUERY` 등 |
| `operator_id` | BIGINT | **NOT NULL**, FK → `admin_operators.id` | internal | **신규 (TASK-BE-027)** — 기존 `operator_external_id` 문자열이 있었다면 deprecate하고 본 FK로 대체. 권한 평가 주체와 감사 주체를 동일 entity로 고정 |
| `permission_used` | VARCHAR(80) | NULL | internal | **신규 (TASK-BE-027)** — 본 action이 통과(또는 거부)된 permission key. annotation 없는 endpoint의 경우 NULL이 아닌 sentinel `"<missing>"` 기록 ([rbac.md](./rbac.md) Missing-Annotation 정책) |
| `target_type` | VARCHAR(20) | NULL | internal | `ACCOUNT` / `SESSION` / `AUDIT_QUERY` 등 |
| `target_id` | VARCHAR(64) | NULL, INDEX | internal | 대상 식별자 (account_id 등). 조회 액션은 NULL 허용 |
| `reason` | VARCHAR(500) | NOT NULL | **confidential** | `X-Operator-Reason` 원문. 개인정보 포함 가능성 — 감사 조회 시 권한 검증 후 노출 |
| `ticket_id` | VARCHAR(64) | NULL | internal | 내부 티켓 연결 |
| `request_id` | VARCHAR(64) | NOT NULL, INDEX | internal | Idempotency 및 로그 상관관계 키 |
| `outcome` | VARCHAR(20) | NOT NULL | internal | `SUCCESS` / `FAILURE` / **`DENIED`** (신규). 기존 enum 확장 — DB는 VARCHAR + 애플리케이션 제약 |
| `detail` | TEXT | NULL | internal | 실패/거부 상세 (downstream error code, denied permission 등). PII 금지 |
| `occurred_at` | DATETIME(6) | NOT NULL, INDEX | internal | 액션 발생 시각 |

**인덱스**:
- `idx_admin_actions_operator_time` (`operator_id`, `occurred_at`) — 특정 운영자 활동 타임라인
- `idx_admin_actions_target_time` (`target_type`, `target_id`, `occurred_at`) — 특정 계정 대상 감사
- `idx_admin_actions_action_code` (`action_code`)
- `idx_admin_actions_request_id` (`request_id`) — 멱등성 조회
- `idx_admin_actions_outcome_time` (`outcome`, `occurred_at`) — DENIED 빈도 모니터링

**Immutability**: UPDATE/DELETE는 DB 레벨 권한 제거 또는 트리거로 차단 ([architecture.md](./architecture.md) Forbidden Dependencies).

### `outbox`

[libs/java-messaging](../../../libs/java-messaging) 표준 스키마. `admin.action.performed` 발행 버퍼.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT |
| `aggregate_type` | VARCHAR(100) | `admin` |
| `aggregate_id` | VARCHAR(255) | `admin_actions.id` (string화) |
| `event_type` | VARCHAR(100) | `admin.action.performed` |
| `payload` | TEXT (JSON) | 이벤트 envelope |
| `created_at` | TIMESTAMP | — |
| `published_at` | TIMESTAMP | NULL이면 미발행 |
| `status` | VARCHAR(20) | `PENDING` / `PUBLISHED` |

---

## Migration Strategy

- **Flyway**: `V{nnnn}__{description}.sql`
- 본 태스크(TASK-BE-027)는 specs-only. 실제 DDL/Flyway는 TASK-BE-028에서 작성한다.
- 권장 migration 분할:
  - `V0001__create_admin_actions_and_outbox.sql` (기존, admin-service bootstrap에서 생성 가정)
  - `V00NN__create_admin_rbac_tables.sql` — `admin_operators`, `admin_roles`, `admin_role_permissions`, `admin_operator_roles` 신규
  - `V00NN+1__extend_admin_actions_rbac_columns.sql` — `admin_actions.operator_id` FK, `permission_used`, `outcome` enum 값 확장
  - `V00NN+2__seed_admin_roles_and_permissions.sql` — [rbac.md](./rbac.md) seed 매트릭스 INSERT
- `totp_secret_encrypted`, `totp_enrolled_at`, `admin_roles.require_2fa`는 본 태스크에서 컬럼만 생성하고 평가 로직은 추가하지 않는다 (TASK-BE-029 예약).
- PII/시크릿 컬럼(`password_hash`, `totp_secret_encrypted`, `email`) 변경은 단방향만 허용 — down migration 금지.

---

## Data Classification Summary

| 등급 | 컬럼 |
|---|---|
| **restricted** | `admin_operators.password_hash`, `admin_operators.totp_secret_encrypted` |
| **confidential** | `admin_operators.email`, `admin_operators.display_name`, `admin_actions.reason` |
| **internal** | 위에 명시되지 않은 모든 컬럼 — `admin_operators` 나머지 (id, operator_id, status, totp_enrolled_at, last_login_at, created_at, updated_at, version), `admin_roles`의 모든 컬럼, `admin_role_permissions`의 모든 컬럼, `admin_operator_roles`의 모든 컬럼, `admin_actions`의 나머지 (id, action_code, operator_id, permission_used, target_type, target_id, ticket_id, request_id, outcome, detail, occurred_at), `outbox`의 모든 컬럼 |
| **public** | 없음 |

[rules/traits/regulated.md](../../../rules/traits/regulated.md) R1 (PII/secret 분리 저장·마스킹)과 R2 (시크릿 평문 저장 금지) 준수.

> RBAC 테이블의 **행동 규칙**(권한 평가 알고리즘, seed 매트릭스, DENIED 기록 정책, JWT claim)은 본 문서가 아닌 [rbac.md](./rbac.md)에서 선언된다. 본 문서는 스키마와 분류만 다룬다.
