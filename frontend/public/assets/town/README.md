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
- `characters/c01.png` … `c20.png`：预制角色动画表，每帧 32×64
- `emotes.png`：表情气泡动画表

美术署名（授权要求）：LimeZu，https://limezu.itch.io/

陪伴小街另使用已购的 Modern Farm 与 Modern Office Revamped：

```bash
python3 scripts/build-companion-assets.py
```

脚本从 `tmp/Modern_Farm_v1.2.zip` 和 `tmp/Modern_Office_Revamped_v1.2.zip`
提取菜圃、小鸡、学习桌椅与台灯，生成被 Git 忽略的 `companion-atlas.png/json`。
四个资源包均由 LimeZu 制作；页面保留署名，不重新分发原始或生成的授权素材。
