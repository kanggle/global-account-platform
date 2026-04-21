# Testing Strategy

Defines the platform-wide testing requirements and patterns for all services.

---

# Test Pyramid

```
        [E2E / Contract]       ← minimal, slow, high-cost
      [Integration Tests]      ← Testcontainers, real DB/cache
    [Slice / Component Tests]  ← controller-level isolation
  [Unit Tests]                 ← pure logic, no framework
```

Every service must have coverage at all four levels unless the level is explicitly not applicable.

---

# Test Types

## Unit Tests

- Test a single class in isolation.
- Mock dependencies.
- Must not start the Spring context.
- Coverage target: all service/domain logic.

**Naming:** `*Test.java`

## Controller Slice Tests

- Test HTTP request/response mapping, validation, and exception handling.
- Use controller-level isolation (mock all service dependencies).
- Must verify security configuration and global exception handling behavior.

**Naming:** `*ControllerTest.java`

## Integration Tests

- Test real interactions with DB and cache using Testcontainers.
- Start full Spring context.
- Must not use H2 or in-memory substitutes for persistence layers.

**Naming:** `*IntegrationTest.java`

## Event Consumer / Producer Tests

- Test event publishing and consuming with Testcontainers Kafka.
- Producer tests: verify the correct event envelope is published after a business action completes.
- Consumer tests: verify that consuming a valid event triggers the expected side effect.
- Idempotency tests: verify that consuming the same event twice produces the same result.
- DLQ tests: verify that a malformed event is routed to the dead-letter queue, not silently dropped.

**Naming:** `*EventTest.java` (unit), `*EventIntegrationTest.java` (with Testcontainers Kafka)

## Contract Tests (future)

- Verify that HTTP API responses match published contracts.
- Tool: Spring Cloud Contract or Pact (to be decided per service).

---

# Required Tests Per Task

Every backend task with `code` tag must include:

| Layer | Test Type |
|---|---|
| Domain entity | Unit |
| Application service | Unit |
| Controller | Slice |
| Full flow | Integration |

For implementation details (annotations, imports, container images, setup code), see `.claude/skills/backend/testing-backend/SKILL.md`.

---

# Testcontainers Conventions

- Use real containers via Testcontainers. Do not use H2 or in-memory substitutes.
- Container image versions are specified in `.claude/skills/backend/testing-backend/SKILL.md`.

## Container Lifecycle

- Declare containers as `static` fields annotated with `@Container` on the test
  class and let JUnit's `@Testcontainers` extension manage the lifecycle. One
  container instance is shared across every `@Test` in the class.
- If a shared base class (`AbstractIntegrationTest` or similar) exists, declare
  `@Container` there so every subclass inherits the same instance. Do **not**
  create a shared base purely to unify containers — migrate only when an
  opportunity already exists.
- Never hardcode container host ports. Rely on Testcontainers' randomly mapped
  ports and wire them into Spring via `@DynamicPropertySource`.
- `@DynamicPropertySource` suppliers are evaluated lazily when Spring builds
  the `ApplicationContext`. The container must already be started by then; if
  you need a value before context startup (e.g., WireMock base URL), start the
  auxiliary server in a `static { }` block or inside the
  `@DynamicPropertySource` method itself.

## Wait Strategy and Startup Timeout

The CI runner is frequently slower than a developer laptop. To avoid spurious
`ContainerLaunchException: ...timed out` failures, every container used in an
integration test must declare:

- `.withStartupTimeout(Duration.ofMinutes(3))` on MySQL, Kafka, and any other
  `GenericContainer` where default startup exceeds a minute on CI.
- `.waitingFor(Wait.forLogMessage(".*\\[KafkaServer id=\\d+\\] started.*", 1))`
  on `KafkaContainer`. Port-listening alone (`Wait.forListeningPort()`) does
  not guarantee broker metadata has been published; CI runs observed
  Producer/Consumer first-connect races where the broker port was open but
  advertised-listeners had not propagated, surfacing as `Node 1 disconnected,
  Connection could not be established` (TASK-BE-075 diagnosis from TASK-BE-074
  CI artifacts). The log pattern matches both `confluentinc/cp-kafka:7.5.x`
  and `7.6.x`. If a future image changes this line, fall back to
  `Wait.forListeningPort()` combined with an application-level `Awaitility`
  poll for metadata readiness — do not silently wait for a log pattern that
  never matches.

MySQL's default `Wait.forLogMessage` strategy is sufficient — do not override
it unless the test image changes.

## Producer / Consumer Retry Tuning (Test Profile Only)

Integration tests that publish to or consume from Kafka must run with the
following producer **and** consumer overrides so that a transient broker drop
(common under a heavily loaded CI runner, or random port assignment between
container restarts) does not fail the test before Kafka recovers:

```yaml
spring:
  kafka:
    consumer:
      properties:
        reconnect.backoff.ms: 500
        reconnect.backoff.max.ms: 10000
        request.timeout.ms: 60000
    producer:
      properties:
        reconnect.backoff.ms: 500
        reconnect.backoff.max.ms: 10000
        request.timeout.ms: 60000
```

Keep these in `src/test/resources/application-test.yml` (or equivalent). Do
**not** copy them into the production profile — tighter defaults are correct
for production.

Rationale for the tighter `reconnect.backoff.ms=500` vs the earlier 1000ms
default (TASK-BE-075): CI sees random port assignment between Testcontainers
restarts, so aggressive reconnect is the difference between a test passing on
the second metadata refresh and failing on a stale cached endpoint. The
`reconnect.backoff.max.ms=10000` cap prevents the client from drifting into
60s+ backoff once recovery succeeds.

## MySQL Hikari Validation (Test Profile Only)

Integration tests that share a JVM across multiple `@SpringBootTest` classes
must configure Hikari to validate every connection borrow and recycle idle
connections aggressively:

```yaml
spring:
  datasource:
    hikari:
      validation-timeout: 3000
      connection-test-query: SELECT 1
      max-lifetime: 60000
      keepalive-time: 30000
      leak-detection-threshold: 10000
```

Rationale (TASK-BE-075, root cause confirmed from TASK-BE-074 CI artifacts):
when a prior test class' MySQL Testcontainer is stopped and a new one is
started for the next class, Hikari may hand out a connection cached against
the stopped container. This surfaces as a `Communications link failure` inside
`OutboxPollingScheduler.pollAndPublish`, which fails the transaction and
produces HTTP 503 responses from otherwise healthy controllers.

- `validation-timeout: 3000` — cap the validation call at 3s so a dead
  connection is discarded quickly.
- `connection-test-query: SELECT 1` — provide a minimal query as a backup to
  the JDBC `isValid()` check (some MySQL driver versions return stale `true`).
- `max-lifetime: 60000` — force recycling during the short test lifetime.
- `keepalive-time: 30000` — proactively validate idle connections.
- `leak-detection-threshold: 10000` — surface accidental connection leaks
  before they starve the pool.

Invariant: `max-lifetime` must be strictly greater than `keepalive-time`
(60000 > 30000). Hikari refuses to start otherwise. Do **not** copy these
tight values into the production profile — the pool there should favour
long-lived connections and trust the DB to enforce server-side timeouts.

## Reuse Policy

Testcontainers supports container reuse across JVM runs via
`testcontainers.reuse.enable=true` in `~/.testcontainers.properties`.

- **Local development**: enabling reuse is recommended for fast feedback
  loops. Opt in per developer by editing `~/.testcontainers.properties` and
  adding `.withReuse(true)` (or `.withLabel("reusable", "true")`) on
  containers. This is a developer-local optimisation and does not need to be
  checked into the repository.
- **CI runners**: reuse must stay **disabled**. CI relies on a clean container
  per test session to avoid cross-test leakage and to match production
  startup behaviour. Do not add `.withReuse(true)` unconditionally in test
  source.

If you add reuse support behind a flag, scope it to a per-developer system
property so CI remains unaffected.

## Docker Availability Guard

For tests that must run on machines without Docker, gate the class with
`@EnabledIf("isDockerAvailable")` and check
`DockerClientFactory.instance().isDockerAvailable()` (or equivalent). This
lets `./gradlew test` stay green on developer machines that have no Docker
while the same tests execute on CI.

---

# Naming Conventions

| Test Type | Naming Pattern | Example |
|---|---|---|
| Unit (service) | `{ServiceName}Test` | `LoginServiceTest` |
| Unit (entity) | `{EntityName}Test` | `UserTest` |
| Unit (infrastructure) | `{ClassName}UnitTest` | `RedisUserSessionRegistryUnitTest` |
| Controller slice | `{ControllerName}Test` | `AuthControllerTest` |
| Integration (infrastructure) | `{ClassName}Test` | `RedisUserSessionRegistryTest` |
| Integration (full flow) | `{Feature}IntegrationTest` | `AuthSignupLoginIntegrationTest` |
| Event (unit) | `{EventName}EventTest` | `UserSignedUpEventTest` |
| Event (integration) | `{Feature}EventIntegrationTest` | `AuthEventPublishIntegrationTest` |

---

# Rules

- Tests must not share mutable state across test methods.
- Each test method must be independent and idempotent.
- Test method names must describe the scenario: `{scenario}_{condition}_{expectedResult}`.
- Production code must not contain test-only annotations or conditionals.
- Testcontainers tests must clean up or use isolated data per test (unique emails, IDs, etc.).
- Use `@DisplayName` with Korean descriptions for test readability.

---

# Change Rule

Changes to test standards must be reflected here before applying to services.
