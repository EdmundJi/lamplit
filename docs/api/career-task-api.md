# 职业任务与等级 API

基础路径：`/api/v1`。所有接口使用 Cookie 会话；写请求携带 `X-CSRF-Token`。

## 职业代码

| `roleCode` | 显示名 | 任务场景 | 默认成长维度 |
| --- | --- | --- | --- |
| `STUDENT` | 学生 | `STUDY` | `KNOWLEDGE` |
| `FITNESS_USER` | 健身用户 | `FITNESS` | `HEALTH` |
| `WORKER` | 打工人 | `CAREER` | `CAREER` |
| `EMOTIONAL_SUPPORT_USER` | 情绪支持用户 | `EMOTIONAL_SUPPORT` | `WELLBEING` |

职业是用户的任务成长身份，与账户权限角色 `USER` / `ADMIN` 无关。

## 随机任务

### 获取当天候选

`GET /task-presets?role=STUDENT`

首次读取某职业时从该职业的 50 条已发布模板中随机抽取 4 条并持久化。同一用户、当地日期和职业再次读取时返回相同 4 条，不消耗刷新次数。

```json
{
  "roleCode": "STUDENT",
  "roleName": "学生",
  "localDate": "2026-08-01",
  "refreshesRemaining": 3,
  "items": [
    {
      "publicId": "60000000000000000000001011",
      "roleCode": "STUDENT",
      "roleName": "学生",
      "name": "轻量：预习一节课程",
      "notes": "只完成最小版本，不追求一次到位；浏览标题、目录和关键概念。",
      "estimatedMinutes": 10,
      "difficulty": 1,
      "plannedLocalTime": "07:30:00",
      "rrule": "FREQ=WEEKLY;BYDAY=MO,WE,FR",
      "dimensionCode": "KNOWLEDGE",
      "dimensionWeight": 10,
      "experienceReward": 2
    }
  ]
}
```

### 换一批

`POST /task-presets/refresh?role=STUDENT`

每日最多刷新 3 次，配额按用户当地日期计算，并由四职业共享。服务端在事务中锁定配额；刷新页面、切换职业或并发请求不能增加次数。刷新会尽量排除当前 4 项。

第 4 次返回 `429 TASK_PRESET_REFRESH_LIMIT`。

## 创建职业任务

`POST /tasks`

从候选创建时，提交 `sourceTemplatePublicId` 和 `roleCode`，同时可编辑模板的其他字段：

```json
{
  "weeklyPlanPublicId": "...",
  "sourceTemplatePublicId": "60000000000000000000001011",
  "roleCode": "STUDENT",
  "title": "轻量：预习一节课程",
  "notes": "只完成最小版本。",
  "estimatedMinutes": 10,
  "difficulty": 1,
  "rrule": "FREQ=WEEKLY;BYDAY=MO,WE,FR",
  "dimensionWeights": { "KNOWLEDGE": 10 },
  "plannedLocalTime": "07:30:00",
  "activeFrom": "2026-08-03",
  "activeUntil": "2026-08-09"
}
```

手工任务可不提交 `sourceTemplatePublicId`。为兼容旧客户端，缺少 `roleCode` 时后端会根据成长维度推断职业。

## 职业等级

`GET /progress/roles`

始终按固定顺序返回四个职业。新用户各职业初始为 1 级、0 经验，等级上限为 10。

1 到 9 级的升级需求依次为：`10, 15, 25, 40, 65, 105, 170, 275, 445`。从第 3 项开始，每一级需求为前两级需求之和。升级时扣除当前等级所需经验并保留溢出经验；到达 10 级后不再升级，当前经验最多累计到 999。

响应中的 `experience` 是当前等级经验，`maxExperience` 是当前等级升级需求（10 级时为 999），`totalExperience` 是用于保证升级、撤销和数据重建一致性的累计总进度。

任务事件响应除原有 `experienceDelta` 外，还返回：

- `roleExperienceDelta`：本次实际计入职业的经验，10 级 999 经验封顶时可能小于任务经验。
- `roleProgress`：更新后的职业等级、当前等级经验、距下一级经验和等级内进度。

撤销完成事件时，按原事件实际计入的 `roleExperienceDelta` 扣回，并支持跨等级回退，不会因封顶而多扣。
