# tests/e2e — End-to-End Scenario Tests

This Gradle module hosts the real-container, cross-service E2E scenarios defined
by TASK-BE-041c. It drives `docker-compose.e2e.yml` (041a foundation) and
exercises the 4 platform services end-to-end over HTTP + Kafka + MySQL.

## Prerequisites

- Docker Desktop (or daemon) reachable from the test host.
- JDK 21 (same as the rest of the monorepo).
- Host bootJars built once per code change:
  ```bash
  ./gradlew :apps:auth-service:bootJar \
            :apps:account-service:bootJar \
            :apps:security-service:bootJar \
            :apps:admin-service:bootJar
  ```
  (See `docs/guides/docker-build.md`.)

## Running

```bash
./gradlew :tests:e2e:test
```

The suite boots the full compose stack once at the start of the JVM, waits for
every service's `/actuator/health` to return 200, runs all 4 scenarios against
the running cluster, and tears the stack down at JVM shutdown.

### Host port map

| Host port | Container | Purpose                          |
|-----------|-----------|----------------------------------|
| 18081     | auth      | actuator/health, service calls   |
| 18082     | account   | actuator/health, service calls   |
| 18084     | security  | actuator/health, metrics         |
| 18085     | admin     | `/api/admin/*`, JWKS             |
| 13306     | mysql     | direct DB asserts (seed/verify)  |
| 16379     | redis     | debugging only                   |
| 19092     | kafka     | host-side producer/consumer (DLQ scenario) |

## Scenarios

| Test class                        | Summary                                                               |
|-----------------------------------|-----------------------------------------------------------------------|
| `GoldenPathE2ETest`               | JWKS → enroll → verify → login → refresh → logout → token revoked    |
| `CrossServiceBulkLockE2ETest`     | admin bulk-lock → account_db LOCKED → security_db account_lock_history|
| `RefreshReuseDetectionE2ETest`    | rotate refresh, then reuse original → chain-wide revoke               |
| `DlqHandlingE2ETest`              | malformed `auth.login.succeeded` → `.dlq` topic arrival + observability|

## Fixtures

- `ComposeFixture` — JUnit 5 extension; singleton compose stack for the suite.
- `E2EBase`        — base class wiring RestAssured to admin-service.
- `TotpTestUtil`   — independent RFC 6238 HMAC-SHA1 TOTP generator (does NOT depend
                     on admin-service's internal `TotpGenerator`).
- `OperatorSessionHelper` — encapsulates the dev SUPER_ADMIN login + first-time
                     2FA enrollment (idempotent across tests within a JVM).

## Known Limitations

- MySQL/Kafka/Redis ports are host-mapped only in `docker-compose.e2e.yml`. If
  another process on the host uses 13306/16379/19092, stop it before running.
- The TOTP step boundary is guarded by a 2.5s sleep when within the last 2s of
  the 30s window; worst-case run time adds ~3s.
- The DLQ scenario relies on Spring Kafka's default `DefaultErrorHandler` routing
  malformed payloads to `<topic>.dlq`. If a service overrides the handler to a
  different naming scheme, adjust `DLQ_TOPIC` accordingly.
- bootJar staleness is NOT auto-detected. Re-run the `bootJar` command above
  before invoking `:tests:e2e:test` if service code changed.
- Docker absence → Testcontainers fails fast; there is no skip path (TASK-BE-041c
  Failure Scenarios §1).
