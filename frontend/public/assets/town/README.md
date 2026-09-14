# 成长小镇素材

这个目录存放小镇页面用到的像素素材，由 `scripts/build-town-assets.py` 从购买的
LimeZu「Modern Exteriors」「Modern Interiors」资源包生成。素材授权允许在商业和开源
项目中使用，但禁止再分发，因此生成结果不进入版本库，原始 zip 也放在被忽略的 `tmp/` 下。

本地生成：

```bash
python3 scripts/build-town-assets.py --exteriors tmp/modernexteriors-win.zip --interiors tmp/moderninteriors-win.zip
```

产物：

- `town-atlas.png` / `town-atlas.json`：建筑模块、地形、道具的 Phaser 图集
- `characters/c01.png` … `c20.png`：预制角色动画表，每帧 32×64；运行产物只保留场景实际读取的前 8 行，避免 26 个完整供应商动画表占用移动端显存
- `emotes.png`：表情气泡动画表

美术署名（授权要求）：LimeZu，https://limezu.itch.io/

陪伴小街另使用已购的 Modern Farm 与 Modern Office Revamped：

```bash
python3 scripts/build-companion-assets.py
```

脚本从 `tmp/Modern_Farm_v1.2.zip` 和 `tmp/Modern_Office_Revamped_v1.2.zip`
提取菜圃、小鸡、学习桌椅与台灯，生成被 Git 忽略的 `companion-atlas.png/json`。
同时生成五位居民的 `characters/c01-actions.png`、`c03-actions.png`、
`c06-actions.png`、`c09-actions.png`、`c12-actions.png`。每格 96×96，
每行 14 帧，依次为写字、喝咖啡、浇水；脚底锚点统一为 (32, 80)。
写字组合原版坐姿、伸手姿态、Office 纸本和 Farm 铅笔，喝咖啡组合原版抬手与
Interiors 杯子，浇水使用 Farm 工具表的真实水流帧。保留每位居民原有的人物外观。
场景中的 emoji 保留模型给出的话题，只统一外层气泡的像素描边、纸色和阴影。

本地可打开 `/harness/companion-art.html` 检查白天、夜晚、动作与对话气泡。
四个资源包均由 LimeZu 制作；页面保留署名，不重新分发原始或生成的授权素材。

25 个外观 = 20 个现成（above）+ 5 个从分层件合成（docs/01「人」）。合成脚本是
`scripts/build-companion-character.py`，从 `moderninteriors-win.zip` 的
`Character_Generator` 分层件（身体/眼睛/发型/衣服/配饰）里选一套帧对齐一致的图层叠加：

```bash
python3 scripts/build-companion-character.py tmp/moderninteriors-win.zip --inspect
python3 scripts/build-companion-character.py tmp/moderninteriors-win.zip --defaults
```

**先跑 `--inspect`**：它打开真实压缩包，打印 `Character_Generator` 下真实的文件夹名，
并检查共享的 56 列帧网格。仓库里的 `scripts/companion-character-specs.json` 固定了五套
经过检查的搭配；`--defaults` 会生成 c21-c25，完整的 `build-town-assets.py` 也会自动调用它。
需要试验新搭配时仍可传 `--spec` JSON（每个角色一条 `{id, body, outfit, hairstyle, accessory, eyes}`）；
`compose()` 在拼层前会先比较每层的真实像素尺寸，任何一层不一致就直接报错退出，
不会悄悄拼出错位的角色（已用合成的假图层验证过这条报错路径，见 PR/会议记录）。

小镇地面同样是生成的，不是手绘的。`scripts/build-town-map.py` 读
`frontend/src/modules/companion/town-layout.json`（陪伴小街建筑/道路坐标的唯一来源，
`companion-scene.ts`/`pathfinding.ts` 也读它）把地面画成一张 Tiled 地图：

```bash
python3 scripts/build-town-map.py [--exteriors tmp/modernexteriors-win.zip] [--preview out.png]
```

同样需要 Pillow 和上面这份已购的 LimeZu Modern Exteriors zip。产物同样被 Git 忽略、不得再分发：

- `maps/town.tmj`：Tiled JSON 地图，`ground`/`paths`/`water`/`fences` 四个瓦片图层，外加一个
  `decor` 物件图层（树、灌木等）
- `maps/town-tiles.png`：实际用到的瓦片图集，已做色调分级——先 HLS 去饱和再混入暖色，
  草地、人行道两类瓦片的平均色再各自对齐回纯色兜底用的 0x96a486（草坪）/0xc5bfa8（步道），
  只加纹理，整体色调和纯色版保持一致

`companion-scene.ts` 的 `buildGround()` 在这两个文件缺失时返回 `undefined`，
`companion-stage.ts` 就会走 `painted`（纯色画地面/道路）分支，因此不跑这个脚本场景也能正常使用。
