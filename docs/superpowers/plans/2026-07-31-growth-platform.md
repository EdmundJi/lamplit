# Growth Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build and verify the complete documented MVP for the 「成为更好的自己」 growth platform, including the user journey, Qwen safety pipeline, privacy lifecycle, administration, and full data-flow self-test.

**Architecture:** Use a Vue 3 mobile-first SPA against a Spring Boot 3 modular monolith. MySQL is authoritative, Redis is disposable acceleration, MinIO provides S3-compatible files, and Qwen is isolated behind a provider plus two-stage safety checks. Implement vertical slices so each business flow reaches UI, API, persistence, and tests before the next slice starts.

**Tech Stack:** Java 21, Spring Boot 3, Maven, MySQL 8, Redis 7, Flyway, MinIO, Vue 3, TypeScript, Vite, Pinia, Vue Router, ECharts, Vitest, Testing Library, Playwright, Docker Compose, Nginx.

---

## File Structure

```text
.
├── .env.example
├── .gitignore
├── README.md
├── backend/
│   ├── pom.xml
│   ├── mvnw
│   ├── .mvn/wrapper/
│   └── src/
│       ├── main/java/com/betterself/growth/
│       │   ├── GrowthApplication.java
│       │   ├── shared/       # API envelopes, request IDs, time, ULID, storage, outbox
│       │   ├── auth/         # Cookie sessions, CSRF, password reset, TOTP MFA
│       │   ├── identity/     # Profile, consent, preferences, notifications
│       │   ├── goal/         # Dimensions, goals, plans, tasks and schedules
│       │   ├── execution/    # Task event state machine, idempotency and experience
│       │   ├── insight/      # Metrics, trends, recovery and weekly review
│       │   ├── ai/           # Qwen provider, sessions, messages, suggestions and SSE
│       │   ├── safety/       # Risk classification, scene rules and crisis response
│       │   ├── privacy/      # Export, retention, deletion and attachments
│       │   └── admin/        # Versioned content, audit and dashboards
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── application-local.yml
│       │   └── db/migration/
│       └── test/java/com/betterself/growth/
├── frontend/
│   ├── package.json
│   ├── vite.config.ts
│   ├── playwright.config.ts
│   └── src/
│       ├── app/              # Bootstrap, router, layouts and guards
│       ├── modules/          # auth, onboarding, today, goals, insights, ai, settings, admin
│       └── shared/           # API, SSE, UI, validation and telemetry
├── deploy/
│   ├── compose.yaml
│   └── nginx/default.conf
├── e2e/
│   ├── fixtures/
│   ├── global-flow.spec.ts
│   ├── ownership.spec.ts
│   └── responsive.spec.ts
└── scripts/
    ├── seed-local.sh
    ├── smoke-api.sh
    └── verify-global-flow.sh
```

## Task 1: Repository, Runtime, and Infrastructure Foundation

**Files:**
- Create: `.gitignore`
- Create: `.env.example`
- Create: `README.md`
- Create: `deploy/compose.yaml`
- Create: `deploy/nginx/default.conf`
- Create: `backend/pom.xml`
- Create: `backend/src/main/java/com/betterself/growth/GrowthApplication.java`
- Create: `backend/src/main/resources/application.yml`
- Create: `backend/src/main/resources/application-local.yml`
- Create: `backend/src/test/java/com/betterself/growth/GrowthApplicationTest.java`
- Create: `frontend/package.json`
- Create: `frontend/tsconfig.json`
- Create: `frontend/vite.config.ts`
- Create: `frontend/index.html`
- Create: `frontend/src/main.ts`
- Create: `frontend/src/App.vue`
- Test: `frontend/src/App.test.ts`

- [ ] **Step 1: Write the failing backend and frontend boot tests**

```java
package com.betterself.growth;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.flyway.enabled=false")
class GrowthApplicationTest {
    @Test
    void contextLoads() {}
}
```

```ts
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import App from './App.vue'

describe('App', () => {
  it('renders the product name', () => {
    expect(mount(App).text()).toContain('更好的自己')
  })
})
```

- [ ] **Step 2: Run the tests and verify the missing projects fail**

Run: `cd backend && mvn test -Dtest=GrowthApplicationTest`  
Expected: FAIL because `pom.xml` and `GrowthApplication` do not exist.

Run: `cd frontend && pnpm test --run src/App.test.ts`  
Expected: FAIL because the frontend package and app do not exist.

- [ ] **Step 3: Create minimal bootable projects and local infrastructure**

Use Spring Boot `3.5.x`, Java 21, and dependencies for Web, Security, Validation, Data JPA, Redis, Actuator, Flyway MySQL, Jackson, JWT, Argon2, TOTP, AWS S3, springdoc, Testcontainers, and REST Assured. Use Vue `3.5.x`, TypeScript, Vite, Pinia, Vue Router, ECharts, Zod, lucide-vue-next, Vitest, Testing Library, axe-core, and Playwright.

The application entry point must be complete and minimal:

```java
package com.betterself.growth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class GrowthApplication {
    public static void main(String[] args) {
        SpringApplication.run(GrowthApplication.class, args);
    }
}
```

`deploy/compose.yaml` must define healthy `mysql:8.4`, `redis:7.4-alpine`, and `minio/minio` services with project-scoped named volumes. `.env.example` must contain variable names and harmless examples only. `.gitignore` must include `.env.local`, `.superpowers/`, `frontend/node_modules/`, `frontend/dist/`, `backend/target/`, Playwright artifacts, and IDE files.

- [ ] **Step 4: Verify the foundation**

Run: `cd backend && ./mvnw test -Dtest=GrowthApplicationTest`  
Expected: PASS.

Run: `cd frontend && pnpm install && pnpm test --run`  
Expected: PASS.

Run: `docker compose --env-file .env.local -f deploy/compose.yaml config`  
Expected: valid Compose configuration with no unresolved required variable.

- [ ] **Step 5: Commit the foundation**

```bash
git add .gitignore .env.example README.md deploy backend frontend
git commit -m "chore: scaffold growth platform"
```

## Task 2: Shared API Contract and Versioned Database

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/shared/api/ApiEnvelope.java`
- Create: `backend/src/main/java/com/betterself/growth/shared/api/ApiError.java`
- Create: `backend/src/main/java/com/betterself/growth/shared/api/GlobalExceptionHandler.java`
- Create: `backend/src/main/java/com/betterself/growth/shared/web/RequestIdFilter.java`
- Create: `backend/src/main/java/com/betterself/growth/shared/time/AppClockConfig.java`
- Create: `backend/src/main/java/com/betterself/growth/shared/id/PublicIdGenerator.java`
- Create: `backend/src/main/resources/db/migration/V1__baseline.sql`
- Create: `backend/src/main/resources/db/migration/V2__support_tables.sql`
- Create: `backend/src/main/resources/db/migration/V3__seed_system_dimensions_and_templates.sql`
- Create: `backend/src/main/resources/db/migration/V4__seed_prompt_and_safety_versions.sql`
- Test: `backend/src/test/java/com/betterself/growth/shared/DatabaseMigrationIT.java`
- Test: `backend/src/test/java/com/betterself/growth/shared/ApiEnvelopeTest.java`

- [ ] **Step 1: Write migration and envelope tests**

```java
@Testcontainers
@SpringBootTest
class DatabaseMigrationIT {
    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
    }

    @Autowired JdbcTemplate jdbc;

    @Test
    void createsCompleteBaseline() {
        Integer count = jdbc.queryForObject(
            "select count(*) from information_schema.tables where table_schema = database()",
            Integer.class
        );
        assertThat(count).isGreaterThanOrEqualTo(29);
        assertThat(jdbc.queryForObject("select count(*) from growth_dimension where is_system=1", Integer.class)).isEqualTo(5);
    }
}
```

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=DatabaseMigrationIT,ApiEnvelopeTest`  
Expected: FAIL because the migrations and shared response types do not exist.

- [ ] **Step 3: Implement schema and API primitives**

`V1` must create the documented identity, goal, task, AI, privacy, and audit tables. `V2` must add `password_reset_token`, `suggestion_set`, `suggestion_item`, `knowledge_source`, `attachment`, `outbox_event`, `product_event`, and `notification_delivery` with foreign keys, unique keys, status checks, and UTC `DATETIME(3)` timestamps. `V3` seeds five system dimensions and at least five reviewed low-risk templates per scene. `V4` seeds one published prompt version per scene and a versioned L3 response policy.

Use one response shape everywhere:

```java
public record ApiEnvelope<T>(T data, String requestId, Instant timestamp) {
    public static <T> ApiEnvelope<T> of(T data, String requestId, Clock clock) {
        return new ApiEnvelope<>(data, requestId, clock.instant());
    }
}
```

`RequestIdFilter` accepts a valid incoming `X-Request-ID` or creates a ULID, adds it to MDC and the response header, and always clears MDC in `finally`.

- [ ] **Step 4: Verify GREEN and migration repeatability**

Run: `cd backend && ./mvnw test -Dtest=DatabaseMigrationIT,ApiEnvelopeTest`  
Expected: PASS and Flyway reports schema version 4.

Run: `cd backend && ./mvnw flyway:info -Dflyway.url=jdbc:mysql://127.0.0.1:3306/growth -Dflyway.user=growth_app -Dflyway.password="$MYSQL_PASSWORD"`  
Expected: all four migrations show `Success` or `Pending`, never `Failed`.

- [ ] **Step 5: Commit shared infrastructure**

```bash
git add backend/src/main/java/com/betterself/growth/shared backend/src/main/resources/db backend/src/test/java/com/betterself/growth/shared
git commit -m "feat: add shared API and database baseline"
```

## Task 3: Authentication, Consent, and Preferences Slice

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/auth/AuthController.java`
- Create: `backend/src/main/java/com/betterself/growth/auth/AuthService.java`
- Create: `backend/src/main/java/com/betterself/growth/auth/SessionService.java`
- Create: `backend/src/main/java/com/betterself/growth/auth/CookieFactory.java`
- Create: `backend/src/main/java/com/betterself/growth/auth/CsrfDoubleSubmitFilter.java`
- Create: `backend/src/main/java/com/betterself/growth/auth/SecurityConfig.java`
- Create: `backend/src/main/java/com/betterself/growth/auth/MfaService.java`
- Create: `backend/src/main/java/com/betterself/growth/auth/AgePolicy.java`
- Create: `backend/src/main/java/com/betterself/growth/identity/IdentityController.java`
- Create: `backend/src/main/java/com/betterself/growth/identity/IdentityService.java`
- Test: `backend/src/test/java/com/betterself/growth/auth/AgePolicyTest.java`
- Test: `backend/src/test/java/com/betterself/growth/auth/AuthFlowIT.java`
- Test: `backend/src/test/java/com/betterself/growth/auth/RefreshReplayIT.java`

- [ ] **Step 1: Write the failing age, Cookie, CSRF, and replay tests**

```java
@Test
void rejectsUserWhoIsNotEighteenInTheirTimezone() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-31T04:00:00Z"), ZoneOffset.UTC);
    assertThat(AgePolicy.isAdult(LocalDate.parse("2008-08-01"), ZoneId.of("Asia/Shanghai"), clock)).isFalse();
}

@Test
void acceptsUserOnEighteenthBirthday() {
    Clock clock = Clock.fixed(Instant.parse("2026-07-31T04:00:00Z"), ZoneOffset.UTC);
    assertThat(AgePolicy.isAdult(LocalDate.parse("2008-07-31"), ZoneId.of("Asia/Shanghai"), clock)).isTrue();
}
```

`AuthFlowIT` must register an adult with three current consent versions, assert `access_token`, `refresh_token`, and `csrf_token` cookies, assert token fields are absent from JSON, reject a write without `X-CSRF-Token`, and accept it with the cookie value. `RefreshReplayIT` must use an old refresh token twice and assert the whole token family is revoked.

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=AgePolicyTest,AuthFlowIT,RefreshReplayIT`  
Expected: FAIL because the auth and identity slice is absent.

- [ ] **Step 3: Implement the auth and identity contracts**

Use Argon2id for passwords, a 30-minute signed access JWT, a 7-day opaque refresh token, and SHA-256 only for refresh/reset token hashing. Register `CsrfDoubleSubmitFilter` after authentication and require a header/cookie constant-time match for POST, PUT, PATCH, and DELETE.

The public service contract must be:

```java
public interface SessionService {
    IssuedSession issue(long userId, String deviceLabel, HttpServletRequest request);
    IssuedSession rotate(String rawRefreshToken, HttpServletRequest request);
    void revokeCurrent(long userId, String rawRefreshToken);
    void revokeAll(long userId);
}
```

Registration must write `sys_user`, three `consent_record` rows, `user_preference`, and five `user_dimension` rows in one transaction. Implement `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `/auth/logout-all`, password forgot/reset/update, `/me`, `/me/preferences`, `/me/consents`, and AI memory preference endpoints. Admin login remains `MFA_PENDING` until a valid TOTP is submitted.

- [ ] **Step 4: Verify GREEN**

Run: `cd backend && ./mvnw test -Dtest=AgePolicyTest,AuthFlowIT,RefreshReplayIT`  
Expected: PASS with no raw token in JSON or logs.

Run: `cd backend && ./mvnw verify -Dgroups=security`  
Expected: Cookie flags, CSRF, ownership principal, and refresh replay tests pass.

- [ ] **Step 5: Commit auth and identity**

```bash
git add backend/src/main/java/com/betterself/growth/auth backend/src/main/java/com/betterself/growth/identity backend/src/test/java/com/betterself/growth/auth
git commit -m "feat: add secure authentication and onboarding preferences"
```

## Task 4: Dimensions, Goals, Weekly Plans, and Task Definitions

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/goal/DimensionController.java`
- Create: `backend/src/main/java/com/betterself/growth/goal/GoalController.java`
- Create: `backend/src/main/java/com/betterself/growth/goal/WeeklyPlanController.java`
- Create: `backend/src/main/java/com/betterself/growth/goal/TaskController.java`
- Create: `backend/src/main/java/com/betterself/growth/goal/GoalService.java`
- Create: `backend/src/main/java/com/betterself/growth/goal/PlanningService.java`
- Create: `backend/src/main/java/com/betterself/growth/goal/RecurrenceExpander.java`
- Test: `backend/src/test/java/com/betterself/growth/goal/GoalPolicyTest.java`
- Test: `backend/src/test/java/com/betterself/growth/goal/RecurrenceExpanderTest.java`
- Test: `backend/src/test/java/com/betterself/growth/goal/GoalPlanningIT.java`

- [ ] **Step 1: Write failing goal and timezone tests**

```java
@Test
void expandsWeeklyRuleInUserTimezoneAcrossDst() {
    RecurrenceRule rule = RecurrenceRule.parse("FREQ=WEEKLY;BYDAY=MO,WE,FR");
    List<PlannedOccurrence> result = expander.expand(
        rule,
        LocalDate.parse("2026-03-02"),
        LocalTime.parse("07:30"),
        ZoneId.of("America/New_York"),
        LocalDate.parse("2026-03-15")
    );
    assertThat(result).hasSize(6);
    assertThat(result.get(3).localDate()).isEqualTo(LocalDate.parse("2026-03-09"));
    assertThat(result.get(3).instant()).isEqualTo(Instant.parse("2026-03-09T11:30:00Z"));
}
```

`GoalPlanningIT` must reject a fourth active goal, reject a duration below 14 or above 84 days, archive a used dimension instead of deleting it, and generate unique `(task_id, planned_start_at)` schedules.

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=GoalPolicyTest,RecurrenceExpanderTest,GoalPlanningIT`  
Expected: FAIL because goal planning is missing.

- [ ] **Step 3: Implement goal planning**

Implement the documented dimension, goal, weekly plan, and task endpoints. Parse only the supported RFC 5545 subset: `FREQ=DAILY|WEEKLY`, `INTERVAL`, `BYDAY`, and `COUNT|UNTIL`; reject all other properties with `INVALID_RRULE`.

Expose this planning boundary:

```java
public interface PlanningService {
    WeeklyPlanView createWeeklyPlan(long userId, CreateWeeklyPlanCommand command);
    TaskView createTask(long userId, CreateTaskCommand command);
    List<TaskScheduleView> materializeWeek(long userId, String weeklyPlanPublicId);
}
```

Every generated occurrence stores UTC `planned_start_at`, local date, and IANA timezone. Never use server default timezone.

- [ ] **Step 4: Verify GREEN**

Run: `cd backend && ./mvnw test -Dtest=GoalPolicyTest,RecurrenceExpanderTest,GoalPlanningIT`  
Expected: PASS.

- [ ] **Step 5: Commit planning**

```bash
git add backend/src/main/java/com/betterself/growth/goal backend/src/test/java/com/betterself/growth/goal
git commit -m "feat: add goals weekly plans and recurring tasks"
```

## Task 5: Task Event State Machine, Idempotency, and Experience

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/execution/TaskEventController.java`
- Create: `backend/src/main/java/com/betterself/growth/execution/TaskExecutionService.java`
- Create: `backend/src/main/java/com/betterself/growth/execution/TaskStateMachine.java`
- Create: `backend/src/main/java/com/betterself/growth/execution/ExperienceCalculator.java`
- Create: `backend/src/main/java/com/betterself/growth/execution/IdempotencyService.java`
- Create: `backend/src/main/java/com/betterself/growth/execution/ScheduleExpiryJob.java`
- Test: `backend/src/test/java/com/betterself/growth/execution/TaskStateMachineTest.java`
- Test: `backend/src/test/java/com/betterself/growth/execution/ExperienceCalculatorTest.java`
- Test: `backend/src/test/java/com/betterself/growth/execution/TaskExecutionIT.java`
- Test: `backend/src/test/java/com/betterself/growth/execution/TaskExecutionConcurrencyIT.java`

- [ ] **Step 1: Write failing state and idempotency tests**

```java
@ParameterizedTest
@CsvSource({
    "COMPLETED,30,3,1.0,9",
    "PARTIAL,30,3,0.5,5",
    "SKIPPED,30,3,0.0,0",
    "DEFERRED,30,3,0.0,0"
})
void calculatesDocumentedExperience(TaskEventType type, int minutes, int difficulty, double ratio, int expected) {
    assertThat(calculator.earned(type, minutes, difficulty, ratio, 100)).isEqualTo(expected);
}
```

`TaskExecutionIT` must submit the same idempotency key twice and assert one event and one experience update; reuse the key with a different body and expect 409; defer and assert a linked new schedule; skip and assert `SKIPPED`; expire an untouched schedule and assert zero experience; reverse the latest terminal event and assert metrics are recomputed.

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=TaskStateMachineTest,ExperienceCalculatorTest,TaskExecutionIT`  
Expected: FAIL because execution behavior is missing.

- [ ] **Step 3: Implement the transactional event flow**

Implement one `@Transactional` method that loads the user-owned schedule with a pessimistic lock, validates state, checks the durable MySQL idempotency row, writes the immutable event and snapshot, updates state/dimensions/weekly aggregate, creates a deferred schedule when required, writes Outbox/product events, stores the response, commits, and then invalidates the Redis overview key.

```java
public int earned(TaskEventType type, int estimatedMinutes, int difficulty, double completionRatio, int remainingDailyCap) {
    int base = Math.min(30, Math.max(2, (int) Math.ceil(estimatedMinutes / 10.0) * difficulty));
    double ratio = switch (type) {
        case COMPLETED -> 1.0;
        case PARTIAL -> completionRatio;
        default -> 0.0;
    };
    return Math.min(remainingDailyCap, (int) Math.round(base * ratio));
}
```

The expiry job changes only untouched elapsed schedules to `EXPIRED`. Reversal writes a `REVERSED` event and never deletes history.

- [ ] **Step 4: Verify GREEN and concurrency safety**

Run: `cd backend && ./mvnw test -Dtest=TaskStateMachineTest,ExperienceCalculatorTest,TaskExecutionIT`  
Expected: PASS.

Run: `cd backend && ./mvnw test -Dtest=TaskExecutionConcurrencyIT`  
Expected: ten concurrent duplicate requests produce one terminal event.

- [ ] **Step 5: Commit execution**

```bash
git add backend/src/main/java/com/betterself/growth/execution backend/src/test/java/com/betterself/growth/execution
git commit -m "feat: add idempotent task execution state machine"
```

## Task 6: Insights and Weekly Reviews

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/insight/InsightController.java`
- Create: `backend/src/main/java/com/betterself/growth/insight/InsightService.java`
- Create: `backend/src/main/java/com/betterself/growth/insight/WeeklyReviewController.java`
- Create: `backend/src/main/java/com/betterself/growth/insight/WeeklyReviewService.java`
- Create: `backend/src/main/java/com/betterself/growth/insight/WeeklyMetricsCalculator.java`
- Create: `backend/src/main/java/com/betterself/growth/insight/MetricRebuildJob.java`
- Test: `backend/src/test/java/com/betterself/growth/insight/InsightServiceTest.java`
- Test: `backend/src/test/java/com/betterself/growth/insight/WeeklyReviewIT.java`

- [ ] **Step 1: Write failing metric tests**

```java
@Test
void countsOnlyUnreversedCompletedAndPartialEventsAsEffective() {
    WeeklyFacts facts = new WeeklyFacts(4, List.of(
        event(COMPLETED, false), event(PARTIAL, false), event(SKIPPED, false), event(COMPLETED, true)
    ));
    WeeklyMetrics metrics = calculator.calculate(facts);
    assertThat(metrics.effectiveActions()).isEqualTo(2);
    assertThat(metrics.fulfillmentRate()).isEqualByComparingTo("0.500");
}
```

Also test recovery only after seven complete inactive days, personal best only from the current user, and weekly review confirmation not changing next-week settings until the user confirms adjustments.

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=InsightServiceTest,WeeklyReviewIT`  
Expected: FAIL because insights and reviews are absent.

- [ ] **Step 3: Implement deterministic metrics first**

Implement `/insights/overview`, `/insights/trends`, `/insights/calendar`, `/reviews/weekly/{planId}`, and confirmation. Build review facts entirely from event data before any AI call. Cache overview for five minutes, invalidate it after task writes, and rebuild from task events when aggregates drift.

- [ ] **Step 4: Verify GREEN**

Run: `cd backend && ./mvnw test -Dtest=InsightServiceTest,WeeklyReviewIT`  
Expected: PASS with no query or response that includes other users' ranks.

- [ ] **Step 5: Commit insights**

```bash
git add backend/src/main/java/com/betterself/growth/insight backend/src/test/java/com/betterself/growth/insight
git commit -m "feat: add personal insights and weekly reviews"
```

## Task 7: Qwen Provider, Structured Suggestions, SSE, and Safety

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/ai/QwenProvider.java`
- Create: `backend/src/main/java/com/betterself/growth/ai/QwenHttpProvider.java`
- Create: `backend/src/main/java/com/betterself/growth/ai/MockQwenProvider.java`
- Create: `backend/src/main/java/com/betterself/growth/ai/AiController.java`
- Create: `backend/src/main/java/com/betterself/growth/ai/AiService.java`
- Create: `backend/src/main/java/com/betterself/growth/ai/SuggestionService.java`
- Create: `backend/src/main/java/com/betterself/growth/safety/RiskClassifier.java`
- Create: `backend/src/main/java/com/betterself/growth/safety/SafetyService.java`
- Create: `backend/src/main/java/com/betterself/growth/safety/CrisisResponseService.java`
- Test: `backend/src/test/java/com/betterself/growth/safety/SafetyServiceTest.java`
- Test: `backend/src/test/java/com/betterself/growth/ai/QwenContractTest.java`
- Test: `backend/src/test/java/com/betterself/growth/ai/AiFlowIT.java`

- [ ] **Step 1: Write failing safety and provider contract tests**

```java
@ParameterizedTest
@ValueSource(strings = {"我不想活了", "我准备伤害自己", "我可能会伤害别人"})
void sendsCrisisSignalsToL3(String input) {
    SafetyDecision decision = safety.classifyInput(Scene.EMOTIONAL_SUPPORT, input);
    assertThat(decision.level()).isEqualTo(RiskLevel.L3);
    assertThat(decision.allowGeneration()).isFalse();
    assertThat(decision.actions()).contains("CALL_LOCAL_EMERGENCY", "CONTACT_TRUSTED_PERSON");
}
```

`QwenContractTest` must use a local stub server to verify OpenAI-compatible headers, configurable model, timeout, token metadata, one JSON repair attempt, and rejection after a second invalid result. `AiFlowIT` must assert SSE event order `meta -> delta* -> done`, ownership, suggestion expiry, and idempotent adoption.

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=SafetyServiceTest,QwenContractTest,AiFlowIT`  
Expected: FAIL because AI and safety modules are absent.

- [ ] **Step 3: Implement provider isolation and two-stage safety**

```java
public interface QwenProvider {
    StructuredResult generateStructured(StructuredPrompt prompt);
    void stream(ChatPrompt prompt, Consumer<String> deltaConsumer);
    Classification classify(ClassificationPrompt prompt);
}
```

Input safety runs before context assembly. L2 returns a professional-boundary response; L3 returns only the versioned fixed crisis response and records a redacted safety event. For L0/L1, inject only confirmed preferences, aggregate facts, confirmed memories, and reviewed knowledge. Validate structured suggestions against 1-5 items, 5-60 minutes, difficulty 1-3, dimension weight total 1-30, and quiet hours. Run output safety before exposing any content.

Use `SseEmitter` with only `meta`, `delta`, `safety`, `done`, and `error`. Propagate disconnect cancellation. Do not retry after any delta is emitted.

- [ ] **Step 4: Verify GREEN and evaluate the fixed safety corpus**

Run: `cd backend && ./mvnw test -Dtest=SafetyServiceTest,QwenContractTest,AiFlowIT`  
Expected: PASS.

Run: `cd backend && ./mvnw test -Pqwen-eval -Dai.provider=mock`  
Expected: 100% L3 recall, no diagnosis/prescription in L2, and valid structured JSON rate at least 99%.

- [ ] **Step 5: Commit AI and safety**

```bash
git add backend/src/main/java/com/betterself/growth/ai backend/src/main/java/com/betterself/growth/safety backend/src/test/java/com/betterself/growth/ai backend/src/test/java/com/betterself/growth/safety
git commit -m "feat: add safe Qwen assistant and suggestions"
```

## Task 8: Privacy, Export, Deletion, Attachments, and Notifications

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/privacy/PrivacyController.java`
- Create: `backend/src/main/java/com/betterself/growth/privacy/ExportService.java`
- Create: `backend/src/main/java/com/betterself/growth/privacy/DeletionService.java`
- Create: `backend/src/main/java/com/betterself/growth/privacy/RetentionJob.java`
- Create: `backend/src/main/java/com/betterself/growth/privacy/AttachmentController.java`
- Create: `backend/src/main/java/com/betterself/growth/shared/storage/ObjectStorage.java`
- Create: `backend/src/main/java/com/betterself/growth/identity/NotificationController.java`
- Create: `backend/src/main/java/com/betterself/growth/identity/NotificationService.java`
- Test: `backend/src/test/java/com/betterself/growth/privacy/PrivacyFlowIT.java`
- Test: `backend/src/test/java/com/betterself/growth/privacy/RetentionPolicyTest.java`
- Test: `backend/src/test/java/com/betterself/growth/privacy/AttachmentPolicyTest.java`

- [ ] **Step 1: Write failing privacy lifecycle tests**

`PrivacyFlowIT` must create one export per 24 hours, verify JSON and CSV members, reject a second user's download, expire the object after 24 hours, create a deletion request with exactly seven days cooling-off, revoke sessions, allow cancellation, and process deletion using an injected clock. `AttachmentPolicyTest` must reject unsupported MIME/extension pairs and prevent unscanned object association.

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=PrivacyFlowIT,RetentionPolicyTest,AttachmentPolicyTest`  
Expected: FAIL because privacy/file behavior is missing.

- [ ] **Step 3: Implement explicit lifecycle states**

Implement `/privacy/exports`, status, download, deletion create/current/cancel, conversation deletion, AI memory deletion, and attachment presign. Export machine-readable data to a ZIP containing `manifest.json` and per-category CSV files, store with server-side encryption, return a 15-minute presigned URL, and delete after 24 hours.

Deletion uses `COOLING_OFF -> PROCESSING -> COMPLETED|FAILED`, with `CANCELLED` available only before processing. Pause notifications and new AI calls during cooling-off. Notification preferences support `IN_APP`, `EMAIL`, and `WEB_PUSH`; only dispatch configured channels and enforce quiet hours plus `max_per_day`.

- [ ] **Step 4: Verify GREEN**

Run: `cd backend && ./mvnw test -Dtest=PrivacyFlowIT,RetentionPolicyTest,AttachmentPolicyTest`  
Expected: PASS.

- [ ] **Step 5: Commit privacy and notification behavior**

```bash
git add backend/src/main/java/com/betterself/growth/privacy backend/src/main/java/com/betterself/growth/shared/storage backend/src/main/java/com/betterself/growth/identity backend/src/test/java/com/betterself/growth/privacy
git commit -m "feat: add privacy lifecycle files and notifications"
```

## Task 9: Admin Governance, Audit, and Product Metrics

**Files:**
- Create: `backend/src/main/java/com/betterself/growth/admin/AdminController.java`
- Create: `backend/src/main/java/com/betterself/growth/admin/ContentGovernanceService.java`
- Create: `backend/src/main/java/com/betterself/growth/admin/SafetyReviewService.java`
- Create: `backend/src/main/java/com/betterself/growth/admin/AuditService.java`
- Create: `backend/src/main/java/com/betterself/growth/admin/MetricsController.java`
- Create: `backend/src/main/java/com/betterself/growth/shared/outbox/OutboxPublisher.java`
- Test: `backend/src/test/java/com/betterself/growth/admin/AdminGovernanceIT.java`
- Test: `backend/src/test/java/com/betterself/growth/admin/TelemetryPrivacyTest.java`

- [ ] **Step 1: Write failing role and audit tests**

Test that content operators cannot read safety details, safety operators only see redacted excerpts, publication requires MFA plus reviewer, publication creates a new immutable version rather than overwriting, rollback changes the active pointer, and every sensitive action creates an audit row. Test that product events reject email, task notes, AI text, and arbitrary free-text properties.

- [ ] **Step 2: Verify RED**

Run: `cd backend && ./mvnw test -Dtest=AdminGovernanceIT,TelemetryPrivacyTest`  
Expected: FAIL because admin governance is absent.

- [ ] **Step 3: Implement least-privilege administration**

Implement the documented template, prompt, knowledge source, safety event, audit, and dashboard endpoints. Use explicit permissions such as `TEMPLATE_EDIT`, `CONTENT_PUBLISH`, `SAFETY_REVIEW`, `SAFETY_SENSITIVE_READ`, and `AUDIT_READ`. Outbox delivery records product events with a property allowlist and retries without blocking the original transaction.

- [ ] **Step 4: Verify GREEN**

Run: `cd backend && ./mvnw test -Dtest=AdminGovernanceIT,TelemetryPrivacyTest`  
Expected: PASS and no sensitive property reaches `product_event`.

- [ ] **Step 5: Commit governance**

```bash
git add backend/src/main/java/com/betterself/growth/admin backend/src/main/java/com/betterself/growth/shared/outbox backend/src/test/java/com/betterself/growth/admin
git commit -m "feat: add governed administration and telemetry"
```

## Task 10: Frontend Foundation, Design System, Authentication, and Onboarding

**Files:**
- Create: `frontend/src/app/router.ts`
- Create: `frontend/src/app/UserLayout.vue`
- Create: `frontend/src/app/AdminLayout.vue`
- Create: `frontend/src/shared/api/client.ts`
- Create: `frontend/src/shared/api/errors.ts`
- Create: `frontend/src/shared/ui/AppButton.vue`
- Create: `frontend/src/shared/ui/AppDialog.vue`
- Create: `frontend/src/shared/ui/AppField.vue`
- Create: `frontend/src/shared/ui/AsyncState.vue`
- Create: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/global.css`
- Create: `frontend/src/modules/auth/AuthView.vue`
- Create: `frontend/src/modules/auth/auth.store.ts`
- Create: `frontend/src/modules/onboarding/OnboardingView.vue`
- Test: `frontend/src/shared/api/client.test.ts`
- Test: `frontend/src/modules/auth/AuthView.test.ts`
- Test: `frontend/src/modules/onboarding/OnboardingView.test.ts`

- [ ] **Step 1: Write failing UI and API client tests**

```ts
it('sends the readable csrf cookie on writes without exposing tokens', async () => {
  document.cookie = 'csrf_token=test-csrf'
  await api.post('/goals', { title: '四周复习' })
  expect(fetchMock).toHaveBeenCalledWith('/api/v1/goals', expect.objectContaining({
    credentials: 'include',
    headers: expect.objectContaining({ 'X-CSRF-Token': 'test-csrf' }),
  }))
})
```

Test 18+ validation, three independent consent checkboxes, keyboard focus, onboarding progress, and an API error summary linked to invalid fields.

- [ ] **Step 2: Verify RED**

Run: `cd frontend && pnpm test --run src/modules/auth src/modules/onboarding src/shared/api`  
Expected: FAIL because the app shell and modules are absent.

- [ ] **Step 3: Implement the approved responsive shell**

Use a desktop left sidebar and mobile five-item bottom navigation. Define fixed control heights, 8px-or-less radius, warm-white surfaces, deep green primary, neutral ink, amber warning, and red danger tokens. All icon buttons use lucide-vue-next and tooltips. The API client always uses `credentials: 'include'`, maps envelope errors, refreshes once on 401, and never stores access/refresh tokens.

Implement login, register, forgot/reset password, MFA, onboarding scene/preference/goal flow, route guards, loading, empty, error, offline, and permission states.

- [ ] **Step 4: Verify GREEN and accessibility**

Run: `cd frontend && pnpm test --run src/modules/auth src/modules/onboarding src/shared/api`  
Expected: PASS.

Run: `cd frontend && pnpm lint && pnpm build`  
Expected: PASS with no type errors.

- [ ] **Step 5: Commit frontend foundation**

```bash
git add frontend/src/app frontend/src/shared frontend/src/styles frontend/src/modules/auth frontend/src/modules/onboarding
git commit -m "feat: add accessible app shell and onboarding"
```

## Task 11: Today, Goals, Task Recording, and Insights UI

**Files:**
- Create: `frontend/src/modules/today/TodayView.vue`
- Create: `frontend/src/modules/today/TaskRow.vue`
- Create: `frontend/src/modules/today/TaskRecordDialog.vue`
- Create: `frontend/src/modules/today/today.store.ts`
- Create: `frontend/src/modules/goals/GoalsView.vue`
- Create: `frontend/src/modules/goals/GoalEditor.vue`
- Create: `frontend/src/modules/goals/WeeklyPlanView.vue`
- Create: `frontend/src/modules/insights/InsightsView.vue`
- Create: `frontend/src/modules/insights/WeeklyReviewView.vue`
- Test: `frontend/src/modules/today/TodayView.test.ts`
- Test: `frontend/src/modules/goals/GoalsView.test.ts`
- Test: `frontend/src/modules/insights/InsightsView.test.ts`

- [ ] **Step 1: Write failing workflow tests**

Test that Today shows record/defer/skip in one stable row, partial completion requires a ratio, defer requires a new time, skip never renders as done, a successful event exposes reverse, duplicate clicks reuse one idempotency key, and insights never render a ranking. Test empty and AI-unavailable states include manual actions.

- [ ] **Step 2: Verify RED**

Run: `cd frontend && pnpm test --run src/modules/today src/modules/goals src/modules/insights`  
Expected: FAIL because the workflow UI does not exist.

- [ ] **Step 3: Implement the core action surfaces**

Use list rows and full-width bands rather than nested cards. Keep progress bars and action controls at stable dimensions. Render ECharts only in the Insights route and provide a text/table alternative for screen readers. The store creates one UUID idempotency key per user intent and keeps it during network retry.

- [ ] **Step 4: Verify GREEN**

Run: `cd frontend && pnpm test --run src/modules/today src/modules/goals src/modules/insights`  
Expected: PASS.

Run: `cd frontend && pnpm build`  
Expected: PASS.

- [ ] **Step 5: Commit the core UI**

```bash
git add frontend/src/modules/today frontend/src/modules/goals frontend/src/modules/insights
git commit -m "feat: add goal task and insight workflows"
```

## Task 12: AI, Safety, Settings, Privacy, and Admin UI

**Files:**
- Create: `frontend/src/shared/api/sse.ts`
- Create: `frontend/src/modules/ai/AiView.vue`
- Create: `frontend/src/modules/ai/AiMessageList.vue`
- Create: `frontend/src/modules/ai/SuggestionReview.vue`
- Create: `frontend/src/modules/ai/CrisisSupportView.vue`
- Create: `frontend/src/modules/settings/SettingsView.vue`
- Create: `frontend/src/modules/settings/PrivacyPanel.vue`
- Create: `frontend/src/modules/settings/NotificationPanel.vue`
- Create: `frontend/src/modules/admin/AdminDashboard.vue`
- Create: `frontend/src/modules/admin/ContentVersionsView.vue`
- Create: `frontend/src/modules/admin/SafetyEventsView.vue`
- Test: `frontend/src/shared/api/sse.test.ts`
- Test: `frontend/src/modules/ai/AiView.test.ts`
- Test: `frontend/src/modules/settings/PrivacyPanel.test.ts`
- Test: `frontend/src/modules/admin/AdminDashboard.test.ts`

- [ ] **Step 1: Write failing AI and privacy UI tests**

Test SSE ordering and disconnect, plain-text rendering of model content, manual fallback on `AI_TEMPORARILY_UNAVAILABLE`, editable suggestion confirmation, L3 replacing the normal composer, export status/download expiry, deletion cooling-off cancellation, memory deletion, role-based admin navigation, and redacted safety excerpts.

- [ ] **Step 2: Verify RED**

Run: `cd frontend && pnpm test --run src/modules/ai src/modules/settings src/modules/admin src/shared/api/sse.test.ts`  
Expected: FAIL because these modules are absent.

- [ ] **Step 3: Implement safe streaming and controlled settings**

The SSE client parses only named events, appends deltas as text nodes, aborts on unmount, and never automatically resubmits after disconnect. `CrisisSupportView` contains the server-provided reviewed message and action links, with no experience, achievement, suggestion, or conversational dependency language. Settings show explicit retention periods and real deletion/export states. Admin routes require both role and permission claims.

- [ ] **Step 4: Verify GREEN and static security**

Run: `cd frontend && pnpm test --run src/modules/ai src/modules/settings src/modules/admin src/shared/api`  
Expected: PASS.

Run: `cd frontend && rg -n 'v-html|localStorage.*token|sessionStorage.*token' src`  
Expected: no matches.

- [ ] **Step 5: Commit AI, privacy, and admin UI**

```bash
git add frontend/src/shared/api frontend/src/modules/ai frontend/src/modules/settings frontend/src/modules/admin
git commit -m "feat: add AI privacy and administration interfaces"
```

## Task 13: Deployment, End-to-End Journeys, and Global Data-Flow Verification

**Files:**
- Create: `frontend/playwright.config.ts`
- Create: `e2e/fixtures/users.ts`
- Create: `e2e/global-flow.spec.ts`
- Create: `e2e/ownership.spec.ts`
- Create: `e2e/responsive.spec.ts`
- Create: `scripts/seed-local.sh`
- Create: `scripts/smoke-api.sh`
- Create: `scripts/verify-global-flow.sh`
- Modify: `deploy/nginx/default.conf`
- Modify: `README.md`

- [ ] **Step 1: Write the failing end-to-end tests**

`global-flow.spec.ts` must perform adult registration, three consents, onboarding, goal/plan creation, real or configured-mock Qwen suggestion generation, idempotent adoption, start/partial/complete/defer/skip/reverse task events, insight and review confirmation, SSE chat, export download validation, deletion request/cancel, admin MFA publication, and audit verification.

`ownership.spec.ts` must create two users and assert cross-user goal, schedule, AI session, export, and attachment access returns 404/403. `responsive.spec.ts` must run at 390x844 and 1440x900, assert no horizontal overflow, visible focus, no overlapping navigation, and nonblank charts.

- [ ] **Step 2: Verify RED**

Run: `pnpm --dir frontend exec playwright test -c ../frontend/playwright.config.ts`  
Expected: FAIL because the running stack and E2E fixtures are not complete.

- [ ] **Step 3: Complete deployment and deterministic verification scripts**

Configure Nginx for SPA fallback, `/api/v1`, SSE buffering off, request IDs, TLS-ready security headers, upload limits, and no caching of authenticated API responses. `verify-global-flow.sh` must start the Compose stack, wait for health checks, run backend verify, frontend lint/test/build, Playwright, a real Qwen benign suggestion smoke when `AI_PROVIDER=qwen`, database invariant queries, Redis key inspection, MinIO export existence, and a sensitive-log scan.

The script must exit nonzero on any failed invariant and print a final table with these checks: auth cookies, CSRF, consent rows, goal/plan/task rows, idempotency counts, event states, experience totals, review confirmation, Qwen metadata, safety response, export object, deletion lifecycle, ownership isolation, admin audit, and sensitive log scan.

- [ ] **Step 4: Run the complete verification gate**

Run: `cd backend && ./mvnw verify`  
Expected: PASS.

Run: `cd frontend && pnpm lint && pnpm test --run && pnpm build`  
Expected: PASS.

Run: `pnpm --dir frontend exec playwright test -c ../frontend/playwright.config.ts`  
Expected: PASS on desktop and mobile projects.

Run: `./scripts/verify-global-flow.sh`  
Expected: exit 0 and every invariant reports `PASS`.

- [ ] **Step 5: Commit the verified delivery**

```bash
git add deploy e2e scripts README.md frontend/playwright.config.ts
git commit -m "test: verify complete growth platform data flow"
```

## Task 14: Final Security and Visual Release Audit

**Files:**
- Create: `docs/release/verification-report.md`
- Create: `docs/release/security-checklist.md`
- Create: `docs/release/known-limitations.md`

- [ ] **Step 1: Run secret and sensitive-data scans**

Run: `git grep -nE 'sk-[A-Za-z0-9_-]{20,}|123456|QWEN_API_KEY=.+|MYSQL_PASSWORD=.+' -- ':!docs/superpowers/plans/*'`  
Expected: no real secret or supplied password appears in tracked files.

Run: `rg -n 'password|token|cookie|note|content' logs/`  
Expected: no password, raw token, Cookie header, complete AI message, or task note value.

- [ ] **Step 2: Inspect Playwright screenshots and canvas pixels**

Review every desktop/mobile screenshot for clipping, overlap, blank content, broken icons, unstable controls, missing focus, and text overflow. Check ECharts canvases contain non-background pixels and have accessible text alternatives.

- [ ] **Step 3: Record exact evidence**

Write `verification-report.md` with command, timestamp, exit status, test counts, global-flow invariant table, real Qwen model/request metadata without secret values, and URLs. Write only actual residual limitations in `known-limitations.md`; when none remain, state `No known release-blocking limitations.`

- [ ] **Step 4: Re-run the release gate after documentation**

Run: `./scripts/verify-global-flow.sh`  
Expected: exit 0 on the exact final worktree.

Run: `git status --short`  
Expected: only the three release documents are uncommitted.

- [ ] **Step 5: Commit release evidence**

```bash
git add docs/release
git commit -m "docs: record release verification evidence"
```
