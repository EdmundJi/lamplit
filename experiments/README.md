# 实验档案

这个项目往"做实验、可能写论文"的方向走。**凡是会被引用数字的运行都放这里**：真模型跑、纯规则对照、盲扫。
单元测试、浏览器截图、临时调试探针不算，留在各自原处。

## 固定的规矩

- **模型只用 `qwen3.7-flash-2026-07-15`**（带日期的快照，不用浮动别名）；小镇路由默认只走 qwen，不回退到别的模型。
  2026-09-14 之前的档案里有 `qwen3.8-flash`，**不同模型的数字不能放在一起比**。
- 一次对比只改一个变量：代码、模型、种子（worldId）、初始状态补丁，各算一个变量。
- 真模型跑向 DashScope 发数据前要有明确授权。
- 人设、职责里写进去的预期见 [docs/05 盲扫清单](../docs/05-notes.md)，命中清单的规律不算涌现。

## 一个实验一个目录

```
experiments/<YYYY-MM-DD>-<slug>/
  README.md     问题、假设、代码 commit、模型、配置、种子、命令、结论与限制
  data/         原始产物（manifest.json、model-calls.json、world-snapshot.json……），不进 git
  results/      整理后的表、图、摘要（小文件，进 git）
```

`data/` 被 gitignore：体积大（单次可达 20MB），且来自第三方模型的原始输出。README 与 results 进 git，
所以即使 data 丢了也知道当时跑了什么。`AcceleratedTownRunner` 的 `manifest.json` 里 `model: null` 表示纯规则跑。

## 索引

| 目录 | 类型 | 模型 | 一句话 |
|---|---|---|---|
| [2026-09-09-model-short-smoke](2026-09-09-model-short-smoke/) | 真模型冒烟 + 规则样本 | qwen3.8-flash | 加速跑第一次接通真模型 |
| [2026-09-10-witness-memory-cap](2026-09-10-witness-memory-cap/) | 纯规则调参系列（16 次） | 无 | "看见别人"差点淹掉全镇记忆，调目击上限 |
| [2026-09-10-belief-construction](2026-09-10-belief-construction/) | 真模型 6 次 + 规则对照 2 次 | qwen3.8-flash | 信念从哪来、由什么构成 |
| [2026-09-14-v2-rules-acceptance](2026-09-14-v2-rules-acceptance/) | 纯规则 25 人 × 2 天 | 无 | 第二版世界结构能稳定跑两天 |
| [2026-09-14-v2-model-choice-probe](2026-09-14-v2-model-choice-probe/) | 真模型短探针 | qwen3.7-flash-2026-07-15 | 从规则验收存档续跑 32 分钟 |
| [2026-09-14-persona-blind-review](2026-09-14-persona-blind-review/) | 盲扫 | — | 25 份人设里预写的社会预期 |

2026-09-14 从 `/tmp` 抢救归档：同批的 `town-cafe`、`town-explain`、`town-reflex`、`/tmp/town-life-review` 在盘点当中已被清空，**无法恢复**。
