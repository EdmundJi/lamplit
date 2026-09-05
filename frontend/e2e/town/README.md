# 小镇手动验证脚本

用 Playwright 直接驱动开发服务器，做截图与数值断言（走位速度、坐标序列这类），不是 CI 用的正式测试。

前置：后端在 8081（`scripts/run-local-backend.sh`），前端在 5173（`DEV_API_TARGET=http://127.0.0.1:8081 pnpm dev`），演示账号见 `handoff.md`。

```bash
node frontend/e2e/town/shot-run.mjs     # 走路 / Shift 奔跑 / HUD 开关的位移实测
node frontend/e2e/town/shot-real.mjs    # 真实模型下的 NPC 对话（正文、选项、动作按钮）
node frontend/e2e/town/shot-all.mjs     # 全站页面截图
node frontend/e2e/town/shot-check.mjs   # 改版页面 + 深色 + 窄屏截图
```

截图默认写到脚本里 `OUT` 指定的目录，跑之前按需改成你自己的临时目录。
