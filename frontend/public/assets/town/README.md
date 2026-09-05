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
