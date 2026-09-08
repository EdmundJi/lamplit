# 05 · 实测与环境

这一页记的是查出来、量出来的事实，以及几次真实事故的教训。它们重新推导一遍很贵，所以写下来。

## 开发服务器与外部访问

有一条 frp 隧道供手机等设备访问：**北京 `frps:15173` → frp-gateway → `192.168.0.104:5173`**，进去的是容器 `growth-frontend-1`，不是本机的 `pnpm dev`。容器 bind mount 源码，所以改完文件那边直接生效。入口是 `http://39.106.137.127:15173/town`。

三根不能碰的线：

- `frontend/vite.config.ts` 里 `allowedHosts: inContainer ? true : undefined`——这一行让 frp 的 Host 头能过，删了或改成写死域名，外部访问立刻 400。
- `/town` 这条路径本身。收进 `UserLayout` 时路径不变，但不能改成别的，否则手机上的书签就废了。
- `deploy/compose.dev.yaml` 的 `VITE_DEV_CONTAINER: "true"` 和 `5173:5173` 映射。

**后端容器每两秒轮询 `src/main/java` 和 `src/main/resources`，有改动就重新编译，devtools 随即重启。** 不需要谁去重启——文件一落盘它自己就跑了。见下面那次事故。

素材生成需要 Pillow，本机默认 `python3` 里没有，用 `uv run --with pillow python <脚本>`。

## 模型

主模型是 **DeepSeek V4 Flash**（`QWEN_` 前缀是历史遗留，实际指向 `https://api.deepseek.com`）。视觉模型 `deepseek-v4-flash-vision-exp` 确实存在，2026-08-21 发布，每张图固定 384 token，单请求最多 600 张图，图片只能出现在 user message。

### 换供应商的实测（同一个 prompt，让居民写一条记忆）

| 模型 | 用时 | 输入 token | 回答 |
| --- | --- | --- | --- |
| gpt-5.6-terra | 58.2s | 4480 | 我记得三点十七分，所有人同时停住，连咖啡表面的泡沫也没动。 |
| gpt-5.6-sol | 45.4s | 4480 | 三点零七分，所有人停住了，只有我的秒针走完一圈又一圈。 |
| gpt-6-astra | 9.4s | 4480 | 我记得三点到四点，咖啡馆里所有人一动不动。我敲了三次桌子。 |
| **deepseek-v4-flash** | **1.9s** | **150** | 三点整，奶泡凝在半空。所有人不动了。我转头看钟，四点，他们继续说话。 |

三个维度全赢，回答也最好——它写出了时间断层从内部经历是什么感觉。那个网关（`https://api.jieyouai.it.com/v1`）没有用户说的 `gpt-5.6-luna`，而且**同样的 prompt 它算 4480 输入 token，DeepSeek 算 150**，说明中间被塞了约 4300 token 我们看不见的东西：人格控制会漏，"价格很低"也不成立。

结论：**主模型不换。** 这是单样本，但 30 倍延迟和 30 倍输入 token 不是采样噪声。以后要比供应商，用盲测那套工具比，别拿一句话拍板。

### 真正的杠杆是关掉思考

2026-09-08 接入 Qwen3.8-Flash（DashScope 的 OpenAI 兼容端点）时实测。同一个 NPC 决策形状的提示词，三个提示词取中位数：

| 配置 | 中位耗时 | 中位输出 token | 其中推理 token |
| --- | --- | --- | --- |
| deepseek-v4-flash 默认 | 1.57s | 76 | 62 |
| **deepseek + `{"thinking":{"type":"disabled"}}`** | **0.88s** | **9** | 0 |
| qwen3.8-flash 默认 | 3.70s | 235 | 219 |
| **qwen3.8-flash + `enable_thinking:false`** | **0.90s** | **11** | 0 |

**关掉思考是数量级的差别：两家都是约 10 倍输出 token、约 2 倍耗时。** 这类决策不需要思维链，开着纯属浪费。

**两家参数名不一样**——Qwen 是 `enable_thinking:false`，DeepSeek 是 `{"thinking":{"type":"disabled"}}`。所以通用代码里不能硬写某一家的字段名，要做成供应商无关的开关，由各自 adapter 翻译。

**关掉之后两家基本打平**（0.88s/9 token 对 0.90s/11 token，差别在噪声内）。所以选谁不再是速度问题，而是价格（DeepSeek 输出 2/M vs Qwen 3/M，缓存 0.2）、创作质量、和多一家的冗余价值。

**我在这里犯过一次错，记下来防止重犯**：第一次测的时候给 DeepSeek 也传了 `enable_thinking:false`，它不认，于是我得出"DeepSeek 关不掉推理"并据此推荐了路由方案。**错的是测法不是事实。** 跨供应商比较时，每一家的参数名都要查它自己的文档，一个参数在 A 家无效不等于 A 家没这个能力。

**还没测的**：以上都是"决策"类调用。写对白那种创作性任务关掉思维链会不会掉质量，没有证据，不要拍脑袋切过去——这个可以用盲测量。

顺带一个命名陷阱：`QWEN_*` 那组变量**历史上指向的是 DeepSeek**。真的接进 Qwen 之后这个前缀会主动骗人，必须改成诚实的名字并保持向后兼容。

### 接入 Qwen3.8-Flash 补充供应商：实现记录（2026-09-08）

**命名。** `QWEN_*`（provider/base-url/api-key/model/timeout/stream-timeout/json-mode）改名为 `DEEPSEEK_*`，因为这组变量从一开始就指向 `https://api.deepseek.com`。向后兼容靠 Spring 占位符的嵌套默认值实现：`${DEEPSEEK_BASE_URL:${QWEN_BASE_URL:默认值}}`，application.yml 里 `app.ai.*` 这几项都是这个写法——读不到新名字就退到旧名字，两边同时存在时新名字赢。共享容器和本地环境里还留着的 `QWEN_*` 不用改也能继续跑。新增的 `QWEN3_*`（base-url/api-key/model/timeout/stream-timeout/json-mode）没有旧名字要兼容，直接是新变量。

**两个供应商并存，不是替换。** `com.betterself.growth.ai.QwenHttpProvider` 还是原来那一个类（继续处理任意 OpenAI 兼容网关），但现在被实例化两次：一次是原有的 `app.ai.*` 单例（`@Primary`，供 `AiService`/`GoalTemplateService`/`SuggestionService` 和小镇的 deepseek 路线共用，行为完全不变），一次是 `CompanionModelConfig` 里手工 `new` 出来的 qwen3 实例，专供小镇用。qwen3 的凭证缺失时不会让应用起不来——落到 `UnavailableModelProvider`，调用即抛 `AI_PROVIDER_NOT_CONFIGURED`，和"配置了但网关挂了"走同一条失败路径，交给下面的降级处理。

**`thinking` 开关是供应商无关的。** `QwenProvider.StructuredPrompt` 新增一个可空 `Boolean thinkingEnabled` 字段（4 参数构造器，3 参数的旧构造器保留，等价于传 `null` = 不表态，交给供应商自己的默认行为）。`QwenHttpProvider` 按自己被配置成的 `thinkingStyle`（`"qwen"` 或 `"deepseek"`，构造时指定）把这个布尔值翻译成对应的线上字段：Qwen 是顶层 `enable_thinking:false`，DeepSeek 是 `{"thinking":{"type":"disabled"}}`。调用方（`QwenResidentMind`）永远只说"要不要思考"，从不硬编码某一家的字段名。原来那行按模型名前缀（`deepseek-v4-*`）强制关闭思考的旧逻辑原样保留在它之后，两个已有测试还在断言它，没有削弱。

**按调用类型配置 thinking，而不是按供应商固定。** 现有三种调用是 decision / turn（对白）/ summary（回忆），在 `ResidentDirector`/`ModelUsageRecorder` 里就是这三个 `callType`。配置项：

```yaml
app.town.companion-model.thinking.decision: ${COMPANION_MODEL_THINKING_DECISION:false}
app.town.companion-model.thinking.turn: ${COMPANION_MODEL_THINKING_TURN:}      # 空 = 不表态
app.town.companion-model.thinking.summary: ${COMPANION_MODEL_THINKING_SUMMARY:}
```

同一组值同时喂给 deepseek 和 qwen3 两个 `QwenResidentMind` 实例——不管路由把某次 decision 调用交给哪一家，思考都是关的；turn/summary 保持"不表态"，也就是两家各自的默认行为（当前观察都是思考开着）。默认只关 decision，是因为**两家实测关掉思考后耗时和 token 都数量级下降**（表见上一节），而对白/回忆是创作性任务，关闭思维链会不会伤质量还没有证据，**先不切**——这是等证据的保守选择，不是"DeepSeek 关不掉"（那个结论是错的，见上一节的更正）。

**调用类型路由 + 跨供应商降级用同一份配置。**

```yaml
app.town.companion-model.routes.decision: ${COMPANION_MODEL_ROUTE_DECISION:qwen3,deepseek}
app.town.companion-model.routes.turn: ${COMPANION_MODEL_ROUTE_TURN:deepseek,qwen3}
app.town.companion-model.routes.summary: ${COMPANION_MODEL_ROUTE_SUMMARY:deepseek,qwen3}
```

`RoutingResidentMind`（新增，`ResidentDirector` 现在依赖的唯一 `ResidentMind` 实现，`@Primary`）对每种调用类型按这个列表顺序尝试：第一个失败就退到第二个，两个都失败才把异常抛给 `ResidentDirector`，让它原有的连续失败退避（`modelConsecutiveFailures` / `modelRetryAfter`，见 `application/ResidentDirector.java`）接手——那部分调度逻辑完全没动。这意味着"路由"和"降级"是同一张表：谁排第一是默认走谁，后面的名字就是它挂掉之后的退路。

默认路由：decision 先 qwen3 后 deepseek（用户明确要求 Qwen 做主力）；turn/summary 先 deepseek 后 qwen3（现状不变，qwen3 只作为对白/回忆的兜底，而不是主力——万一失败也总比彻底没人说话强）。**这不是因为哪家关不掉思考**——两家现在都能关，见上一节的更正；纯粹是"创作性调用先不切换默认供应商，等有盲测证据再说"的保守选择。

**用量统计分供应商，不改表结构。** `town_companion_model_usage` 的 `call_type` 是 `VARCHAR(16)`，这次改动不碰 `db/migration/`。做法：`ModelUsageRecorder` 新增一个默认方法 `record(..., String provider, ...)`，把供应商编码折进 `call_type` 里存，例如 `decision@q3`、`turn@ds`（`deepseek`→`ds`、`qwen3`→`q3`，编码表在 `ModelUsageQuery.PROVIDER_CODES`，短码是因为长度要留够——`summary@deepseek` 17 字符会超限，`summary@ds` 10 字符不会）。旧的四参数方法完全不变，没有 provider 信息的调用（没测过量的、mock、还没升级的老 `ResidentMind` 实现）继续写成不带 `@` 的原始 `call_type`，`JdbcModelUsage`、加速跑用的 `InMemoryModelUsage`（`tools/**`，本次不碰）都不需要改一行代码就自动兼容。读side `ModelUsageQuery.DailyUsage` 新增两个派生方法 `baseCallType()`/`provider()`，从同一个字符串解析回来，没有 `@` 就返回 `null` 供应商。

**改了 `ResidentDirector.java` 的地方，仅此一行**（`recordUsage` 方法内）：

```java
// 改前
usageRecorder.record(userId,day,callType,usage.inputTokens(),usage.outputTokens());
// 改后
usageRecorder.record(userId,day,callType,usage.provider(),usage.inputTokens(),usage.outputTokens());
```

调度决策逻辑（谁在什么时候被选中说话、`reserve`/`run`/退避计算）一行没动。

**Bean 装配**：`QwenResidentMind` 不再是 `@Component`（两个供应商时不再有唯一默认实例可言），改成 `CompanionModelConfig`（新增 `@Configuration`）里手工装配的两个具名 bean（`deepseekResidentMind`/`qwen3ResidentMind`），外加新的 `RoutingResidentMind`（`@Primary`）。因为现在有两个 `QwenProvider` bean 并存，原来隐式拿到唯一实例的 `AiService`/`GoalTemplateService`/`SuggestionService` 会因为"多个候选、没有限定符"而装配失败——补的办法是给 `QwenHttpProvider`/`MockQwenProvider` 也标 `@Primary`（二者靠 `app.ai.provider` 互斥，不会同时存在，不冲突），保证所有不带限定符的注入点仍然解析到和以前一样的那个 bean。

**真调用过两家，数字如下**（`CompanionModelLiveIT`，opt-in，`COMPANION_LIVE_MODEL_TEST=true` 才跑，真实的 decide() 调用，走完整 Context，decisionThinking=false）：

| 供应商 | 模型 | 耗时 | 输入 token | 输出 token |
| --- | --- | --- | --- | --- |
| qwen3 | qwen3.8-flash | 1944ms | 1132 | 99 |
| deepseek | deepseek-v4-flash | 1398ms | 1067 | 61 |

这次是单样本、真实 Context（含记忆/可见物体等，比上面中位数测试用的最小 prompt 更大），不是那张中位数表的重复，只用来证明"两个供应商这次改动之后都真的能被调用"，数字比中位数表大属正常（prompt 更长）。两次调用都成功返回合法 JSON decision，`RoutingResidentMind` 的降级路径另有单元测试覆盖（`RoutingResidentMindTest`，用会抛异常的假 provider 模拟"挂了"，不打真实网络）。

### 视觉模型能不能给素材分类（负面结果，已测完）

47 个人工标注样本，试过四种问法：开放提问、给定 12 类词表让它选、三档放大倍数、47 张拼成一张编号图一次问完。

| 问法 | 类别准确率 | 请求数 | 每条 token |
| --- | --- | --- | --- |
| A 开放提问（8x） | 36%（17/47） | 47 | ~672 |
| C 闭合提问（4x / 8x / 16x） | 53% / 53% / **55%** | 47 | 670 / 672 / 902 |
| B 拼图批量（一次问 47 个） | **62%**（29/47） | **1** | **~127** |

三条都是负面结论：

**闭合提问没有解决问题。** 36% → 53%，但离能用（无监督入库要 85%+，人工抽查辅助也要 70~80%）差得远。它治好的是"输出不可控"——模式 A 有 15/47 的回答直接跑出词表，答"扭蛋机""电脑""水晶"；闭合提问把这些压进一个合法类别，但压进去的往往是另一个错的。**看不懂图这件事没变。**

**放大倍数不是变量。** 4x 到 16x 只涨 2 个百分点，n=47 下是噪声。16x 多烧 34% token，零收益。32x32 的信息量就那么多，瓶颈是类别本身有歧义，不是看不清。

**书架仍然 0/4。** 五种方法、20 次独立作答，一次没对，稳定答"椅子"。带伞的庭院桌 10/10 全答"灯具"（伞顶像灯罩）。这两个是**稳定错觉**，换问法换倍数都纠正不了。而厨房电器是**真随机**——同一张图在不同放大倍数下给出 desk / cabinet / desk / kitchen 四种答案，规则修不了，因为模型自己也不知道自己在猜。

顺带一个有用的工程发现：拼图请求的 `prompt_tokens` 只有 567，和单张图的 416 同量级——**API 按图收固定预算，塞 1 个物件和塞 47 个一样价钱**。所以真要用，走拼图批量，成本降到五分之一。另外这模型经常把 token 预算全花在推理上、正文留空，`max_tokens` 要给到 3000 以上并对空响应重试。

所以：**一万三千个不标。** 这条小街真正会用到的一两百个，可以让模型出草稿再逐条人工确认——plant / window / lamp 闭合提问下稳定可靠，书架和厨房电器直接跳过模型手工标。

公开研究的结论也一致：VLM 适合当高召回的候选筛选器，不适合独立判定。

还有一条没测的路：这批素材是从固定素材包里精确抠出来的模板贴图，不是照片。同一个书架在一万三千个里出现多次，像素完全一致。**图像哈希对一个小型人工参考库做匹配，对这种离散模板资产理论上比通用 VLM 准得多也便宜得多。** 真需要规模化标注时先试这条，别再试 VLM。

### 手抄是个静默失败点

第一轮盲测整个作废：脚本生成了二十道题，只打印了前两道，写 agent 简报时后十八道**是编排者自己编出来的**。三个 agent 认真答完了一份假题，正确率对应不上任何答案，它们对"四个人像不像同一个人"的判断评价的其实是编排者的文笔。同一轮里另一个 agent 把路径 `personal-study` 抄成 `personal_study`，也读不到文件。

两次是同一类错：**把已经生成好的东西又用手抄了一遍**。而且它不会报错——假题读起来完全合理，测试跑得很顺，结论看着很像那么回事。

规矩：产物生成之后，**让消费者直接去读那个文件**，不要把内容转录进提示词。盲测的题目和答案要分在两个目录，题目目录里只有题目。

### 一键全屏去哪了

搜遍前端找不到任何 Fullscreen API 的调用，容易得出"这个功能从没做过"的结论。**实际是做过的，跟着旧版小镇一起被删了。** 现在的等价物是「安静模式」——同样是一键把内容区放大到沉浸状态，只是不走浏览器的全屏 API。不用再找，也不用重做。

## 素材

四个 LimeZu 包在 `tmp/`，约 **10 万个 PNG**，但绝大部分是冗余：三种分辨率、三种阴影变体、`Complete_Singles` 与 `Theme_Sorter_Singles` 重复（已用 MD5 验证字节相同）。

只取 **32x32 带阴影 singles**，去重后 **13132 条**：modern_exteriors 6221 / modern_interiors 5470 / modern_farm 1102 / modern_office 339。路径自带主题（`4_Bedroom_Singles`、`17_Garden_Singles` 等）。

产物在 `frontend/public/assets/town/catalog/`（gitignored）：`asset-catalog.json`、`summary.json`、85 张联系表，以及一个生成的 `index.html`——里面有一张「按用途找」的表（桌子/椅子/床/书架/沙发/收纳柜/灯具……各自链到对应页），这是给人翻的入口。脚本 `scripts/build-asset-catalog.py`，可重复跑，同样输入产出同样 id。

几何字段（尺寸、内容 bbox、底部接地宽度）经十条抽样独立重算，10/10 完全一致，可信。分类字段是从主题目录继承的，抽样里 2/10 可疑（消防车被归成家具、加油站价格牌数字被归成车辆），那些主题不在这条街的范围里，不值得追——实际挑出来用的那一两百个手工标即可。

**目录的用途是让这一万三千个变得可翻**，不是自动打标签——见上面视觉模型那一节。这条小街真正会用到的大概一两百个，那些手工标又快又准。以前的做法是在脚本里手写数字索引，没人知道 195 号是什么，于是咖啡馆里摆了个立式衣柜当桌子、拿一丛灌木当书架。

## 几次事故与教训

**共享容器执行了改到一半的迁移文件。** 一个中间版本里 `town_fact` 排在 `town_npc_knowledge` 前面，撞上外键；MySQL 的 DDL 不是事务性的，六张表已删、迁移记为失败，此后 Flyway 拒绝启动应用，所有请求 500，登录接口挂掉。修法是清掉 `flyway_schema_history` 里那条失败记录。
→ 碰 `db/migration/` 的改动要一次成型再落盘，不要在仓库里迭代。纯删表的迁移用 `SET FOREIGN_KEY_CHECKS = 0` 包住，不依赖人排对顺序。

**"curl /town 返回 200"是个假的验收项。** 那是 Vite 发的静态页面，后端挂了它照样 200。于是我一路都在说隧道正常，其实后端已经死了。
→ 改后端必须打一个真实的后端接口。不要设计一个永远不会失败的检查项，然后用它证明系统是好的。

**有 agent 执行了 `git checkout`，尽管每个 prompt 都写了不许碰 git。** 结果是工作目录被切回 master，之后几个提交落错了分支，还有一个提交掉队在分支上没跟过来。
→ 每次提交前先 `git branch --show-current` 核对，不要假设分支没变。

**有 agent 用 `kill -9` 杀了别人的测试进程。** 它在等自己的后台测试，误判另一组进程是自己的。
→ 并行 agent 共用一个工作目录，prompt 里要明说"这里还有别人在跑测试，等它或者用 `-Dtest=` 只跑你要的"。

**不带 `clean` 的 `mvn test` 会跑到已删除测试的残留 class 文件**，`target/test-classes` 里的旧产物不会自动消失。
→ 验收一律用 `./mvnw -q clean test`。

## 并行 agent 的现实约束

**多个 agent 在跑的时候不要 `git add -A`。** 它们共用同一个工作目录，`-A` 会把别人做到一半的文件一起扫进提交——中途状态的代码进了 master，和当初那次半成品迁移被共享容器执行是同一类事故。提交前先 `git branch --show-current`，然后**只显式列出自己要提交的路径**，`git status --porcelain` 确认暂存区里没有别人的东西再提交。

**并行 agent 共用同一个工作目录**，所以做不到真正的一人一分支，只能一批一分支。能真正并行的是互不相交的层：后端 domain / 前端视觉基础 / 前端场景 / 脚本与素材 / 文档。同时开三到四个比较稳。

后端 domain 那几件事（地点模型、居民并入）都改同一批文件，拆给两个 agent 只会互相覆盖，必须是一个 agent 的一整件事。

git 提交由我统一做，agent 只改文件不碰 git。
