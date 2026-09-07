# 小镇信箱接入与交接

## 组件契约

```vue
<script setup lang="ts">
import { ref } from 'vue'
import { TownMailbox } from './social' // TownView 同级；其他目录调整相对路径
const mailbox = ref<InstanceType<typeof TownMailbox> | null>(null)
const letterUnread = ref(0)
</script>

<template>
  <TownMailbox ref="mailbox" @unread-change="letterUnread = $event" />
</template>
```

- 无 props，无 router、Pinia、world bridge 依赖；两个小镇均可挂载。
- `unread-change(count: number)`：首次加载成功及未读数变化后触发。加载失败不冒报 0。
- `refresh(): Promise<void>`：组件 ref 上公开，可在再次打开窗口时刷新；组件挂载自动收信。收信或标读正在进行时跳过重复刷新。
- 外壳负责窗口、关闭、Escape、焦点与返回地图。关闭请用 `v-if` 卸载；不要 KeepAlive / v-show 整个信箱，避免关闭后仍保留私密草稿。
- `FriendsPanel` 已默认渲染信箱，切换“好友聊天”可使用原聊天功能。manifest 保留 `friends` key、`npc:postman` anchor，标题改为“信箱与好友”。外壳邮递员可打开 `friends`，或直接渲染本组件。两个外壳的接线由主 agent 完成。
- 未读事件只有数字，不携带正文。好友消息的未读数与小镇信件不同，请分别命名，勿把信件正文放入邮递员气泡、公共 feed 或跨镇广播。

## 后端契约

共享 `api` 自动加 `/api/v1` 并解包 ApiEnvelope。类型见 `social.types.ts`，读取依据为 `TownSocialController`、`TownLetterService`、`TownConfidantService`。

| 调用 | 请求 / 响应 |
| --- | --- |
| GET `/town/letters` | `{ letters: TownLetter[], unreadCount: number }`；只含已送达信件 |
| POST `/town/letters/{publicId}/read` | 无 body；响应 data 为 null；后端幂等 |
| POST `/town/confidant` | `{ message: string }`；响应 data 为 null；1–2000 字 |

LONG / NOTE / INVITE 分别为树洞长信、居民短笺、活动请柬，均使用同一阅读与标读流程。INVITE 当前只有正文，没有结构化活动 id 或 RSVP 契约；不猜测事件时间、地点，不调用 `/town/events`，活动公告由 `TownEventsBoard` 负责。

写信成功只显示寄出确认，不造即时回信或本地待回信记录。后端无待回信查询 API，因此不承诺刷新后可恢复寄出状态。隔天夜间任务投递后，再收信即可阅读 LONG。客户端不尝试调夜间任务。

store 是每个组件独立的 Vue 内存状态，不持久化，不记录日志，不发奖励、经验或社交同步事件；卸载清除正文与草稿，并忽略未完成请求的后续结果。正文用文本插值呈现，仅在展开时进入 DOM；不渲染 HTML、链接或自动跳转。失败提示不回显服务器错误正文。

## M5 本轮修复已完成（2026-09-06）

此前静态核查发现的五项缺口已按用户授权完成修复，以下为最终交付状态，替代原未修复清单。

1. **已修复：三个面板的离镇按钮。** `immersive/panels/TodayPanel.vue`、`PartnersPanel.vue`、`AiPanel.vue` 已移除 `openFullPage` 调用和显著离镇按钮；今天保留镇内任务操作，伙伴提供面板内刷新。
2. **已修复：伙伴切换失败与并发保护。** `partners/partners.logic.ts::usePartnerProfile/selectPet` 增加 busy 锁、失败提示和可重试路径。成功后应用选择接口返回的 Pet；旧刷新响应不能覆盖已确认选择。面板切换期间禁用切换及互动，失败不发成功 toast；购买、保存也遵守操作锁。
3. **已修复：AI 历史会话错配。** `AiPanel.vue::openSession` 仅在最新请求成功后同时提交 session 和 messages；失败保留原会话及草稿，加载期间禁止发送，多次切换的迟到响应被忽略。
4. **已修复：新会话、创建请求及 SSE 竞态。** AI 使用请求代次和每次流请求独立的 AbortController。新会话、历史切换、停止和卸载会使旧操作失效；迟到的创建结果、SSE 回调、错误和 finally 均不能改写新会话或解除新请求的 busy 状态。停止保留已收到的回复；创建尚未完成时停止会恢复未发送草稿，且不会在创建响应迟到后发起 SSE。
5. **已修复：历史加载及失败恢复。** 历史列表显示加载状态和重试按钮；发送失败且尚无回复时恢复草稿，供用户主动重试，不自动重发。保留正常 SSE、多轮对话、429 提示及停止生成。

今天的看任务、开始、完成、延期（含弹窗确认）均在面板内完成。相关 Vitest 已覆盖这些操作，以及 AI 乱序响应、停止/重置/卸载隔离、伙伴失败重试和旧刷新隔离。

**交付状态：M5 代码与测试已完成，可冻结前端文件。** 本轮修改三个面板及各自测试、`partners/partners.logic.ts`，新增 `partners/partners.logic.test.ts`；未修改 engine、两个外壳或 social 实现。最终浏览器 M4/M5 旅程由主 agent 验收，不将尚未执行的 e2e 记为通过。

**已执行验证：** M5 范围与关联回归共 6 个测试文件、43/43 通过，`vue-tsc --noEmit` 通过。主 agent 另已反馈整合后的 town 单测 551 通过。既有 `today.logic.test.ts` 有生命周期 hook warning，但测试通过且无未处理错误。本次文档状态更新不改代码，不重复运行测试；主 agent 计划执行的 8 条前端 e2e 待验收。

## 本范围验证

```sh
cd frontend
./node_modules/.bin/vitest run src/modules/town/social src/modules/town/immersive/panels/FriendsPanel.test.ts src/modules/town/immersive/panels/manifest.test.ts
```

覆盖加载/空态/报错、三轨筛选及阅读、标已读成功/失败/重试、未读事件、重复请求保护、写信校验与发送、隔天回信刷新、隐私正文转义及折叠隐藏、卸载后隔离，以及 FriendsPanel 与 manifest 回归。不跑 e2e。
