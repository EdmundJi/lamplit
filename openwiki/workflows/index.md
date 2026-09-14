# 工作流

- [账户生命周期](account-lifecycle.md) - 说明账户从注册、浏览器会话和入门设置，到数据导出、注销冷静期及最终匿名化的服务端生命周期与安全边界。
- [动作合法性、物理结果与世界提交](action-legality-and-world-commit.md) - 说明居民模型如何从受限的 DecisionOption 提案，经规则层二次校验、移动与占用竞争，最终提交项目、门、服务、物件和记忆等世界结果。
- [AI 引导式规划](ai-guided-planning.md) - 说明经过身份认证的 AI 对话、目标草案和任务建议如何在安全检查与用户确认的边界内协助规划。涵盖 SSE 事件契约、建议采纳的幂等处理、模型提供方配置及关键验证路径。
- [伙伴意图与专注](companion-intents-and-focus.md) - 面向已认证用户的小镇伙伴世界如何加入、推进、提交或取消意图，以及专注会话如何保持与真实 Todo 的隔离。本文同时说明共享前端状态和异步居民模型结果的并发与过期保护。
- [规划、生成、完成与撤销工作](plan-and-complete-work.md) - 说明目标、周计划和任务如何生成可执行的日程，并记录、奖励、过期和撤销一次任务执行。涵盖 API 入口、幂等与并发约束、状态机及关键验证。
- [一次 Resident Attempt 的完整 Loop](resident-attempt-loop.md) - 追踪 ResidentDirector 将一次居民模型尝试拆为短事务 reservation、事务外模型调用和权威回写的时序，并解释 revision、对话 operation、证据、年龄、预算与失败语义如何处理迟到结果。
- [Resident Trigger 与调度目录](resident-trigger-and-scheduling-catalog.md) - 按真实工作类型说明 ResidentDirector 如何从世界状态保留并异步调度居民模型调用，涵盖优先级、公平性、预算、过期和失败语义。
