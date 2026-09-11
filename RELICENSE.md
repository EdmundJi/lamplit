# 重新许可：Mulan PSL v2 → Apache License 2.0（已完成，2026-09-11）

## 现状

本仓库整体适用 **Apache License 2.0**，见 [`LICENSE`](LICENSE) 与 [`NOTICE`](NOTICE)。

在 2026-09-11 之前，本仓库适用 **木兰宽松许可证第 2 版（Mulan PSL v2）**。

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

- [x] **griffty73-debug**（提交邮箱 `griffty73@gmail.com`）—— 已书面确认

出处：https://github.com/EdmundJi/lamplit/issues/1
时间：2026-09-11T09:07:39Z
方式：由本人 GitHub 账号 `griffty73-debug` 在本仓库 issue 下评论

原文：

> 我，griffty73（提交记录中的邮箱：griffty73@gmail.com），是 EdmundJi/lamplit（原 asher_ji/personal_study）的贡献者之一。
> 我在此不可撤销地授权：我对该仓库的全部既往贡献，可以从木兰宽松许可证第 2 版（MulanPSL v2）重新许可为 Apache License 2.0。
> 本授权无附加条件，适用于我在本仓库中的所有历史提交。
> 日期：2026-09-11

（原评论中有两处手误：邮箱写成 `griffty73@gmai.com`、仓库名写成 `1amplit`。两处都在自我描述里、不在授权表述里，且评论由本人账号发在本仓库 issue 下，identity 由账号本身确立。）

## 已完成

1. `LICENSE` 换成 Apache License 2.0 原文（取自 GitHub licenses API，未手抄）
2. 新增 `NOTICE`，按 Apache-2.0 惯例保留对 griffty73-debug 的署名
3. 本文件记录同意的时间与出处
