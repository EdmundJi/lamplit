# 发布验证报告

验证日期：2026-08-01

## 结果

- 全局门禁：`./scripts/verify-global-flow.sh` 通过。
- 后端：39 项测试通过，0 失败；包含真实 MySQL 8.4 Testcontainers、Flyway V1-V5、认证、计划、执行、洞察、AI、安全、隐私及后台治理。
- 前端：9 个测试文件、15 项 Vitest 测试通过；TypeScript 检查和 Vite 生产构建通过。
- 浏览器：桌面 Chromium 与移动 WebKit 共 10 项 Playwright 测试通过，覆盖 AI SSE 回复和目标鼓励/完成祝贺。
- 可见页面 QA：1440x900 和 390x844 均无横向溢出、元素裁切或控制台错误；长祝贺文案在移动端正常换行。
- API smoke：认证 Cookie/CSRF、三项同意、目标/周计划/任务、状态流转与反转、AI SSE、L3 安全替换、建议幂等采纳、加密 ZIP 导出、用户隔离、注销冷静期及撤销全部通过。
- 基础设施：MySQL、Redis、MinIO 三个 Compose 服务均为 `healthy`；Redis `PING` 返回 `PONG`；MinIO 使用本地 KMS 支持 AES256 服务端加密对象。
- AI 客户端：SSE 在响应流开始前遇到 `401` 时会刷新 Cookie 并仅重试一次；会话创建失败和流错误均能恢复交互状态。
- 情绪反馈：10 类、约 104 条目标与任务语录已接入，包含设定、创建、开始、完成、部分完成、延期和跳过等场景，并避免同场景连续重复。

## 本地入口

- Web：`http://127.0.0.1:5173`
- 后端健康：`http://127.0.0.1:8080/actuator/health`
- MinIO 控制台：`http://127.0.0.1:9001`
- MySQL：`127.0.0.1:3307`
- Redis：`127.0.0.1:6379`

本次验收使用 `QWEN_PROVIDER=mock`，未将真实供应商密钥写入仓库或验证产物。

验收后另行完成硅基流动真实供应商 smoke：模型列表、普通对话、JSON 结构化输出，以及浏览器端注册会话与 SSE 回复均通过。真实密钥仅保存在被 Git 忽略的本机 `.env.local`。
