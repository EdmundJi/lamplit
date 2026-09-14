---
type: 工程快速开始与改动路由
title: Lamplit 工程快速开始与改动路由
description: 提供 Lamplit 的本地启动、配置检查与分层验证入口，并把全局产品改动和 Companion Town harness 改动路由到相应专题。
tags: [quickstart, contributor-guide, development, companion-town, testing]
verified:
  - by: openwiki/0.5.1
    at: 2026-09-14T13:15:33.655Z
sources:
  - id: openwiki-source-cc9ac771b9086a58897b0e1c
    resource: repo://backend/pom.xml
  - id: openwiki-source-70576db011716879c6b60d83
    resource: repo://backend/src/main/java/com/betterself/growth/GrowthApplication.java
  - id: openwiki-source-1f3fea336fd995d78a45b574
    resource: repo://backend/src/main/java/com/betterself/growth/town/companion/application/ResidentDirector.java
  - id: openwiki-source-43e51584e32e09627af47da0
    resource: repo://backend/src/main/java/com/betterself/growth/town/companion/domain/ConversationLifecycle.java
  - id: openwiki-source-a26a90e812a2e9a6f7f7d7f8
    resource: repo://backend/src/main/java/com/betterself/growth/town/companion/domain/ResidentSeed.java
  - id: openwiki-source-b93f52b66231f83a44f148b4
    resource: repo://backend/src/main/java/com/betterself/growth/town/companion/domain/ResidentSimulation.java
  - id: openwiki-source-2203842ad674d103faccd84b
    resource: repo://backend/src/main/resources/application.yml
  - id: openwiki-source-4b571b39f042df3a80e17d02
    resource: repo://deploy/compose.yaml
  - id: openwiki-source-094485633ffa4398bb8dd91d
    resource: repo://deploy/nginx/default.conf
  - id: openwiki-source-76fe91787c48018dcf26a5b7
    resource: repo://frontend/src/app/router.ts
  - id: openwiki-source-69b522f10ffdb79d601b4fcf
    resource: repo://frontend/src/main.ts
  - id: openwiki-source-9957b4e667beb4b771b55bcf
    resource: repo://frontend/src/modules/companion/companion.store.ts
  - id: openwiki-source-9980fb42dcac64a5160c0020
    resource: repo://frontend/src/shared/api/client.ts
  - id: openwiki-source-23775c3de52f3ab95a13cb8b
    resource: repo://README.md
  - id: openwiki-source-b162d4e3c9dd4a9c513097a1
    resource: repo://scripts/verify-global-flow.sh
generated: { by: "openwiki/0.5.1", at: "2026-09-14T13:15:33.655Z" }
---

# Lamplit 工程快速开始与改动路由

本页用于选择**第一个**应读的专题和最小验证入口，不替代对改动代码、调用方和测试的追踪。Lamplit 是 Vue/Pinia SPA 加 Spring Boot 模块化单体：MySQL 是记录库，Redis 是可丢弃的加速层，MinIO 是本地 S3 兼容对象存储；小镇 resident memory 还拥有独立的文件持久化边界。浏览器只投影服务端快照，不能成为业务状态的最终裁决者。[^system] [^memory]

先读[系统概览](architecture/system-overview.md)了解全局 API、持久化和模块边界；要改小镇，先读[小镇 Harness 总览与稳定边界](architecture/companion-harness-overview.md)，再按下文的专题路由收敛范围。

## 启动本地工作区

### 前置条件与本地配置

需要 Java 21、Docker（含 Docker Compose）、Node.js >=22.13 以及 pnpm 11（仓库固定 `pnpm@11.9.0`）。从示例创建不提交的本地配置：

```bash
cp .env.example .env.local
```

`.env.local` 中的密码、`JWT_SECRET`、`MFA_ENCRYPTION_KEY`、MinIO/KMS 凭据和模型密钥都是本机配置；示例值不能用于共享或生产环境，也不得提交或写入日志。默认 `QWEN_PROVIDER=mock`，适合普通测试；真实模型调用必须显式改为 `qwen` 并提供受限的真实密钥、端点和模型。[^config]

### 选择一种开发模式

**A. 宿主机运行应用。** 仅启动依赖服务，避免 `deploy/compose.yaml` 中的 `backend` 与 `nginx` 占用宿主机的 `8080`/`80`：

```bash
docker compose --env-file .env.local -f deploy/compose.yaml up -d mysql redis minio
```

然后分别启动后端和前端：

```bash
cd backend
set -a
source ../.env.local
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```bash
cd frontend
pnpm install
pnpm dev
```

Vite 将 `/api/v1` 代理到宿主机后端。`application.yml` 以环境变量配置 MySQL、Redis、Flyway、调度、安全、对象存储、AI 及 companion memory/model routing；不要将凭据硬编码到源码。[^config] [^frontend]

**B. 共享容器开发服务器。** 让所有服务以源码挂载的开发 Compose 栈运行：

```bash
scripts/dev-server.sh up
scripts/dev-server.sh urls
scripts/dev-server.sh logs
```

这两种模式共享具名数据卷且默认端口冲突，切换前先停止另一种模式的宿主机进程。`deploy/compose.yaml` 本身是生产形态拓扑，包含 MySQL、Redis、MinIO、backend 与 Nginx；若要完整构建该形态，先产生 JAR 和 `frontend/dist`，再执行 `docker compose ... up -d --build`。小镇记忆目录必须另行配置为持久且已备份的挂载，不能只依赖容器可写层。[^operations] [^memory]

## 运行时入口：从浏览器到服务端

- `frontend/src/main.ts` 创建一个 Pinia、hydrate 外观偏好、安装 router 并挂载 `App`。`frontend/src/app/router.ts` 懒加载页面；它初始化认证，未认证访问非 public route 时转至 `/auth`，非管理员不能进入 `/admin`。[^frontend]
- 用户界面的主入口包括 `/today`、`/town`、`/goals`、`/partners`、`/friends`、`/attributes`、`/insights`、`/ai`、`/profile` 与 `/settings`；`/town/debug` 是脱离 `UserLayout` 的开发调试视图，不应当作普通产品路径。[^frontend]
- `frontend/src/shared/api/client.ts` 是常用浏览器 API 边界：请求使用 `/api/v1` 和 cookie；不安全请求在可用时携带 `X-CSRF-Token`，遇到 `401` 最多 refresh 后重试一次。服务端授权仍是安全边界，路由守卫只改善导航体验。[^client]
- 后端从 `GrowthApplication` 启动。直接检查健康状态可访问 `http://localhost:8080/actuator/health`；Nginx 前置时同一路径以及 `/api/v1` 会被代理，API 代理禁用缓存和缓冲以支持流式响应。[^config] [^compose]

## “我要改 X，应从哪里开始”

跨域改动通常需要读一行以上：第一列是行为的首要专题，第二列指出必须同时检查的稳定边界或验证页。

### 全局产品系统

| 我要改… | 应从哪里开始 | 接着检查 |
| --- | --- | --- |
| SPA/服务端职责、API 契约、持久化所有权或跨模块边界 | [系统概览](architecture/system-overview.md) | [持久化与 API 契约](architecture/persistence-and-api-contracts.md) |
| 注册、会话、Cookie、CSRF、管理员或资源所有权 | [认证与所有权](concepts/authentication-and-ownership.md) | [账户生命周期](workflows/account-lifecycle.md) |
| 目标、计划、任务事件、撤销、成长、角色或伙伴奖励 | [成长规划与执行](concepts/growth-planning-and-execution.md) | [计划与完成工作](workflows/plan-and-complete-work.md)；[社交与奖励系统](concepts/social-and-reward-systems.md) |
| AI chat、SSE、安全降级、建议生成或采纳 | [AI Coaching](concepts/ai-coaching.md) | [AI 引导规划](workflows/ai-guided-planning.md)；[AI 与对象存储](integrations/ai-and-object-storage.md) |
| 导出、附件、删除、保留期或任意用户拥有的数据 | [隐私与保留](concepts/privacy-and-retention.md) | [持久化与 API 契约](architecture/persistence-and-api-contracts.md) |
| Compose、环境变量、迁移、镜像、Nginx、健康检查或密钥 | [本地开发与部署](operations/local-development-and-deployment.md) | [验证策略](testing/verification-strategy.md) |
| 测试层级、Testcontainers、Vitest、Playwright 或发布门禁 | [测试与全局验证策略](testing/verification-strategy.md) | 受影响领域/工作流页面的 focused tests |

### Companion Town harness 专题

| 我要改… | 应从哪里开始 | 为什么还要读/跑 |
| --- | --- | --- |
| **trigger**：何时提问、工作种类、优先级、公平性、冷却或预算 | [Resident Trigger 与调度目录](workflows/resident-trigger-and-scheduling-catalog.md) | [一次 Resident Attempt 的完整 Loop](workflows/resident-attempt-loop.md)：`reserve`、`thinking` 排他和失败语义必须同步成立。 |
| **prompt/context**：persona、bounded knowledge、memory slice、可见人/物或 evidence | [Resident Context、限知与 Prompt 输入契约](concepts/resident-context-and-bounded-knowledge.md) | Harness 总览：context 只属于当前 resident，模型只提案，不能借 prompt 越过 owner 与 Todo 隐私边界。 |
| **action legality**：新增 action、option、目标、地点或模型 proposal 校验 | [动作合法性、物理结果与世界提交](workflows/action-legality-and-world-commit.md) | Attempt loop：候选菜单与 `applyDecision` 的二次合法性检查要一同改。 |
| **object behavior**：可占用/磨损/修理/借出/赠与的物件规则及环境副作用 | [动作合法性、物理结果与世界提交](workflows/action-legality-and-world-commit.md) | [Harness 总览](architecture/companion-harness-overview.md)：对象结果应由规则层写入，并进入事件/感知而非直接由模型写状态。 |
| **memory**：observed/heard/seed/reflection/belief、检索、supersession 或文件恢复 | [事件、感知、记忆与信念](concepts/events-perception-memory-and-belief.md) | [状态所有权、持久化与并发提交](architecture/companion-state-ownership-and-concurrency.md)：world JSON、Markdown memory 与 SQLite 索引不是一个原子事务。 |
| **conversation**：turn、summary、公开言说、operation 或超时 | [一次 Resident Attempt 的完整 Loop](workflows/resident-attempt-loop.md) | 仅通过 `ConversationLifecycle` 的 operation 验证才能把 draft 变成已说出口的话。 |
| **scenario seed**：居民、家庭/地点、人设、职责、初始关系、知识或 warm start | [场景种子、居民初始基因与 Warm Start](concepts/scenario-seeds-and-resident-genesis.md) | `ResidentSeed` 是手工 genesis：新增 resident 不会由模拟自动招募；同步检查种子与人口测试。[^seed] |
| **frontend projection**：`/town`、轮询、snapshot/revision、Phaser/DOM 展示或即时反馈 | [多 Agent 小镇：边界与专题入口](concepts/companion-town.md) | `useTownWorld` 共享 Pinia world，拒绝 revision 倒退；不要在前端模拟权威动作、记忆或结果。[^projection] |
| **metrics**：timeline、outcome、人格/关系指标、norm detection、盲测或导出解释 | [小镇实验 Harness、指标与消融](testing/companion-experiment-harness-and-ablations.md) | 指标是观测证据而非因果结论；以应用点 outcome 而非 UI 文案判断 applied/rejected/failed。 |
| **experiment runner**：时钟、tick、预算、模型配置、rule-only 对照、resume 或 manifest | [小镇实验 Harness、指标与消融](testing/companion-experiment-harness-and-ablations.md) | `AcceleratedTownRunner` 必须经公共 `CompanionService` 推进，不要另造语义不同的“快速模拟器”。 |

小镇的一条安全路线是：**trigger selection → reservation → context → model draft → validation → rule commit → perception/memory → 前端下一次 projection**。规则提交、网络调用和模型结果提交是分离阶段；因此改 prompt 或并行度也可能影响过期和拒绝率，而不仅是文本质量。[^harness] [^attempt]

## 修改与验证的最低纪律

1. **先找权威写入者。** UI 只改投影；授权、幂等、持久化转移和动作裁决留在服务端。尤其不能让小镇 focus 自动完成真实 Todo。[^town]
2. **沿边界双向追踪。** 从 route/view/Pinia 到 API、controller、application、domain、store；后端变更则反向追到 API wire shape 和前端 projection。
3. **把多存储当作多边界。** MySQL、对象存储和 companion memory 文件需要分别考虑备份、删除和恢复；Redis 不能作为丢失持久状态的权威回退。[^system] [^memory]
4. **实验只改变一个变量。** model-on 与 rule-only 对照应固定 seed、代码、模型快照、时钟、tick、时区、预算与 parallelism；原始模型输出、memory、snapshot 和 `.env.local` 不应提交。[^experiment]
5. **用最窄的测试证明不变量。** 改小镇 trigger/并行运行 `ResidentDirectorParallelTest`；改对话运行 `ResidentDirectorDialogueTest`；改 memory/retrieval 运行 `FileMemoryStoreTest` 与 `CompanionRecallTest`；改 projection 运行 `companion.store.test.ts`；改 runner/导出运行对应 Harness 测试。随后按影响面升级。[^harness] [^projection]

## 交付前的验证入口

迭代时先跑针对性测试；跨越持久化、安全、浏览器/API、AI 或部署时，再执行完整检查：

```bash
cd backend && ./mvnw test
cd frontend && pnpm lint && pnpm test --run && pnpm build
cd ..
docker compose --env-file .env.local -f deploy/compose.yaml config
./scripts/verify-global-flow.sh
```

Surefire 同时包含 `*Test` 和 `*IT`，并在测试中关闭应用调度。全局脚本要求 `.env.local`，启动完整 Compose 栈并等待健康状态，然后顺序运行 Maven `verify`、前端 lint/Vitest/build、Playwright、API smoke、MySQL 断言、已认证 Redis `PING` 及敏感日志扫描。浏览器失败时保留 Playwright trace/截图和最早失败层的完整输出；不要靠重置卷或重跑掩盖证据。[^verify]

[^system]: 模块化单体与存储角色：repo://README.md#L27-L35；后端依赖：repo://backend/pom.xml#L51-L94
[^config]: 环境驱动运行配置：repo://backend/src/main/resources/application.yml#L1-L119；本地示例与 mock 默认值：repo://.env.example#L2-L76
[^operations]: 两种模式、生产 Compose 与记忆挂载注意事项：repo://openwiki/operations/local-development-and-deployment.md#L11-L16；repo://openwiki/operations/local-development-and-deployment.md#L89-L125
[^memory]: 记忆文件、SQLite 索引与 world store 的 hydrate/persist 边界：repo://backend/pom.xml#L88-L95；repo://backend/src/main/resources/application.yml#L92-L119
[^frontend]: 前端 bootstrap 与路由：repo://frontend/src/main.ts#L1-L13；repo://frontend/src/app/router.ts#L4-L59
[^client]: 浏览器 API 客户端：repo://frontend/src/shared/api/client.ts#L1-L40
[^compose]: Compose 服务拓扑：repo://deploy/compose.yaml#L3-L125；Nginx 代理：repo://deploy/nginx/default.conf#L14-L48
[^seed]: 手工种子及唯一新增入口：repo://backend/src/main/java/com/betterself/growth/town/companion/domain/ResidentSeed.java#L13-L28；初始化：repo://backend/src/main/java/com/betterself/growth/town/companion/domain/ResidentSeed.java#L125-L168
[^projection]: 服务端快照、revision guard 和共享轮询：repo://frontend/src/modules/companion/companion.store.ts#L9-L14；repo://frontend/src/modules/companion/companion.store.ts#L60-L98；repo://frontend/src/modules/companion/companion.store.ts#L132-L171
[^town]: 小镇边界与 Todo 约束：repo://README.md#L9-L19；repo://openwiki/concepts/companion-town.md#L17-L30
[^harness]: 驱动、边界和 focused tests：repo://openwiki/architecture/companion-harness-overview.md#L45-L57；repo://openwiki/architecture/companion-harness-overview.md#L94-L108；repo://openwiki/architecture/companion-harness-overview.md#L133-L140
[^attempt]: reservation、事务外 I/O、版本/验证：repo://openwiki/workflows/resident-attempt-loop.md#L11-L15；repo://openwiki/workflows/resident-attempt-loop.md#L77-L94
[^experiment]: runner、控制变量与敏感输出边界：repo://openwiki/testing/companion-experiment-harness-and-ablations.md#L11-L13；repo://openwiki/testing/companion-experiment-harness-and-ablations.md#L49-L67
[^verify]: Maven 选择器：repo://backend/pom.xml#L169-L188；验证脚本：repo://scripts/verify-global-flow.sh#L1-L33
