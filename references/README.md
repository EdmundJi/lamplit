# 参考文献

本目录保存本项目讨论中使用的两篇研究论文原文 PDF，作为可随仓库保留的设计资料。下载日期：2026-09-08。PDF 来自 arXiv 指定版本，未修改；原文著作权归原作者/权利人，不因放入本仓库而改用本仓库的软件许可证。

这些是研究依据，不是产品效果证明。当前设计见 [小镇想法](../docs/01-requirements.md)和[代码思路](../docs/02-modules.md)。官方实现仓库另以链接保留，不将其误列为第三篇论文。

## 原文与来源

### Generative Agents: Interactive Simulacra of Human Behavior

- 作者：Joon Sung Park, Joseph C. O’Brien, Carrie J. Cai, Meredith Ringel Morris, Percy Liang, Michael S. Bernstein。
- 发表：UIST 2023。
- 本地原文：[2023-generative-agents.pdf](2023-generative-agents.pdf)。
- 保存版本：arXiv `2304.03442v2`，版本日期 2023-08-06。
- [论文页面](https://arxiv.org/abs/2304.03442v2) · [原始 PDF](https://arxiv.org/pdf/2304.03442v2)。
- 文件大小：11,947,867 字节。
- SHA-256：`1b31e77fb24d25d7598f2c49e955d12a28b95a6dabad34acdac40f44bfb7a139`。

### Humanoid Agents: Platform for Simulating Human-like Generative Agents

- 作者：Zhilin Wang, Yu Ying Chiu, Yu Cheung Chiu。
- 发表：EMNLP 2023 System Demonstrations。
- 本地原文：[2023-humanoid-agents.pdf](2023-humanoid-agents.pdf)。
- 保存版本：arXiv `2310.05418v1`，版本日期 2023-10-09。
- [论文页面](https://arxiv.org/abs/2310.05418v1) · [原始 PDF](https://arxiv.org/pdf/2310.05418v1)。
- 文件大小：1,517,537 字节。
- SHA-256：`1ba7e33e286131186f0d1025dbfcf11aac438ca627648f39ded01c2324986f0f`。

## 怎么读

| 研究 | 优先阅读 | 本项目要验证的启发 |
| --- | --- | --- |
| Generative Agents | §3 行为与交互（含 inner voice）、Figure 2 地图、§4 记忆/反思/规划、§5 环境表达、§7–8 评价与局限 | 内心声音式介入、限知传播、共同历史、空间支持行动；长期陪伴另做验证 |
| Humanoid Agents | 基本需求、情绪、关系亲近程度如何影响计划与交流 | 用户没操作时，基本节律仍能维持生活；先实现少量有可见作用的状态 |

原论文展示了短期社会模拟，不能据此声称已经解决长期记忆、稳定人格、运行成本或产品留存。我们的规则执行、预算、真实任务隔离和后台恢复是面向产品的设计选择。

## 地图参考

参考 Generative Agents 的 Figure 2、§3.2 与 §5：按地点、子区域和可使用物件表达环境，再把计划落实到具体空间。具体设计应用见 [地图](../docs/01-requirements.md#地图)。

本项目借鉴空间职责、日常动线、自然相遇和行动落点，自行设计紧凑布局与美术。后续扩图依据用户是否看得见、愿不愿意停留和能否产生有意义的生活联系评估；不以复刻原图或堆齐全部建筑为目标。

## 官方实现

- [Generative Agents 官方代码](https://github.com/joonspk-research/generative_agents)：查看种子历史、模拟与回放、三人/二十五人基础场景。
- [Humanoid Agents 官方代码](https://github.com/HumanoidAgents/HumanoidAgents)：配合第二篇论文了解实现。

外部仓库内容可能更新。若之后复用具体代码，记录所用 commit、对应文件与许可证；当前仅保存研究链接，未导入其源码或地图资产。

## 相关开源实现

- [AI Town](https://github.com/a16z-infra/ai-town)：受 Generative Agents 启发的应用实现，采用 Convex 和 React/Pixi；不是原论文的官方代码。重点参考其移动轨迹、对话参与状态、异步 Agent 操作和记忆检索。
- 本次阅读版本：`8e05997f2409275669c8344b84a51692e83f3f33`（2026-09-08 检出），[架构说明](https://github.com/a16z-infra/ai-town/blob/8e05997f2409275669c8344b84a51692e83f3f33/ARCHITECTURE.md)。已参考其逐轮操作编号、发言归属和会后记忆思路，自行实现 Java 对话生命周期；未导入 Convex 源码或地图。
- 代码使用 [MIT 许可证](https://github.com/a16z-infra/ai-town/blob/8e05997f2409275669c8344b84a51692e83f3f33/LICENSE)；直接复制或改写其实质代码时保留版权与许可声明。小镇美术仍使用项目已购买的四个 LimeZu 包。

## 文件维护

PDF 使用稳定的“年份＋主题”文件名。更新版本时明确记录新版本、来源与校验值，不静默替换；确需同时引用多个版本时给文件名加版本后缀。

在仓库根目录可检查文件校验值：

```sh
shasum -a 256 references/*.pdf
```

仅将论文和索引提交仓库，不放模型密钥、下载缓存或运行日志。
