> 历史归档（截至 2026-09-07）。文中的计划、完成状态、分工和约定仅代表当时记录；当前范围以[新版 MVP 计划](../../../../01-requirements.md)为准。

# 成长平台实施计划

> **给智能体执行者：** 必备子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实施本计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 构建并验证文档中「成为更好的自己」成长平台的完整 MVP，包括用户旅程、Qwen 安全管线、隐私生命周期、后台管理，以及全数据流自检。

**架构：** 使用 Vue 3 移动优先 SPA 对接 Spring Boot 3 模块化单体。MySQL 为权威数据源，Redis 仅作可丢弃的加速层，MinIO 提供 S3 兼容文件存储，Qwen 隔离在供应商适配层与两阶段安全检查之后。按垂直切片推进，确保每个业务流在进入下一个切片前都完成 UI、API、持久化与测试。

**技术栈：** Java 21、Spring Boot 3、Maven、MySQL 8、Redis 7、Flyway、MinIO、Vue 3、TypeScript、Vite、Pinia、Vue Router、ECharts、Vitest、Testing Library、Playwright、Docker Compose、Nginx。

---

## 文件结构

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
│       │   ├── shared/       # API 信封、请求 ID、时间、ULID、存储、outbox
│       │   ├── auth/         # Cookie 会话、CSRF、密码重置、TOTP MFA
│       │   ├── identity/     # 个人资料、同意、偏好、通知
│       │   ├── goal/         # 维度、目标、周计划、任务与日程
│       │   ├── execution/    # 任务事件状态机、幂等与经验
│       │   ├── insight/      # 指标、趋势、恢复与周复盘
│       │   ├── ai/           # Qwen 供应商、会话、消息、建议与 SSE
│       │   ├── safety/       # 风险分级、场景规则与危机响应
│       │   ├── privacy/      # 导出、留存、删除与附件
│       │   └── admin/        # 版本化内容、审计与看板
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
│       ├── app/              # 启动引导、路由、布局与守卫
│       ├── modules/          # auth、onboarding、today、goals、insights、ai、settings、admin
│       └── shared/           # API、SSE、UI、校验与遥测
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

## 任务 1：仓库、运行时与基础设施基座

**文件：**
- 新建：`.gitignore`
- 新建：`.env.example`
- 新建：`README.md`
- 新建：`deploy/compose.yaml`
- 新建：`deploy/nginx/default.conf`
- 新建：`backend/pom.xml`
- 新建：`backend/src/main/java/com/betterself/growth/GrowthApplication.java`
- 新建：`backend/src/main/resources/application.yml`
- 新建：`backend/src/main/resources/application-local.yml`
- 新建：`backend/src/test/java/com/betterself/growth/GrowthApplicationTest.java`
- 新建：`frontend/package.json`
- 新建：`frontend/tsconfig.json`
- 新建：`frontend/vite.config.ts`
- 新建：`frontend/index.html`
- 新建：`frontend/src/main.ts`
- 新建：`frontend/src/App.vue`
- 测试：`frontend/src/App.test.ts`

- [ ] **步骤 1：编写失败的后端与前端启动测试**

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

- [ ] **步骤 2：运行测试并确认缺失的项目会失败**

运行：`cd backend && mvn test -Dtest=GrowthApplicationTest`  
预期：失败，因为 `pom.xml` 与 `GrowthApplication` 尚不存在。

运行：`cd frontend && pnpm test --run src/App.test.ts`  
预期：失败，因为前端包与应用尚不存在。

- [ ] **步骤 3：创建最小可启动项目与本地基础设施**

使用 Spring Boot `3.5.x`、Java 21，依赖 Web、Security、Validation、Data JPA、Redis、Actuator、Flyway MySQL、Jackson、JWT、Argon2、TOTP、AWS S3、springdoc、Testcontainers 与 REST Assured。使用 Vue `3.5.x`、TypeScript、Vite、Pinia、Vue Router、ECharts、Zod、lucide-vue-next、Vitest、Testing Library、axe-core 与 Playwright。

应用入口必须完整且最小：

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

`deploy/compose.yaml` 必须定义健康的 `mysql:8.4`、`redis:7.4-alpine` 与 `minio/minio` 服务，并使用项目级命名卷。`.env.example` 只允许包含变量名与无害示例值。`.gitignore` 必须包含 `.env.local`、`.superpowers/`、`frontend/node_modules/`、`frontend/dist/`、`backend/target/`、Playwright 产物与 IDE 文件。

- [ ] **步骤 4：验证基座**

运行：`cd backend && ./mvnw test -Dtest=GrowthApplicationTest`  
预期：通过。

运行：`cd frontend && pnpm install && pnpm test --run`  
预期：通过。

运行：`docker compose --env-file .env.local -f deploy/compose.yaml config`  
预期：Compose 配置有效，且没有未解析的必填变量。

- [ ] **步骤 5：提交基座**

```bash
git add .gitignore .env.example README.md deploy backend frontend
git commit -m "chore: scaffold growth platform"
```

## 任务 2：共享 API 契约与版本化数据库

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/shared/api/ApiEnvelope.java`
- 新建：`backend/src/main/java/com/betterself/growth/shared/api/ApiError.java`
- 新建：`backend/src/main/java/com/betterself/growth/shared/api/GlobalExceptionHandler.java`
- 新建：`backend/src/main/java/com/betterself/growth/shared/web/RequestIdFilter.java`
- 新建：`backend/src/main/java/com/betterself/growth/shared/time/AppClockConfig.java`
- 新建：`backend/src/main/java/com/betterself/growth/shared/id/PublicIdGenerator.java`
- 新建：`backend/src/main/resources/db/migration/V1__baseline.sql`
- 新建：`backend/src/main/resources/db/migration/V2__support_tables.sql`
- 新建：`backend/src/main/resources/db/migration/V3__seed_system_dimensions_and_templates.sql`
- 新建：`backend/src/main/resources/db/migration/V4__seed_prompt_and_safety_versions.sql`
- 测试：`backend/src/test/java/com/betterself/growth/shared/DatabaseMigrationIT.java`
- 测试：`backend/src/test/java/com/betterself/growth/shared/ApiEnvelopeTest.java`

- [ ] **步骤 1：编写迁移与信封测试**

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

- [ ] **步骤 2：验证 RED（测试先失败）**

运行：`cd backend && ./mvnw test -Dtest=DatabaseMigrationIT,ApiEnvelopeTest`  
预期：失败，因为迁移与共享响应类型尚不存在。

- [ ] **步骤 3：实现数据库 schema 与 API 原语**

`V1` 必须创建文档定义的身份、目标、任务、AI、隐私与审计表。`V2` 必须新增 `password_reset_token`、`suggestion_set`、`suggestion_item`、`knowledge_source`、`attachment`、`outbox_event`、`product_event` 与 `notification_delivery`，包含外键、唯一键、状态 CHECK 约束与 UTC `DATETIME(3)` 时间戳。`V3` 种子化五个系统维度与每个场景至少五条已评审的低风险模板。`V4` 种子化每个场景一个已发布提示词版本，以及版本化的 L3 响应策略。

所有接口统一使用一种响应结构：

```java
public record ApiEnvelope<T>(T data, String requestId, Instant timestamp) {
    public static <T> ApiEnvelope<T> of(T data, String requestId, Clock clock) {
        return new ApiEnvelope<>(data, requestId, clock.instant());
    }
}
```

`RequestIdFilter` 接受合法的传入 `X-Request-ID` 或自行生成 ULID，将其写入 MDC 与响应头，并在 `finally` 中始终清理 MDC。

- [ ] **步骤 4：验证 GREEN（测试通过）与迁移可重复性**

运行：`cd backend && ./mvnw test -Dtest=DatabaseMigrationIT,ApiEnvelopeTest`  
预期：通过，且 Flyway 报告 schema 版本为 4。

运行：`cd backend && ./mvnw flyway:info -Dflyway.url=jdbc:mysql://127.0.0.1:3306/growth -Dflyway.user=growth_app -Dflyway.password="$MYSQL_PASSWORD"`  
预期：四个迁移全部显示 `Success` 或 `Pending`，绝无 `Failed`。

- [ ] **步骤 5：提交共享基础设施**

```bash
git add backend/src/main/java/com/betterself/growth/shared backend/src/main/resources/db backend/src/test/java/com/betterself/growth/shared
git commit -m "feat: add shared API and database baseline"
```

## 任务 3：认证、同意与偏好切片

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/auth/AuthController.java`
- 新建：`backend/src/main/java/com/betterself/growth/auth/AuthService.java`
- 新建：`backend/src/main/java/com/betterself/growth/auth/SessionService.java`
- 新建：`backend/src/main/java/com/betterself/growth/auth/CookieFactory.java`
- 新建：`backend/src/main/java/com/betterself/growth/auth/CsrfDoubleSubmitFilter.java`
- 新建：`backend/src/main/java/com/betterself/growth/auth/SecurityConfig.java`
- 新建：`backend/src/main/java/com/betterself/growth/auth/MfaService.java`
- 新建：`backend/src/main/java/com/betterself/growth/auth/AgePolicy.java`
- 新建：`backend/src/main/java/com/betterself/growth/identity/IdentityController.java`
- 新建：`backend/src/main/java/com/betterself/growth/identity/IdentityService.java`
- 测试：`backend/src/test/java/com/betterself/growth/auth/AgePolicyTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/auth/AuthFlowIT.java`
- 测试：`backend/src/test/java/com/betterself/growth/auth/RefreshReplayIT.java`

- [ ] **步骤 1：编写失败的年龄、Cookie、CSRF 与重放测试**

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

`AuthFlowIT` 必须注册一个带三个当前版本同意的成年人，断言 `access_token`、`refresh_token` 与 `csrf_token` Cookie，断言 JSON 中不出现令牌字段，拒绝不带 `X-CSRF-Token` 的写请求，并接受带 Cookie 值的写请求。`RefreshReplayIT` 必须对旧刷新令牌使用两次，并断言整个令牌族被吊销。

- [ ] **步骤 2：验证 RED**

运行：`cd backend && ./mvnw test -Dtest=AgePolicyTest,AuthFlowIT,RefreshReplayIT`  
预期：失败，因为认证与身份切片缺失。

- [ ] **步骤 3：实现认证与身份契约**

密码使用 Argon2id，签发 30 分钟有效期的有符号访问 JWT，7 天有效的不透明刷新令牌；刷新/重置令牌的哈希仅使用 SHA-256。在认证之后注册 `CsrfDoubleSubmitFilter`，并要求对 POST、PUT、PATCH 与 DELETE 进行 header/cookie 常量时间匹配。

对外服务契约必须为：

```java
public interface SessionService {
    IssuedSession issue(long userId, String deviceLabel, HttpServletRequest request);
    IssuedSession rotate(String rawRefreshToken, HttpServletRequest request);
    void revokeCurrent(long userId, String rawRefreshToken);
    void revokeAll(long userId);
}
```

注册必须在同一事务内写入 `sys_user`、三行 `consent_record`、`user_preference` 与五行 `user_dimension`。实现 `/auth/register`、`/auth/login`、`/auth/refresh`、`/auth/logout`、`/auth/logout-all`、忘记/重置/修改密码、`/me`、`/me/preferences`、`/me/consents` 与 AI 记忆偏好接口。管理员登录在提交有效 TOTP 之前保持 `MFA_PENDING` 状态。

- [ ] **步骤 4：验证 GREEN**

运行：`cd backend && ./mvnw test -Dtest=AgePolicyTest,AuthFlowIT,RefreshReplayIT`  
预期：通过，且 JSON 或日志中不出现原始令牌。

运行：`cd backend && ./mvnw verify -Dgroups=security`  
预期：Cookie 标志、CSRF、归属主体与刷新重放测试全部通过。

- [ ] **步骤 5：提交认证与身份**

```bash
git add backend/src/main/java/com/betterself/growth/auth backend/src/main/java/com/betterself/growth/identity backend/src/test/java/com/betterself/growth/auth
git commit -m "feat: add secure authentication and onboarding preferences"
```

## 任务 4：维度、目标、周计划与任务定义

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/goal/DimensionController.java`
- 新建：`backend/src/main/java/com/betterself/growth/goal/GoalController.java`
- 新建：`backend/src/main/java/com/betterself/growth/goal/WeeklyPlanController.java`
- 新建：`backend/src/main/java/com/betterself/growth/goal/TaskController.java`
- 新建：`backend/src/main/java/com/betterself/growth/goal/GoalService.java`
- 新建：`backend/src/main/java/com/betterself/growth/goal/PlanningService.java`
- 新建：`backend/src/main/java/com/betterself/growth/goal/RecurrenceExpander.java`
- 测试：`backend/src/test/java/com/betterself/growth/goal/GoalPolicyTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/goal/RecurrenceExpanderTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/goal/GoalPlanningIT.java`

- [ ] **步骤 1：编写失败的目标与时区测试**

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

`GoalPlanningIT` 必须拒绝第四个活跃目标，拒绝低于 14 天或高于 84 天的时长，对已被使用的维度执行归档而非删除，并生成唯一的 `(task_id, planned_start_at)` 日程。

- [ ] **步骤 2：验证 RED**

运行：`cd backend && ./mvnw test -Dtest=GoalPolicyTest,RecurrenceExpanderTest,GoalPlanningIT`  
预期：失败，因为目标规划缺失。

- [ ] **步骤 3：实现目标规划**

实现文档定义的维度、目标、周计划与任务接口。仅解析支持的 RFC 5545 子集：`FREQ=DAILY|WEEKLY`、`INTERVAL`、`BYDAY` 与 `COUNT|UNTIL`；其余属性一律以 `INVALID_RRULE` 拒绝。

暴露以下规划边界：

```java
public interface PlanningService {
    WeeklyPlanView createWeeklyPlan(long userId, CreateWeeklyPlanCommand command);
    TaskView createTask(long userId, CreateTaskCommand command);
    List<TaskScheduleView> materializeWeek(long userId, String weeklyPlanPublicId);
}
```

每个生成的日程都必须存储 UTC `planned_start_at`、本地日期与 IANA 时区。绝不使用服务器默认时区。

- [ ] **步骤 4：验证 GREEN**

运行：`cd backend && ./mvnw test -Dtest=GoalPolicyTest,RecurrenceExpanderTest,GoalPlanningIT`  
预期：通过。

- [ ] **步骤 5：提交规划**

```bash
git add backend/src/main/java/com/betterself/growth/goal backend/src/test/java/com/betterself/growth/goal
git commit -m "feat: add goals weekly plans and recurring tasks"
```

## 任务 5：任务事件状态机、幂等与经验

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/execution/TaskEventController.java`
- 新建：`backend/src/main/java/com/betterself/growth/execution/TaskExecutionService.java`
- 新建：`backend/src/main/java/com/betterself/growth/execution/TaskStateMachine.java`
- 新建：`backend/src/main/java/com/betterself/growth/execution/ExperienceCalculator.java`
- 新建：`backend/src/main/java/com/betterself/growth/execution/IdempotencyService.java`
- 新建：`backend/src/main/java/com/betterself/growth/execution/ScheduleExpiryJob.java`
- 测试：`backend/src/test/java/com/betterself/growth/execution/TaskStateMachineTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/execution/ExperienceCalculatorTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/execution/TaskExecutionIT.java`
- 测试：`backend/src/test/java/com/betterself/growth/execution/TaskExecutionConcurrencyIT.java`

- [ ] **步骤 1：编写失败的状态与幂等测试**

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

`TaskExecutionIT` 必须对同一幂等键提交两次并断言只有一条事件与一次经验更新；用不同请求体复用该键并预期 409；执行延期并断言生成关联的新日程；执行跳过并断言 `SKIPPED`；将未触碰的日程过期并断言零经验；撤销最新终止事件并断言指标被重算。

- [ ] **步骤 2：验证 RED**

运行：`cd backend && ./mvnw test -Dtest=TaskStateMachineTest,ExperienceCalculatorTest,TaskExecutionIT`  
预期：失败，因为执行行为缺失。

- [ ] **步骤 3：实现事务化事件流**

实现一个 `@Transactional` 方法：以悲观锁加载用户所属日程，校验状态，检查 MySQL 中持久的幂等行，写入不可变事件与快照，更新状态/维度/周聚合，在需要时创建延期日程，写入 Outbox/产品事件，存储响应，提交，然后使 Redis 概览键失效。

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

过期任务只把未被触碰的已过时日程改为 `EXPIRED`。撤销写入 `REVERSED` 事件，绝不删除历史。

- [ ] **步骤 4：验证 GREEN 与并发安全**

运行：`cd backend && ./mvnw test -Dtest=TaskStateMachineTest,ExperienceCalculatorTest,TaskExecutionIT`  
预期：通过。

运行：`cd backend && ./mvnw test -Dtest=TaskExecutionConcurrencyIT`  
预期：十个并发重复请求只产生一条终止事件。

- [ ] **步骤 5：提交执行**

```bash
git add backend/src/main/java/com/betterself/growth/execution backend/src/test/java/com/betterself/growth/execution
git commit -m "feat: add idempotent task execution state machine"
```

## 任务 6：洞察与周复盘

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/insight/InsightController.java`
- 新建：`backend/src/main/java/com/betterself/growth/insight/InsightService.java`
- 新建：`backend/src/main/java/com/betterself/growth/insight/WeeklyReviewController.java`
- 新建：`backend/src/main/java/com/betterself/growth/insight/WeeklyReviewService.java`
- 新建：`backend/src/main/java/com/betterself/growth/insight/WeeklyMetricsCalculator.java`
- 新建：`backend/src/main/java/com/betterself/growth/insight/MetricRebuildJob.java`
- 测试：`backend/src/test/java/com/betterself/growth/insight/InsightServiceTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/insight/WeeklyReviewIT.java`

- [ ] **步骤 1：编写失败的指标测试**

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

同时测试：仅连续七个无行动日后才计入恢复；个人最佳只来自当前用户；周复盘确认前不改变下周设置。

- [ ] **步骤 2：验证 RED**

运行：`cd backend && ./mvnw test -Dtest=InsightServiceTest,WeeklyReviewIT`  
预期：失败，因为洞察与复盘缺失。

- [ ] **步骤 3：先实现确定性指标**

实现 `/insights/overview`、`/insights/trends`、`/insights/calendar`、`/reviews/weekly/{planId}` 与确认接口。复盘事实必须在任何 AI 调用之前完全由事件数据构建。概览缓存五分钟，任务写入后失效，聚合漂移时从任务事件重建。

- [ ] **步骤 4：验证 GREEN**

运行：`cd backend && ./mvnw test -Dtest=InsightServiceTest,WeeklyReviewIT`  
预期：通过，且查询或响应中不含其他用户的排名。

- [ ] **步骤 5：提交洞察**

```bash
git add backend/src/main/java/com/betterself/growth/insight backend/src/test/java/com/betterself/growth/insight
git commit -m "feat: add personal insights and weekly reviews"
```

## 任务 7：Qwen 供应商、结构化建议、SSE 与安全

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/ai/QwenProvider.java`
- 新建：`backend/src/main/java/com/betterself/growth/ai/QwenHttpProvider.java`
- 新建：`backend/src/main/java/com/betterself/growth/ai/MockQwenProvider.java`
- 新建：`backend/src/main/java/com/betterself/growth/ai/AiController.java`
- 新建：`backend/src/main/java/com/betterself/growth/ai/AiService.java`
- 新建：`backend/src/main/java/com/betterself/growth/ai/SuggestionService.java`
- 新建：`backend/src/main/java/com/betterself/growth/safety/RiskClassifier.java`
- 新建：`backend/src/main/java/com/betterself/growth/safety/SafetyService.java`
- 新建：`backend/src/main/java/com/betterself/growth/safety/CrisisResponseService.java`
- 测试：`backend/src/test/java/com/betterself/growth/safety/SafetyServiceTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/ai/QwenContractTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/ai/AiFlowIT.java`

- [ ] **步骤 1：编写失败的安全与供应商契约测试**

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

`QwenContractTest` 必须使用本地桩服务器验证 OpenAI 兼容请求头、可配置模型、超时、令牌元数据、一次 JSON 修复尝试，以及第二次非法结果后被拒绝。`AiFlowIT` 必须断言 SSE 事件顺序 `meta -> delta* -> done`、归属、建议过期与幂等采纳。

- [ ] **步骤 2：验证 RED**

运行：`cd backend && ./mvnw test -Dtest=SafetyServiceTest,QwenContractTest,AiFlowIT`  
预期：失败，因为 AI 与安全模块缺失。

- [ ] **步骤 3：实现供应商隔离与两阶段安全**

```java
public interface QwenProvider {
    StructuredResult generateStructured(StructuredPrompt prompt);
    void stream(ChatPrompt prompt, Consumer<String> deltaConsumer);
    Classification classify(ClassificationPrompt prompt);
}
```

输入安全检查在上下文组装之前运行。L2 返回专业边界响应；L3 仅返回版本化的固定危机响应并记录脱敏安全事件。对 L0/L1，只注入已确认的偏好、聚合事实、已确认的记忆与已评审的知识。结构化建议须通过 1-5 条、5-60 分钟、难度 1-3、维度权重合计 1-30 与免打扰时段校验。任何内容对外暴露前先运行输出安全检查。

使用 `SseEmitter`，只允许 `meta`、`delta`、`safety`、`done` 与 `error` 事件。传播断连取消。发出任何 delta 后不再重试。

- [ ] **步骤 4：验证 GREEN 并评估固定安全语料**

运行：`cd backend && ./mvnw test -Dtest=SafetyServiceTest,QwenContractTest,AiFlowIT`  
预期：通过。

运行：`cd backend && ./mvnw test -Pqwen-eval -Dai.provider=mock`  
预期：L3 召回率 100%，L2 中不出现诊断/处方，结构化 JSON 合法率至少 99%。

- [ ] **步骤 5：提交 AI 与安全**

```bash
git add backend/src/main/java/com/betterself/growth/ai backend/src/main/java/com/betterself/growth/safety backend/src/test/java/com/betterself/growth/ai backend/src/test/java/com/betterself/growth/safety
git commit -m "feat: add safe Qwen assistant and suggestions"
```

## 任务 8：隐私、导出、删除、附件与通知

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/privacy/PrivacyController.java`
- 新建：`backend/src/main/java/com/betterself/growth/privacy/ExportService.java`
- 新建：`backend/src/main/java/com/betterself/growth/privacy/DeletionService.java`
- 新建：`backend/src/main/java/com/betterself/growth/privacy/RetentionJob.java`
- 新建：`backend/src/main/java/com/betterself/growth/privacy/AttachmentController.java`
- 新建：`backend/src/main/java/com/betterself/growth/shared/storage/ObjectStorage.java`
- 新建：`backend/src/main/java/com/betterself/growth/identity/NotificationController.java`
- 新建：`backend/src/main/java/com/betterself/growth/identity/NotificationService.java`
- 测试：`backend/src/test/java/com/betterself/growth/privacy/PrivacyFlowIT.java`
- 测试：`backend/src/test/java/com/betterself/growth/privacy/RetentionPolicyTest.java`
- 测试：`backend/src/test/java/com/betterself/growth/privacy/AttachmentPolicyTest.java`

- [ ] **步骤 1：编写失败的隐私生命周期测试**

`PrivacyFlowIT` 必须每 24 小时创建一个导出，验证 JSON 与 CSV 成员，拒绝第二个用户的下载，24 小时后使对象过期，创建带恰好七天冷静期的删除请求，吊销会话，允许取消，并使用注入的时钟处理删除。`AttachmentPolicyTest` 必须拒绝不支持的 MIME/扩展名组合，并阻止未扫描对象关联。

- [ ] **步骤 2：验证 RED**

运行：`cd backend && ./mvnw test -Dtest=PrivacyFlowIT,RetentionPolicyTest,AttachmentPolicyTest`  
预期：失败，因为隐私/文件行为缺失。

- [ ] **步骤 3：实现显式生命周期状态**

实现 `/privacy/exports`、状态、下载、删除创建/当前/取消、对话删除、AI 记忆删除与附件预签名。导出以机器可读格式打包为 ZIP，包含 `manifest.json` 与按类别的 CSV 文件，服务端加密存储，返回 15 分钟有效期的预签名 URL，24 小时后删除。

删除使用 `COOLING_OFF -> PROCESSING -> COMPLETED|FAILED` 状态机，`CANCELLED` 仅在处理开始前可用。冷静期内暂停通知与新的 AI 调用。通知偏好支持 `IN_APP`、`EMAIL` 与 `WEB_PUSH`；只派发已配置的渠道，并强制免打扰时段与 `max_per_day` 上限。

- [ ] **步骤 4：验证 GREEN**

运行：`cd backend && ./mvnw test -Dtest=PrivacyFlowIT,RetentionPolicyTest,AttachmentPolicyTest`  
预期：通过。

- [ ] **步骤 5：提交隐私与通知行为**

```bash
git add backend/src/main/java/com/betterself/growth/privacy backend/src/main/java/com/betterself/growth/shared/storage backend/src/main/java/com/betterself/growth/identity backend/src/test/java/com/betterself/growth/privacy
git commit -m "feat: add privacy lifecycle files and notifications"
```

## 任务 9：后台治理、审计与产品指标

**文件：**
- 新建：`backend/src/main/java/com/betterself/growth/admin/AdminController.java`
- 新建：`backend/src/main/java/com/betterself/growth/admin/ContentGovernanceService.java`
- 新建：`backend/src/main/java/com/betterself/growth/admin/SafetyReviewService.java`
- 新建：`backend/src/main/java/com/betterself/growth/admin/AuditService.java`
- 新建：`backend/src/main/java/com/betterself/growth/admin/MetricsController.java`
- 新建：`backend/src/main/java/com/betterself/growth/shared/outbox/OutboxPublisher.java`
- 测试：`backend/src/test/java/com/betterself/growth/admin/AdminGovernanceIT.java`
- 测试：`backend/src/test/java/com/betterself/growth/admin/TelemetryPrivacyTest.java`

- [ ] **步骤 1：编写失败的角色与审计测试**

测试内容运营者无法读取安全详情，安全运营者只能看到脱敏摘录，发布需要 MFA 加评审人，发布创建新的不可变版本而非覆盖，回滚改变活动指针，以及每个敏感操作都产生审计行。测试产品事件拒绝邮箱、任务备注、AI 文本与任意自由文本属性。

- [ ] **步骤 2：验证 RED**

运行：`cd backend && ./mvnw test -Dtest=AdminGovernanceIT,TelemetryPrivacyTest`  
预期：失败，因为后台治理缺失。

- [ ] **步骤 3：实现最小权限后台**

实现文档定义的模板、提示词、知识源、安全事件、审计与看板接口。使用显式权限，如 `TEMPLATE_EDIT`、`CONTENT_PUBLISH`、`SAFETY_REVIEW`、`SAFETY_SENSITIVE_READ` 与 `AUDIT_READ`。Outbox 投递以属性白名单记录产品事件，并在不阻塞原始事务的情况下重试。

- [ ] **步骤 4：验证 GREEN**

运行：`cd backend && ./mvnw test -Dtest=AdminGovernanceIT,TelemetryPrivacyTest`  
预期：通过，且 `product_event` 中不出现任何敏感属性。

- [ ] **步骤 5：提交治理**

```bash
git add backend/src/main/java/com/betterself/growth/admin backend/src/main/java/com/betterself/growth/shared/outbox backend/src/test/java/com/betterself/growth/admin
git commit -m "feat: add governed administration and telemetry"
```

## 任务 10：前端基座、设计系统、认证与引导

**文件：**
- 新建：`frontend/src/app/router.ts`
- 新建：`frontend/src/app/UserLayout.vue`
- 新建：`frontend/src/app/AdminLayout.vue`
- 新建：`frontend/src/shared/api/client.ts`
- 新建：`frontend/src/shared/api/errors.ts`
- 新建：`frontend/src/shared/ui/AppButton.vue`
- 新建：`frontend/src/shared/ui/AppDialog.vue`
- 新建：`frontend/src/shared/ui/AppField.vue`
- 新建：`frontend/src/shared/ui/AsyncState.vue`
- 新建：`frontend/src/styles/tokens.css`
- 新建：`frontend/src/styles/global.css`
- 新建：`frontend/src/modules/auth/AuthView.vue`
- 新建：`frontend/src/modules/auth/auth.store.ts`
- 新建：`frontend/src/modules/onboarding/OnboardingView.vue`
- 测试：`frontend/src/shared/api/client.test.ts`
- 测试：`frontend/src/modules/auth/AuthView.test.ts`
- 测试：`frontend/src/modules/onboarding/OnboardingView.test.ts`

- [ ] **步骤 1：编写失败的 UI 与 API 客户端测试**

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

测试 18 岁以上校验、三个独立同意复选框、键盘焦点、引导进度，以及与无效字段关联的 API 错误摘要。

- [ ] **步骤 2：验证 RED**

运行：`cd frontend && pnpm test --run src/modules/auth src/modules/onboarding src/shared/api`  
预期：失败，因为应用外壳与模块缺失。

- [ ] **步骤 3：实现已评审的响应式外壳**

使用桌面左侧栏与移动端五项底栏导航。定义固定控件高度、8px 及以内圆角、暖白表面、深绿主色、中性墨色、琥珀警告与红色危险令牌。所有图标按钮使用 lucide-vue-next 并提供提示。API 客户端始终使用 `credentials: 'include'`，映射信封错误，401 时刷新一次，且绝不存储访问/刷新令牌。

实现登录、注册、忘记/重置密码、MFA、引导场景/偏好/目标流程、路由守卫、加载、空、错误、离线与权限状态。

- [ ] **步骤 4：验证 GREEN 与可访问性**

运行：`cd frontend && pnpm test --run src/modules/auth src/modules/onboarding src/shared/api`  
预期：通过。

运行：`cd frontend && pnpm lint && pnpm build`  
预期：通过，且无类型错误。

- [ ] **步骤 5：提交前端基座**

```bash
git add frontend/src/app frontend/src/shared frontend/src/styles frontend/src/modules/auth frontend/src/modules/onboarding
git commit -m "feat: add accessible app shell and onboarding"
```

## 任务 11：今日、目标、任务记录与洞察 UI

**文件：**
- 新建：`frontend/src/modules/today/TodayView.vue`
- 新建：`frontend/src/modules/today/TaskRow.vue`
- 新建：`frontend/src/modules/today/TaskRecordDialog.vue`
- 新建：`frontend/src/modules/today/today.store.ts`
- 新建：`frontend/src/modules/goals/GoalsView.vue`
- 新建：`frontend/src/modules/goals/GoalEditor.vue`
- 新建：`frontend/src/modules/goals/WeeklyPlanView.vue`
- 新建：`frontend/src/modules/insights/InsightsView.vue`
- 新建：`frontend/src/modules/insights/WeeklyReviewView.vue`
- 测试：`frontend/src/modules/today/TodayView.test.ts`
- 测试：`frontend/src/modules/goals/GoalsView.test.ts`
- 测试：`frontend/src/modules/insights/InsightsView.test.ts`

- [ ] **步骤 1：编写失败的工作流测试**

测试今日页在稳定的单行内展示记录/延期/跳过，部分完成必须填写比例，延期必须填写新时间，跳过绝不渲染为已完成，成功事件暴露撤销入口，重复点击复用同一幂等键，洞察绝不渲染排名。测试空状态与 AI 不可用状态包含手动操作入口。

- [ ] **步骤 2：验证 RED**

运行：`cd frontend && pnpm test --run src/modules/today src/modules/goals src/modules/insights`  
预期：失败，因为工作流 UI 不存在。

- [ ] **步骤 3：实现核心操作界面**

使用列表行与通栏 band 而非嵌套卡片。进度条与操作控件保持稳定尺寸。仅在洞察路由渲染 ECharts，并为屏幕阅读器提供文本/表格替代方案。store 为每个用户意图创建一个 UUID 幂等键，并在网络重试期间保持不变。

- [ ] **步骤 4：验证 GREEN**

运行：`cd frontend && pnpm test --run src/modules/today src/modules/goals src/modules/insights`  
预期：通过。

运行：`cd frontend && pnpm build`  
预期：通过。

- [ ] **步骤 5：提交核心 UI**

```bash
git add frontend/src/modules/today frontend/src/modules/goals frontend/src/modules/insights
git commit -m "feat: add goal task and insight workflows"
```

## 任务 12：AI、安全、设置、隐私与后台 UI

**文件：**
- 新建：`frontend/src/shared/api/sse.ts`
- 新建：`frontend/src/modules/ai/AiView.vue`
- 新建：`frontend/src/modules/ai/AiMessageList.vue`
- 新建：`frontend/src/modules/ai/SuggestionReview.vue`
- 新建：`frontend/src/modules/ai/CrisisSupportView.vue`
- 新建：`frontend/src/modules/settings/SettingsView.vue`
- 新建：`frontend/src/modules/settings/PrivacyPanel.vue`
- 新建：`frontend/src/modules/settings/NotificationPanel.vue`
- 新建：`frontend/src/modules/admin/AdminDashboard.vue`
- 新建：`frontend/src/modules/admin/ContentVersionsView.vue`
- 新建：`frontend/src/modules/admin/SafetyEventsView.vue`
- 测试：`frontend/src/shared/api/sse.test.ts`
- 测试：`frontend/src/modules/ai/AiView.test.ts`
- 测试：`frontend/src/modules/settings/PrivacyPanel.test.ts`
- 测试：`frontend/src/modules/admin/AdminDashboard.test.ts`

- [ ] **步骤 1：编写失败的 AI 与隐私 UI 测试**

测试 SSE 顺序与断连、模型内容的纯文本渲染、`AI_TEMPORARILY_UNAVAILABLE` 时的手动降级、可编辑建议确认、L3 替换常规输入框、导出状态/下载过期、删除冷静期取消、记忆删除、基于角色的后台导航与脱敏安全摘录。

- [ ] **步骤 2：验证 RED**

运行：`cd frontend && pnpm test --run src/modules/ai src/modules/settings src/modules/admin src/shared/api/sse.test.ts`  
预期：失败，因为这些模块缺失。

- [ ] **步骤 3：实现安全流式传输与受控设置**

SSE 客户端只解析命名事件，以文本节点追加 delta，卸载时中止，且断连后绝不自动重发。`CrisisSupportView` 只包含服务端提供的已评审消息与行动链接，不使用经验、成就、建议或对话依赖式语言。设置页展示显式保留周期与真实的删除/导出状态。后台路由同时要求角色与权限声明。

- [ ] **步骤 4：验证 GREEN 与静态安全**

运行：`cd frontend && pnpm test --run src/modules/ai src/modules/settings src/modules/admin src/shared/api`  
预期：通过。

运行：`cd frontend && rg -n 'v-html|localStorage.*token|sessionStorage.*token' src`  
预期：无匹配。

- [ ] **步骤 5：提交 AI、隐私与后台 UI**

```bash
git add frontend/src/shared/api frontend/src/modules/ai frontend/src/modules/settings frontend/src/modules/admin
git commit -m "feat: add AI privacy and administration interfaces"
```

## 任务 13：部署、端到端旅程与全局数据流验证

**文件：**
- 新建：`frontend/playwright.config.ts`
- 新建：`e2e/fixtures/users.ts`
- 新建：`e2e/global-flow.spec.ts`
- 新建：`e2e/ownership.spec.ts`
- 新建：`e2e/responsive.spec.ts`
- 新建：`scripts/seed-local.sh`
- 新建：`scripts/smoke-api.sh`
- 新建：`scripts/verify-global-flow.sh`
- 修改：`deploy/nginx/default.conf`
- 修改：`README.md`

- [ ] **步骤 1：编写失败的端到端测试**

`global-flow.spec.ts` 必须执行成年人注册、三项同意、引导、目标/周计划创建、真实或配置化 Mock 的 Qwen 建议生成、幂等采纳、开始/部分/完成/延期/跳过/撤销任务事件、洞察与复盘确认、SSE 聊天、导出下载校验、删除请求/取消、后台 MFA 发布与审计验证。

`ownership.spec.ts` 必须创建两个用户，并断言跨用户的目标、日程、AI 会话、导出与附件访问返回 404/403。`responsive.spec.ts` 必须在 390x844 与 1440x900 下运行，断言无横向溢出、可见焦点、无导航重叠与非空图表。

- [ ] **步骤 2：验证 RED**

运行：`pnpm --dir frontend exec playwright test -c ../frontend/playwright.config.ts`  
预期：失败，因为运行栈与 E2E fixtures 尚不完整。

- [ ] **步骤 3：完成部署与确定性验证脚本**

配置 Nginx 支持 SPA fallback、`/api/v1`、关闭 SSE 缓冲、请求 ID、为 TLS 准备的响应头、上传限制，以及不对已认证 API 响应做缓存。`verify-global-flow.sh` 必须启动 Compose 栈，等待健康检查，运行后端 verify、前端 lint/测试/构建、Playwright、在 `AI_PROVIDER=qwen` 时执行真实 Qwen 良性建议冒烟、数据库不变量查询、Redis 键检查、MinIO 导出存在性检查与敏感日志扫描。

脚本在任何不变量失败时必须以非零退出，并打印包含以下检查的最终表格：认证 Cookie、CSRF、同意行、目标/周计划/任务行、幂等计数、事件状态、经验总量、复盘确认、Qwen 元数据、安全响应、导出对象、删除生命周期、归属隔离、后台审计与敏感日志扫描。

- [ ] **步骤 4：运行完整验证闸门**

运行：`cd backend && ./mvnw verify`  
预期：通过。

运行：`cd frontend && pnpm lint && pnpm test --run && pnpm build`  
预期：通过。

运行：`pnpm --dir frontend exec playwright test -c ../frontend/playwright.config.ts`  
预期：桌面与移动项目均通过。

运行：`./scripts/verify-global-flow.sh`  
预期：退出码 0，且每项不变量报告 `PASS`。

- [ ] **步骤 5：提交已验证的交付**

```bash
git add deploy e2e scripts README.md frontend/playwright.config.ts
git commit -m "test: verify complete growth platform data flow"
```

## 任务 14：最终安全与视觉发布审计

**文件：**
- 新建：`docs/archive/2026-09-07/release/verification-report.md`
- 新建：`docs/archive/2026-09-07/release/security-checklist.md`
- 新建：`docs/archive/2026-09-07/release/known-limitations.md`

- [ ] **步骤 1：运行密钥与敏感数据扫描**

运行：`git grep -nE 'sk-[A-Za-z0-9_-]{20,}|123456|QWEN_API_KEY=.+|MYSQL_PASSWORD=.+' -- ':!docs/superpowers/plans/*'`  
预期：跟踪文件中不出现真实密钥或已提供密码。

运行：`rg -n 'password|token|cookie|note|content' logs/`  
预期：不出现密码、原始令牌、Cookie 头、完整 AI 消息或任务备注值。

- [ ] **步骤 2：检查 Playwright 截图与画布像素**

评审每张桌面/移动截图，检查裁切、重叠、空白内容、损坏图标、不稳定控件、焦点缺失与文本溢出。确认 ECharts 画布包含非背景像素并提供可访问文本替代。

- [ ] **步骤 3：记录精确证据**

编写 `verification-report.md`，包含命令、时间戳、退出状态、测试数量、global-flow 不变量表、真实 Qwen 模型/请求元数据（不含密钥值）与 URL。在 `known-limitations.md` 中只写真实存在的剩余限制；若已无限制，则写明 `No known release-blocking limitations.`

- [ ] **步骤 4：文档后重跑发布闸门**

运行：`./scripts/verify-global-flow.sh`  
预期：在最终精确工作树上退出码为 0。

运行：`git status --short`  
预期：仅三个发布文档未提交。

- [ ] **步骤 5：提交发布证据**

```bash
git add docs/release
git commit -m "docs: record release verification evidence"
```
