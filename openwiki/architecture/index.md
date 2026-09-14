# 架构

- [小镇架构热点与渐进拆分接缝](companion-architecture-hotspots-and-seams.md) - 基于现有模拟、编排、状态存储和模型适配代码，说明保持现有行为时可先抽取的边界、契约与回归测试。重点是以权威状态、提交语义和模型输入输出校验为护栏的渐进拆分，而非重写。
- [小镇 Harness 总览与稳定边界](companion-harness-overview.md) - 说明 HTTP/poll 如何推进小镇世界，并划清规则模拟、异步居民心智、持久化记忆、前端投影与加速实验 Harness 的职责和一致性边界。
- [小镇状态所有权、持久化与并发提交](companion-state-ownership-and-concurrency.md) - 说明 CompanionWorld、居民记忆文件、SQLite 索引、前端 Pinia 与模型相关记录各自的权威边界、恢复语义和并发控制。覆盖并行模型思考、串行保留与提交，以及跨存储非原子操作的运维影响。
- [持久化与 API 契约](persistence-and-api-contracts.md) - 说明后端如何划分 MySQL、文件、对象存储与可丢弃缓存的权威性，以及 API 信封、幂等性和定时任务如何维持所有权、可恢复性与生命周期边界。
- [系统概览与运行时边界](system-overview.md) - 说明 Lamplit 的 Vue/Pinia 单页前端、Spring Boot 模块化单体、API 与持久化边界，以及小镇陪伴功能的并发和生命周期约束。
