# Lamplit / 成长小镇项目 Wiki 生成简报

这不是一份平均介绍每个 CRUD 模块的产品手册。它首先是给项目维护者和后续编码 Agent 使用的「架构控制面」：让人能快速看清一个多 Agent 小镇为什么运行、某次决策如何穿过 harness，什么代码拥有哪些状态，以及如何只改一个变量做消融实验。

## 语言与写作

- 正文使用简体中文；代码标识符、类名、字段名、命令和固定枚举保持英文。
- 先说结论和边界，再说实现细节。页面应能帮助读者做修改决策，不要只改写目录树。
- 关键结论必须指向精确的现行源码或测试证据；无法证实时明确写「待验证」，不要补齐一个听起来合理的设计。
- 把「当前已实现」、「现行文档已决定但尚未实现」、「我们正在讨论的候选方案」严格分开。

## 证据权威顺序

1. 当前生产源码与对应测试：回答系统现在真正怎样运行。
2. `docs/01-requirements.md`、`docs/02-modules.md`、`docs/04-decisions.md`、`docs/05-notes.md`：回答现行设计意图、已定边界和实测教训。
3. `experiments/*/README.md` 与已提交的 `results/`：回答某个结论是如何量出来的。
4. `progress.md`、`README.md`、`README.zh-CN.md`：回答项目运行和当前进度。
5. `docs/archive/**` 只是历史材料。它可以用来解释设计演变，但绝不能被描述成当前实现或当前计划。

当文档和代码冲突时，明确标注差异：「代码现状」以源码与测试为准，「未实现意图」才引用现行文档。

## 首要覆盖范围

保留必要的产品和运维入口，但优先深入 `backend/src/main/java/com/betterself/growth/town/companion` 与对应测试、前端 companion 模块、地图生成脚本和 `experiments/`。小镇页面不要只写一篇巨型概述，至少建立下列相互链接的专题：

1. **Harness 总览**：区分 trigger selection、reservation、context assembly、model/runtime call、validation、commit、perception fan-out、memory write 和 experiment export。
2. **一次 Resident attempt 的完整 loop**：画出时序图，说明哪些阶段在 `WorldStore.update` 内，哪些模型 I/O 在事务外，返回后哪些 revision/operation/evidence 检查会丢弃结果。
3. **Trigger 与调度目录**：列出 decision、conversation turn/summary、react、consider/occasion、day plan、explain、reflect、venture、promise 等真实工作类型的来源、优先级、冷却、预算与失败语义。
4. **Context 与限知**：说清 perception、persona、body signal、self account、working set、known places/projects、available actions/options 怎样进入模型，什么信息明确不能进入。
5. **动作合法性与世界提交**：区分模型提案、decision option、domain 二次校验、物理结果、事件和记忆；标出目前固定动词菜单和扁平 `WorldObject` 的局限。
6. **事件→感知→记忆→信念**：区分客观 `WorldEvent`、每个居民的 observed/heard/seed/reflection/belief、证据链与 supersession，指出全局事实和主观解释的边界。
7. **状态所有权与并发**：用表格列出 `CompanionWorld`、记忆文件、SQLite 索引、MySQL 世界 JSON、前端 Pinia 投影、模型 transcript 的权威性和写入者。解释「可并行思考，必须串行提交」。
8. **场景种子与居民初始基因**：记录 `ResidentSeed`、`ResidentDuties`、persona、初始关系、知识与 warm start 的现状，把已实现种子与候选的「序章/剧本」系统分开。
9. **实验 harness 与消融**：解释 `AcceleratedTownRunner`、timeline、world snapshot、model calls、metrics、`NormDetector`、纯规则对照、盲扫和当前 manifest 规约。给出哪些变量目前可以独立关闭，哪些仍紧耦合而无法做干净消融。
10. **架构热点与拆分接缝**：客观列出大文件、跨职责方法和可能的接口接缝；候选的 Pi agent runtime、组合式物品与 `AgentAttempt` trace 只能标成候选设计，不得写成已实现。

## 必须维持的概念边界

- **LLM 是提案者，不是世界状态的直接写入者。** 不要把 prompt 约束描述成安全或一致性边界。
- **物理事实、公开言说、个人记忆和个人信念不是同一层状态。**
- **规则下意识行为与模型事后解释是两个阶段。** 规则不应把自己写的动机伪装成居民的想法。
- **用户真实 Todo 内容不进入居民记忆和模型上下文。**
- **现有实验将「作者写入」与「模型选择产生」分开。** 纯规则对照能产生的规律不算涌现。
- **开发者可观测信息不得自动变成居民感知。**

## 图与索引

- 总图仅画稳定边界，不把几百个类堆成一张网。
- 对 harness loop、并行思考/串行提交、事件到记忆传播使用 Mermaid 时序图或流程图。
- 提供一个「我要改 X，应从哪里开始」路由表，至少包括 trigger、prompt/context、action legality、object behavior、memory、conversation、scenario seed、frontend projection、metrics 和 experiment runner。
- 目录与各页之间保持双向链接；相邻概念页不要复制整段相同内容。

## 不要做的事

- 不要读取或复制密钥、`.env.local`、运行时用户数据、居民真实记忆目录、未提交的实验 `data/` 或大型生成资产。
- 不要因为文档提到 OpenTelemetry、Pi、OpenWiki、Spring Modulith 或组合式物品就宣称他们已接入。
- 不要建议在没有中间边界的情况下一次性重写大文件。拆分建议应先指出可保持行为的 seam 和对应验证。
