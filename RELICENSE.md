# 重新许可：Mulan PSL v2 → Apache License 2.0

## 现状

本仓库目前整体适用 **木兰宽松许可证第 2 版（Mulan PSL v2）**，见 [`LICENSE`](LICENSE)。

它是一份宽松协议（非 copyleft），自带专利授权。本次重新许可不是为了放宽或收紧条款，
而是换成国际上更广泛认识的等价物——Apache License 2.0 是木兰 v2 当初对标设计的对象，
同样带专利授权和防御性专利终止条款。

## 为什么需要征得同意

按存活行数统计（`git blame`，2026-09-11）：

| 贡献者 | backend/src | frontend/src | docs | 合计 |
| --- | ---: | ---: | ---: | ---: |
| 吉育德 | 19,478 | 13,695 | 3,219 | 36,392 |
| griffty73-debug | 17,370 | 8,719 | 1,526 | **27,615（43%）** |

这不是一个薄 fork，是一份合著的代码：`achievement/`、`admin/` 等整个子系统至今一行未改。
两人的代码又混在同一批文件里，**按文件切协议做不到**。

著作权属于各自的作者，因此整体重新许可需要 griffty73-debug 本人的书面同意。

第三位贡献者 `孤城的月光` 仅有一条提交（`0da0d67 add LICENSE.`），内容是木兰协议原文本身，
不构成代码贡献，无需单独征得同意。

## 授权状态

- [ ] **griffty73-debug**（提交邮箱 `griffty73@gmail.com`）—— 待书面确认，见 issue（链接待补）

**在上面这一项被勾选之前，本仓库整体仍然适用 Mulan PSL v2。**
`LICENSE` 不会提前更换。

## 完成后要做的

1. 把 `LICENSE` 换成 Apache License 2.0 原文
2. 新增 `NOTICE`，按 Apache-2.0 惯例保留对 griffty73-debug 的署名
3. 在本文件记录同意的时间与出处链接
