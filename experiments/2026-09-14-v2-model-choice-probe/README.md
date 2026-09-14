# 第二版真模型短探针（2026-09-14）

- **命令起点**：从 `2026-09-14-v2-rules-acceptance/data/town-v2-accepted-rules/world-snapshot.json` 续跑（manifest 里记的是旧 `/tmp` 路径）。
- **模型**：`qwen3.7-flash-2026-07-15`，4 并发，请求 0.02 天。
- **记录**：[progress.md「真模型短探针已执行」](../../progress.md)
- **结果**：24 次逻辑调用（dayplan 8、turn 7、react 5、summary 2、explain 2），20 应用 / 4 拒绝，121,096 input / 2,040 output tokens。
- **限制**：0 条 `decision`，所以没验证 `choiceId`；wire 请求数（含 JSON repair）从产物**无法回溯**，不要补数。
