# AI API 设计

基础路径：`/api/v1/ai`。所有接口使用 Cookie 会话；写请求携带 `X-CSRF-Token`。AI 文本只按纯文本渲染。

## 对话

### 创建会话

`POST /sessions`

```json
{ "scene": "STUDY" }
```

场景支持 `STUDY`、`FITNESS`、`CAREER`、`EMOTIONAL_SUPPORT`。响应返回 `publicId`、`scene` 和 `status`。

### 流式消息

`POST /sessions/{sessionId}/messages:stream`

请求头必须接受 `text/event-stream`：

```json
{ "message": "请帮我拆解今天的学习任务" }
```

正常事件顺序为：

1. `meta`：会话、消息、模型和风险等级元数据。
2. `delta`：零到多段纯文本增量。
3. `done`：最终消息 ID 和状态。

L2/L3 安全响应使用 `meta -> safety -> done`，不得发送 `delta`。供应商或服务错误使用 `error`，客户端展示手动计划入口。

客户端只允许在响应流开始前遇到 `401` 时刷新 Cookie 并重试一次。开始读取 SSE 后不自动重放，避免重复消息。

### 会话历史

`GET /sessions/{sessionId}/messages`

只返回当前用户拥有的消息，跨用户访问返回 `404`。

## 结构化建议

- `POST /suggestions`：按场景和目标生成可编辑建议集。
- `GET /suggestions/{setId}`：读取未过期建议集。
- `POST /suggestions/{setId}/adopt`：使用 `Idempotency-Key` 将用户确认的建议写入周计划。

建议不会自动修改目标或任务，必须由用户明确采纳。

## 千问适配

后端通过 `QwenProvider` 隔离供应商。`QWEN_PROVIDER=mock` 仅用于本地确定性验证；`QWEN_PROVIDER=qwen` 时调用 OpenAI 兼容的 `/chat/completions`，对话请求使用 `stream=true` 并解析服务端事件，模型、超时、基础 URL 和密钥全部由环境变量提供。模型请求默认等待 120 秒，SSE 连接默认保留 130 秒，可分别通过 `QWEN_TIMEOUT` 和 `QWEN_STREAM_TIMEOUT` 调整。缺少有效密钥或仍使用占位值时，应用会在启动阶段拒绝启用真实服务。

硅基流动使用 `QWEN_BASE_URL=https://api.siliconflow.cn/v1`，模型填写平台返回的完整 ID，例如 `Qwen/Qwen3-30B-A3B-Instruct-2507`。阿里云百炼使用 `QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1`。密钥必须与基础 URL 所属平台匹配。

真实供应商密钥不得写入仓库。结构化输出最多修复一次，无效输出、超时、鉴权失败、限流和非 2xx 响应统一映射为可恢复的 AI 服务错误；错误正文不会回传供应商响应内容。
