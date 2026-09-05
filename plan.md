# 成长小镇 · 虚拟世界 MVP 规划

> 目标：把「成长小镇」从一条可以走动的像素街景，变成一个**有人住、有事发生、留得下痕迹**的小世界，
> 同时兼顾视觉冲击力与功能性。参考对象：Stanford Generative Agents、[a16z-infra/ai-town](https://github.com/a16z-infra/ai-town)。
>
> 核心主张：**不做大地图，做「一个院子 + 一条街 + 三扇能进的门」。深度 > 广度。**
> AI Town 的地图其实很小，好看是因为每个地点都有事发生。

---

## 0. 现状盘点（基于代码调研，非猜测）

| 维度 | 现状 | 结论 |
| --- | --- | --- |
| 世界形态 | `town.engine.ts` 单条横向基线（`BASELINE_ROW=28`），房屋按 `PLOT_START + index × 336` 一字排开，可走带 = `PLAZA_TOP` ~ `WALK_BOTTOM` | 是**走廊**，不是世界 |
| 居民来源 | `TownService.java:66` = 自己 + 已接受好友；独自升级模式只剩自己 | 没好友 = 空街 |
| NPC | 仅 2 个：小助（GUIDE）、邮递员（POSTMAN） | 本质是「AI 助手入口」和「未读消息入口」的拟人化 |
| NPC 智能 | 后端已有 `TownPersonas` / `TownNpcPerception` / `TownPromiseDetector` / `TownNpcCadence` / `TownReflectionService`；前端有 `npc-schedule.ts`（时段 + 地点 + 活动 + 心情 + 避雨） | **底子很好，但 NPC 之间不互动、不自主** |
| 功能面板 | `immersive/panels/manifest.ts` 已有 9 个模块面板（今天/目标/AI/好友/洞察/属性/伙伴/个人/设置） | 「所有功能简化版进小镇」**其实已经做完了** |
| 面板与地点的绑定 | 9 个面板中只有 4 个填了 `anchor`（today→home、ai→npc:assistant、friends→npc:postman、insights→academy） | 其余 5 个与地点无关，dock 沦为导航栏 |
| 房屋 | `building-kit.ts` 按 level / 主维度 / streak 纯函数生成外立面 | 玩家**改不了一个像素**，没有所有权 |
| 宠物 | `town.engine.ts` 内 grep 不到任何 pet；只存在于 `PartnersPanel.vue` 弹窗 | 宠物**不在世界里** |
| 室内场景 | `interior.scene.ts` + `map-loader.ts`（支持 Tiled）已完成 | 只有「成长学院」用上了 |
| 在场同步 | `presence.ts` 上报 + `TownPresenceClamp` 服务端裁决，走轮询 | 看不到好友实时走动 |

### 最重要的发现

> `town.engine.ts:1320` 的 `enterRoom()` 已支持 `home` / `academy` / `gym` 三个室内场景，
> `frontend/public/assets/town/maps/` 下 `home-living-room.json`、`public-gym.json`、`academy-study.json` **都已存在**。
> 但全项目**没有任何一处调用 `enterRoom`** —— 它是死代码。
>
> **「每个用户自己的房子」已经做了一半，只是没有门。**

---

## 1. 诊断：五个真正的短板

### S1 · 空间是走廊，不是世界
一条直线街，没有拐角、没有视野遮挡、没有分区。**走路速度翻倍后这个问题会更暴露**——
3 秒跑到街尾，然后呢？「移动慢」是症状，「路上没东西」才是病。

### S2 · 世界里没有人在活着
居民只有好友，好友不上线就是空街；两个 NPC 不互相说话、不自主行动。
Stanford / AI Town 的震撼点恰恰是：**你不操作时，世界也在跑。**

### S3 · 功能是「贴」上去的，不是「长」出来的
9 个面板挂在 dock 上，点图标开窗 = 换了皮的导航栏，世界退化成壁纸。

### S4 · 玩家留不下痕迹
房子是数据的纯函数，玩家不能改、不能布置、不能拥有。没有 ownership 就没有归属感。
而最强的情感锚点——宠物——完全不在世界里。

### S5 · 同时在场感缺失
presence 是轮询回放，不是推送；没有任何玩家之间的互动动作。

---

## 2. 定位澄清（一个需要先拍板的决定）

原始想法是「把系统所有功能都做一个简化版放进小镇」。**建议不要全做。**
9 个面板全塞进去的结果就是一个套壳导航栏——现在已经有苗头了。更好的切法是给小镇一个明确定位：

- **小镇 = 情感层 / 仪式感层**：看宠物、看房子长高、听 NPC 说话、看好友在不在。
  每个模块只放**最高频的那一个动作**（今天：开始 / 完成一件事；好友：看有没有新消息；属性：看一眼雷达）。
- **传统页面 = 效率层**：批量管理目标、翻历史、改设置，仍然回列表页。

面板已有的 `fullPage` 字段（跳转 `/today` 等）正是这个思路，顺着它走即可。
否则要长期维护两份等价 UI，两边都做不透。

---

## 3. MVP 方案（按 ROI 排序）

### M1 · 我的家：可进入的私人空间 + 宠物入住 ★最高优先级

**为什么排第一**：工作量最小（死代码接线）、视觉回报最大、且直接把宠物系统与小镇焊死。

- 把 `enterRoom('home')` 接到 UI：走到自家门口 → 门开 → 淡入室内 → 宠物跑过来迎接。
- 房间里放三样可交互物件，各绑一个**已有**能力：
  - **书桌** → 坐下 = 开始今日任务（复用 `TodayPanel` 的动作）
  - **奖状墙 / 属性雷达挂画** → 成就 + 五维属性（**视觉冲击力最高的一块**）
  - **宠物窝** → 喂养 / 互动，复用 `POST /api/v1/partners/{petId}/interact`
- 宠物 sprite 在屋里走动、睡觉、跟随玩家。

**这一个 30 秒的体验就是整个 MVP 的 demo 视频。**

涉及文件：`town.engine.ts`（暴露入口 + 门的碰撞热区）、`interior.scene.ts`（家具交互）、
`immersive/world-actions.ts`（注册 `home.enter` / `partners.feed` 等能力）、
`public/assets/town/maps/home-living-room.json`（补家具对象层）。

---

### M2 · 让小镇自己动起来：常驻 NPC + 路遇闲聊

- 新增 3~4 个**有人设、有日程、会互相搭话**的常驻 NPC：咖啡店老板 / 健身教练 / 图书管理员 / 公园老人。
- 直接复用现成的 `npc-schedule.ts`（时段 + 地点 + 活动 + 心情 + 避雨逻辑已经写好）。
- **关键增量：NPC 之间的路遇对话气泡。**
  成本控制：**后端每日预生成一批闲聊语料 + 前端在两个 NPC 相遇时播放气泡**，
  而不是每次相遇都调 LLM。`walkers.ts` 的 `shouldGreet` / `registerGreet` 冷却机制可直接复用。
- 把 `TownNpcPerception.java` 已有的玩家数据感知塞进闲聊里，让玩家路过时听到
  *「听说 XX 昨天连续第 7 天了」* ——**这是 Stanford 小镇最戳人的效果，而零件已经有 90%。**

---

### M3 · 把功能从 dock 挪进建筑（世界即导航）

`world-actions.ts` 的 `anchors` 机制已建好，缺的只是锚点。给每个功能一个能走过去的地点：

| 地点 | 绑定功能 |
| --- | --- |
| 咖啡馆 | 好友 / 群聊 |
| 道场 / 健身房（`public-gym.json` 已存在） | 属性 + 打卡 |
| 图书馆（成长学院，内景已有） | 洞察 + 目标 |
| 公告栏 | 成就 / 排行 |
| 自己的家 | 今天 + 伙伴 + 个人 |

dock 保留但**默认收起**，降级为快捷方式，默认路径是走过去。
**这一步几乎不写新业务代码，是重新绑定已有面板到锚点。**

---

### M4 · 直线街 → L 形

- **不要重写成网格大地图。** 当前引擎是 baseline 单线布局，改网格等于重写 `town.engine.ts`，风险极高。
- 只加**一条纵深支路（住宅巷）+ 一个广场**：
  转角是免费的「发现感」——看不到街尾，就想走过去。
- 收益：解决 S1，且与现有 `collision.ts` / `WALK_APRON` / `PLAZA_TOP` 体系兼容。

---

## 4. 明确不做（MVP 排除项）

| 排除项 | 原因 |
| --- | --- |
| WebSocket 实时多人同步 | 现有轮询 presence 够用，改造成本高，非 MVP 瓶颈 |
| 玩家自由装修 / 家具编辑器 | 先给 3~5 套按等级解锁的**预设房间风格**即可 |
| NPC 向量记忆 + reflection（AI Town 那套） | 太贵；`TownReflectionService` 的日反思已够用 |
| 多区域大地图 | 深度优先，广度是后面的事 |
| 9 个面板全功能化 | 见第 2 节「定位澄清」 |

---

## 5. 执行顺序

1. **M1 家 + 宠物** —— 起手式，单独就能出 demo
2. **M3 功能挂锚点** —— 几乎零业务代码，让世界变成导航
3. **M2 常驻 NPC + 路遇闲聊** —— 世界「活」起来的关键
4. **M4 L 形街道** —— 空间层面收尾

---

## 附：本次已完成的改动

移动速度翻倍（前后端一致，否则服务端 presence 校验会把跑动位置夹回去）：

| 位置 | 原值 | 现值 |
| --- | --- | --- |
| `frontend/src/modules/town/walkers.ts` `WALK_SPEED` | 56 | **112** px/s |
| `frontend/src/modules/town/walkers.ts` `RUN_SPEED` | 132 | **264** px/s |
| `frontend/src/modules/town/interior.scene.ts` `DEFAULT_SPEED`（室内） | 90 | **180** px/s |
| `backend/.../town/TownPresenceClamp.java` `RUN_SPEED_PX_PER_SEC` | 132 | **264** |

同步更新：`docs/成长小镇.md`、`docs/成长小镇-接口约定.md`。
验证：前端 town 模块 341 个测试全通过，后端 `TownPresenceClampTest` 通过。
