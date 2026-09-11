**简体中文** · [English](README.md)

# 陪伴式小镇

一个适合长时间打开、陪伴用户学习与生活的网页小镇。用户搬进已有居民和过去的小街，与一个自主小人共享部分日程，通过明确安排或偶尔的念头影响它的生活。

第一版入口是 `/town`：搬进一条已有四名居民的小街，选择真实 Todo 陪伴专注，或让小人按自己的节律生活。世界由后端保存，刷新后继续；专注结束不会自动完成真实任务。

居民保留自己的需求、计划、关系和有来源的记忆，实际相遇会带来协商、合作及新的小愿望。模型可以在规则约束内调整计划和提出新项目；服务不可用时，居民仍按已有计划生活。认证和真实任务沿用现有基础，新小镇存档和推进逻辑独立。

已实测 DeepSeek V4 Flash 的逐轮对话、模型自选 emoji 和各自的会后记忆。模型配置沿用历史 `QWEN_` 环境变量前缀：`QWEN_PROVIDER=qwen`、`QWEN_BASE_URL=https://api.deepseek.com`、`QWEN_MODEL=deepseek-v4-flash`，密钥仅放在被忽略的 `.env.local`；此模型的居民短对话使用非思考模式。

- [当前进度](progress.md)：最近做了什么、接下来想做什么。
- [01 我们想做的小镇](docs/01-requirements.md) · [02 代码怎么组织](docs/02-modules.md)：做什么、代码如何组织。
- [文档导航](docs/README.md)：按编号查看全部资料；[agent.md](agent.md)记录简短开发约定。
- [参考文献](references/README.md)：两篇研究论文原文与官方实现链接。

现有应用基于 Vue 和 Spring Boot 模块化单体。MySQL 是权威数据存储，Redis 提供可丢弃的加速，MinIO 提供本地 S3 兼容对象存储。以下为现有代码的开发方式。

## 环境要求

- Java 21
- Docker（含 Docker Compose）
- Node.js 22.13 或更新（`packageManager` 锁定的 pnpm 11.9 要求这个版本，Node 20 上会直接崩）
- pnpm 11

## 本地启动

从 `.env.example` 创建被忽略的本地环境文件，并在使用共享或类生产环境前替换示例值。

```bash
cp .env.example .env.local
docker compose --env-file .env.local -f deploy/compose.yaml up -d
```

以本地 profile 运行后端：

```bash
cd backend
set -a
source ../.env.local
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

运行前端：

```bash
cd frontend
pnpm install
pnpm dev
```

小镇使用已购买的四个 LimeZu 素材包，素材不进入版本库。首次运行前把 `modernexteriors-win.zip`、`moderninteriors-win.zip`、`Modern_Farm_v1.2.zip` 和 `Modern_Office_Revamped_v1.2.zip` 放到 `tmp/`，然后生成图集。脚本需要 Pillow；本机默认 `python3` 里没有的话，用 `uv run --with pillow python <脚本>` 即可，不必污染全局环境：

```bash
python3 scripts/build-town-assets.py
python3 scripts/build-companion-assets.py
```

现有代码入口与新版差异见[现有系统说明](docs/02-modules.md)；素材规则见[素材说明](frontend/public/assets/town/README.md)。

## 共享开发服务器

需要把这台机器当成一台大家都能连上来联调、调试的开发服务器时，用这一套——前后端都跑在容器里，
源码从本机挂进去，改完自动生效，其他开发者不需要在自己机器上装 Java 21 或 pnpm。

```bash
scripts/dev-server.sh up      # 启动（首次要下 Maven / pnpm 依赖，慢一次）
scripts/dev-server.sh urls    # 打印发给其他开发者的访问地址
scripts/dev-server.sh logs    # 跟日志，也可以 logs backend
```

它和上面「本地启动」是**二选一**的关系：两套用的是同一批数据卷，数据互通，但端口会打架。
切过去之前先停掉本机的 `pnpm dev` 和 `mvnw spring-boot:run`。

完整说明（端口、远程调试怎么挂、数据怎么重置、给协作者的须知）见[共享开发环境](docs/archive/2026-09-07/开发服务器.md)。

## 首位管理员

应用不开放公开的管理员注册入口。要在不存在活跃 `ADMIN` 时创建首位管理员，请在启动后端前设置以下环境变量：

```bash
ADMIN_BOOTSTRAP_EMAIL=admin@example.com
ADMIN_BOOTSTRAP_PASSWORD='replace-with-a-strong-password'
ADMIN_BOOTSTRAP_DISPLAY_NAME=系统管理员
ADMIN_BOOTSTRAP_TIMEZONE=Asia/Shanghai
ADMIN_BOOTSTRAP_MFA_SECRET=
```

如果 `ADMIN_BOOTSTRAP_MFA_SECRET` 为空，后端会生成一个 TOTP 密钥并在服务器日志中打印一次。将该密钥添加到身份验证器应用，登录并完成 MFA，然后移除引导变量。

## 验证

```bash
cd backend && ./mvnw test
cd frontend && pnpm lint && pnpm test --run && pnpm build
docker compose --env-file .env.local -f deploy/compose.yaml config
```

在后端与前端就绪后，运行完整的本地数据流闸门：

```bash
./scripts/verify-global-flow.sh
```

该闸门验证：认证 Cookie 与 CSRF、同意持久化、目标/周计划/任务物化、任务状态转换与撤销、AI SSE 与固定危机替代、建议幂等采纳、ZIP 导出、归属隔离、删除冷静期/取消、数据库不变量、Redis 健康与敏感日志扫描。自动化验收默认使用 `QWEN_PROVIDER=mock`，不会访问外部模型。要让产品实际调用模型服务，请在未提交的 `.env.local` 或服务器环境中设置 `QWEN_PROVIDER=qwen`、匹配的 `QWEN_BASE_URL`、`QWEN_MODEL`、有效的 `QWEN_API_KEY`，以及可选的 `QWEN_TIMEOUT`（默认 120 秒）和 `QWEN_STREAM_TIMEOUT`（默认 130 秒）；占位密钥会被拒绝，真实密钥绝不写入仓库。

后端健康检查地址为 `http://localhost:8080/actuator/health`。Nginx 用作 SPA 边缘服务器时，已配置暴露同一地址并代理 `/api/v1`。

## 致谢

这个项目不是从空白开始的。而**感谢一份工作最诚实的方式，是精确说出我们从它那里拿走了什么**——包括那些我们读得够细、以至于选择了不跟的地方。

### 基座

本项目起源于 Gitee 上的 **[moonlight-in-lonely-city/personal_study](https://gitee.com/moonlight-in-lonely-city/personal_study)**。

按 2026-09-11 的存活行数，**[griffty73-debug](https://github.com/griffty73-debug) 写下的代码约占全部的 43%（27,615 行）**——`achievement/` 与 `admin/` 两个子系统，从他写完那天起一行没改过。他于 2026-09-11 书面授权，把全部既往贡献从木兰宽松许可证第 2 版重新许可为 Apache-2.0，记录见 [`RELICENSE.md`](RELICENSE.md) 与 [issue #1](https://github.com/EdmundJi/lamplit/issues/1)。

### Generative Agents —— 脊柱

**Joon Sung Park, Joseph C. O'Brien, Carrie J. Cai, Meredith Ringel Morris, Percy Liang, Michael S. Bernstein.** *Generative Agents: Interactive Simulacra of Human Behavior.* UIST 2023. [arXiv:2304.03442](https://arxiv.org/abs/2304.03442v2)

没有这篇，这个小镇不会有架构——它会是四个小人各自按计时器演一个动作，而那**正是第一版的样子**。它给了我们：

| 论文里的 | 我们这边在哪 |
|---|---|
| 检索按 recency + importance + relevance 打分（§4.1、Fig. 6） | [`CompanionRecall.java`](backend/src/main/java/com/betterself/growth/town/companion/domain/CompanionRecall.java) |
| 反思由累积的重要性触发，而不是定时（§4.2） | `ResidentSimulation.needsReflection` |
| 记忆分层，反思的层级高于原始观察（§4.2、Fig. 7） | `CompanionRecall.tier` |
| **「这个居民要不要对刚看到的事做出反应」单独成为一问**（§4.3.1） | `ResidentMind.react` —— 论文原句被逐字引在代码注释里 |
| 环境表达成地点、子区域和可用物件（§3.2、Fig. 2） | `TownPlaces`，以及 [docs/01](docs/01-requirements.md) 的地图一节 |

第四行是我们借到的最值钱的一个想法，而且有数字：作为一长串动作里的一项，`invite` 被提供 96 次、采纳 **0** 次；把同一件事**单独问出来**，同一个模型 357 次里答应了 168 次。后来我们把这个洞察推广到了论文没走到的地方——`celebrate`、`propose`、`venture`、承诺。**推广是我们的，洞察是他们的。**

走了别的路的地方：relevance 在我们这里是对其它因子做**乘法**而不是相加（相加会让"新鲜、重要、但完全跑题"的记忆排到前面）；重要性由规则给定而不是让模型打分；计划停在三四段定性描述，不递归到 5–15 分钟的时间槽。

还有一笔容易被忽略的账：**§7.2 里作者自己报告，他们的智能体会滑向过度礼貌与合作。**我们把这条局限当真到了照着它设计的地步——本我/超我/自我三层，以及"允许往坏了长"那条规矩，都是因为那一段才存在的。**一篇能让你在具体处反对它的论文，比一篇你只能引用的论文值钱得多。**

### a16z-infra/ai-town —— 工程

**[a16z-infra/ai-town](https://github.com/a16z-infra/ai-town)**

两个具体的坑，每一个都能耗掉好几周：

**对话的并发写入。**好几个居民由异步模型调用驱动，而调用可能超时、可能迟到、可能在世界已经翻篇之后才返回。ai-town 的"每次操作一个身份"，正是 [`ConversationLifecycle.java`](backend/src/main/java/com/betterself/growth/town/companion/domain/ConversationLifecycle.java) 里"预定一次发言权成为一次性的 `Operation`、落地前逐项复核"的由来——迟到的回复永远覆盖不了一段已经结束的对话，两个请求也永远不会同时以为轮到自己。**那是独立的 Java 实现，没有嵌入任何 Convex 代码。**

**移动。**「碰撞图加寻路，人可以停在任意可站立的点上」（[docs/04](docs/04-decisions.md)）是直接从它那儿来的，省掉了一次必然的返工——否则我们会先做成枚举槽位，直到四个人要站进只有四席的花园时才被迫推倒重来。实现见 `collision.ts` 与 `pathfinding.ts`。

有三件事我们当时就明确写下"不跟"：它的锐角人格设计、它的向量记忆 + reflection（这个镇子跑的是限知与口耳相传），以及它持续运行的世界。**能让人白纸黑字写下"这里我们不跟你"，本身也是一种影响力。**

### Humanoid Agents —— 反馈闭环

**Zhilin Wang, Yu Ying Chiu, Yu Cheung Chiu.** *Humanoid Agents: Platform for Simulating Human-like Generative Agents.* EMNLP 2023 System Demonstrations. [arXiv:2310.05418](https://arxiv.org/abs/2310.05418v1)

Generative Agents 给了感知—计划—反应，但留下一个空白：**一个动作做完之后，哪些内部变化该反过来影响下一步？**这篇回答了它，而那个回答就是这个小镇运转的那条闭环。

两处几乎是照着做的：内部量静默积分、只有越过阈值才转成一句定性描述（§3.2）；以及**亲密度以词而不是以数字存在**——`ResidentDirector.closeness` 的注释里自己写明了这一条抄自他们的例子。

然后是分歧，也是我们最有把握的一处。论文里，情绪是一个每轮都交给模型的持久分类；而在这里，**任何内部数值都不会进入模型上下文**——`energy`、`social`、`dutyPressure` 这些被按字段名断言的测试挡着，改规矩就得改测试。理由写在 [docs/04](docs/04-decisions.md)：**数值进了上下文，模型就会像读表格一样推理。**

这不是在纠正他们。**他们需要状态可读、初值可设，因为他们的实验要靠消融它；我们要的是一个看不出刻度的小镇。同一条闭环，相反的要求**——而我们能知道这一点，只因为那篇论文具体到了可以被反对。

### 美术与音频

- **[LimeZu](https://limezu.itch.io/)** —— 这个小镇的每一个像素都来自四个**已购买**的素材包：Modern Exteriors、Modern Interiors、Modern Farm、Modern Office Revamped。**它的美术是"这个地方值得坐下来待着"这件事的大半。**署名是这些素材授权的要求，而再分发是被禁止的——这也是为什么生成产物和原始压缩包都不在这个仓库里：想看到这个小镇，需要自己去买。
- **[Twemoji](https://github.com/twitter/twemoji)** —— 界面里的 emoji 图形，[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。
- **Cafe ambiance** —— 咖啡馆环境音，作者 Marble Toast，[CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/)，来自 [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Cafe_ambiance.ogg)。

## 许可

[Apache License 2.0](LICENSE)。署名与重新许可的经过见 [`NOTICE`](NOTICE) 和 [`RELICENSE.md`](RELICENSE.md)。
