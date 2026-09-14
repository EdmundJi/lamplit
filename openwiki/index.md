---
okf_version: "0.2"
---

# Lamplit 项目 Wiki

这是一份以现行源码、测试和实验记录为证据的工程地图。关于多 Agent 小镇，它重点回答「一次居民决策如何穿过 harness」、「谁能改变哪些状态」和「如何只改一个变量做实验」。

## 从这里开始

- [工程快速开始与改动路由](quickstart.md)：本地启动、验证命令，以及「我要改 X，先看哪里」。
- [小镇 Harness 总览](architecture/companion-harness-overview.md)：服务、规则、模型、存储、前端与实验驱动器的稳定边界。
- [一次 Resident Attempt 的完整 Loop](workflows/resident-attempt-loop.md)：两段短事务、事务外模型 I/O 和迟到结果的验证链。
- [状态所有权与并发](architecture/companion-state-ownership-and-concurrency.md)：世界 JSON、记忆文件、SQLite、Pinia 和模型记录各自的权威性。
- [实验 Harness 与消融](testing/companion-experiment-harness-and-ablations.md)：加速运行、产物、指标、纯规则对照与可归因边界。
- [架构热点与渐进拆分](architecture/companion-architecture-hotspots-and-seams.md)：当前耦合点、可保持行为的 seam 和建议验收顺序。

## 按主题浏览

- [架构](architecture/)：系统总图、harness、状态所有权、持久化和拆分接缝。
- [领域概念](concepts/)：小镇、记忆、限知、场景种子与其他业务概念。
- [工作流](workflows/)：居民 loop、trigger 调度、动作提交和用户流程。
- [测试与实验](testing/)：分层验证、实验产物、指标和消融方法。
- [外部集成](integrations/)：模型提供方与对象存储。
- [运行与部署](operations/)：本地开发、配置、容器化与排障。
