# 目击记忆上限调参（2026-09-10，纯规则）

- **问题**：让居民"看见别人做事"会不会把记忆淹掉；目击写入需要什么上限。
- **记录**：[docs/05「看见别人，差点把全镇的记忆淹掉」](../../docs/05-notes.md)
- **数据**：`data/town-rules-only{,2,3}`、`data/town-rules4` … `town-rules18`，全部 `model: null`，worldId `accelerated-rule-run`。
- **限制**：纯规则，只说明规则层的记忆增长形状；每次之间改的代码见 docs/05 对应段落，manifest 里没有 commit 号。
