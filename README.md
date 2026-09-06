# 成长平台

`更好的自己` 是一个基于 Vue 的单页应用，后端为 Spring Boot 模块化单体。MySQL 是权威数据存储，Redis 提供可丢弃的加速，MinIO 提供本地 S3 兼容对象存储。

## 环境要求

- Java 21
- Docker（含 Docker Compose）
- Node.js 22.13 或更新（`packageManager` 锁定的 pnpm 11.9 要求这个版本，Node 20 上会直接崩）
- pnpm 11

## 本地启动

从 `.env.example` 创建被忽略的本地环境文件，并在使用共享或类生产环境前替换示例值。

```bash
cp .env.example .env.local
docker compose --env-file .env.local -f deploy/compose.yaml up -d
```

以本地 profile 运行后端：

```bash
cd backend
set -a
source ../.env.local
set +a
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

运行前端：

```bash
cd frontend
pnpm install
pnpm dev
```

成长小镇页面使用已购买的 LimeZu 像素素材，素材不进入版本库。首次运行前把 `modernexteriors-win.zip` 和 `moderninteriors-win.zip` 放到 `tmp/`，然后生成图集（需要 Pillow）：

```bash
python3 scripts/build-town-assets.py
```

规则与数据映射见 `docs/成长小镇.md`。

## 共享开发服务器

需要把这台机器当成一台大家都能连上来联调、调试的开发服务器时，用这一套——前后端都跑在容器里，
源码从本机挂进去，改完自动生效，其他开发者不需要在自己机器上装 Java 21 或 pnpm。

```bash
scripts/dev-server.sh up      # 启动（首次要下 Maven / pnpm 依赖，慢一次）
scripts/dev-server.sh urls    # 打印发给其他开发者的访问地址
scripts/dev-server.sh logs    # 跟日志，也可以 logs backend
```

它和上面「本地启动」是**二选一**的关系：两套用的是同一批数据卷，数据互通，但端口会打架。
切过去之前先停掉本机的 `pnpm dev` 和 `mvnw spring-boot:run`。

完整说明（端口、远程调试怎么挂、数据怎么重置、给协作者的须知）见 `docs/开发服务器.md`。

## 首位管理员

应用不开放公开的管理员注册入口。要在不存在活跃 `ADMIN` 时创建首位管理员，请在启动后端前设置以下环境变量：

```bash
ADMIN_BOOTSTRAP_EMAIL=admin@example.com
ADMIN_BOOTSTRAP_PASSWORD='replace-with-a-strong-password'
ADMIN_BOOTSTRAP_DISPLAY_NAME=系统管理员
ADMIN_BOOTSTRAP_TIMEZONE=Asia/Shanghai
ADMIN_BOOTSTRAP_MFA_SECRET=
```

如果 `ADMIN_BOOTSTRAP_MFA_SECRET` 为空，后端会生成一个 TOTP 密钥并在服务器日志中打印一次。将该密钥添加到身份验证器应用，登录并完成 MFA，然后移除引导变量。

## 验证

```bash
cd backend && ./mvnw test
cd frontend && pnpm lint && pnpm test --run && pnpm build
docker compose --env-file .env.local -f deploy/compose.yaml config
```

在后端与前端就绪后，运行完整的本地数据流闸门：

```bash
./scripts/verify-global-flow.sh
```

该闸门验证：认证 Cookie 与 CSRF、同意持久化、目标/周计划/任务物化、任务状态转换与撤销、AI SSE 与固定危机替代、建议幂等采纳、ZIP 导出、归属隔离、删除冷静期/取消、数据库不变量、Redis 健康与敏感日志扫描。自动化验收默认使用 `QWEN_PROVIDER=mock`，不会访问外部模型。要让产品实际调用模型服务，请在未提交的 `.env.local` 或服务器环境中设置 `QWEN_PROVIDER=qwen`、匹配的 `QWEN_BASE_URL`、`QWEN_MODEL`、有效的 `QWEN_API_KEY`，以及可选的 `QWEN_TIMEOUT`（默认 120 秒）和 `QWEN_STREAM_TIMEOUT`（默认 130 秒）；占位密钥会被拒绝，真实密钥绝不写入仓库。

后端健康检查地址为 `http://localhost:8080/actuator/health`。Nginx 用作 SPA 边缘服务器时，已配置暴露同一地址并代理 `/api/v1`。
