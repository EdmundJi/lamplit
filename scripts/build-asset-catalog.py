#!/usr/bin/env python3
"""Build a machine-readable catalog of the LimeZu 32x32, shadowed "singles" art
used (or usable) by the town, plus human-scannable contact sheets.

This is *infrastructure*, not an atlas: it does not touch town-atlas.png/json
or companion-atlas.png/json and does not change how build-town-assets.py or
build-companion-assets.py pick frames. It exists so nobody has to guess again
what bare index 195 or 339 points to (see git history: a wardrobe got used as
a desk, a bush as a bookshelf).

Scope, decided by hand after inspecting all four zips (see the accompanying
report for the full audit):

  - Resolution: 32x32 only (each pack also ships 16x16 and 48x48 -- skipped).
  - Shadow variant: exactly one "default" singles tree per pack, the one the
    existing build scripts already draw from. Modern Interiors ships three
    parallel variants (Theme_Sorter_Singles_32x32 / *_Shadowless_Singles_32x32
    / *_Black_Shadow_Singles_32x32); only the first (with shadow) is scanned.
    The other three packs ship exactly one 32x32 singles tree each.
  - Redundant flat re-packagings are skipped: Modern Exteriors'
    Modern_Exteriors_Complete_Singles_32x32 was verified byte-identical (MD5)
    to a subset of ME_Theme_Sorter_32x32, and Modern Farm's
    0_Complete_Tileset_Singles_32x32 heavily overlaps the pack's other themed
    Single_Files_32x32 subfolders -- both are dropped in favour of the themed
    trees, which also hand us theme-from-path for free.

Run with Pillow available (the system python3 may not have it):

    uv run --with pillow python scripts/build-asset-catalog.py

Output (gitignored, alongside the other generated town assets):
    frontend/public/assets/town/catalog/asset-catalog.json   the catalog
    frontend/public/assets/town/catalog/summary.json          counts by pack/theme/scene
    frontend/public/assets/town/catalog/index.html             human front door: "I want a
                                                                 desk, which sheet(s) do I open?"
    frontend/public/assets/town/catalog/contact-sheets/*.png  browsable contact sheets (also
                                                                fine to hand to a vision model,
                                                                but see report: open-ended visual
                                                                classification on this art was
                                                                measured at 38% accuracy, so a
                                                                model pass is not the primary use)
    frontend/public/assets/town/catalog/contact-sheets/index.json  sheet -> item grid layout

Note on scope: this catalog does not auto-label the 13k items. It exists so a
person can *browse* them (start at index.html) and hand-pick and hand-tag the
~100-200 the town actually ends up using. affordance fields (sittable /
sleepable / surface / wallMountable) are deliberately left unset for exactly
that hand-tagging pass -- see empty_affordance() below.
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

try:
    from PIL import Image, ImageDraw, ImageFont
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: uv run --with pillow python scripts/build-asset-catalog.py")

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "frontend" / "public" / "assets" / "town" / "catalog"
SHEETS_DIR = OUT / "contact-sheets"
SCHEMA_VERSION = "1.0.0"

# --- pack source definitions -------------------------------------------------
# Only these directory trees are scanned. Everything else in these zips (other
# resolutions, other shadow variants, "Complete_*" flat re-packagings, sheet
# files, character generators, autotiles, animations, RPG Maker exports, ...)
# is out of scope for this catalog by construction.
PACKS = {
    "modern_exteriors": {
        "zip": "tmp/modernexteriors-win.zip",
        "root": "Modern_Exteriors_32x32/ME_Theme_Sorter_32x32",
        "exclude_dirs": {"Old_Sorting"},
        "flat_theme": None,
    },
    "modern_interiors": {
        "zip": "tmp/moderninteriors-win.zip",
        "root": "1_Interiors/32x32/Theme_Sorter_Singles_32x32",
        "exclude_dirs": set(),
        "flat_theme": None,
    },
    "modern_farm": {
        "zip": "tmp/Modern_Farm_v1.2.zip",
        "root": "32x32/Single_Files_32x32",
        "exclude_dirs": {"0_Complete_Tileset_Singles_32x32"},
        "flat_theme": None,
    },
    "modern_office": {
        "zip": "tmp/Modern_Office_Revamped_v1.2.zip",
        "root": "4_Modern_Office_singles/32x32",
        "exclude_dirs": set(),
        # Office ships no per-theme subfolders at all -- every single is one flat theme.
        "flat_theme": "office",
    },
}

# --- coarse "性质" (kind) inferred from the theme a pack's own folder layout
# already sorted the sprite into. This is a *path*-derived lookup, not a
# per-image visual judgement -- same spirit as computing geometry: cheap,
# deterministic, and reproducible. Themes we don't expect to use (hospital,
# jail, military, graveyard, ...) still get geometry, but their category
# mapping is a best-effort guess, not something anyone should trust blindly.
THEME_CATEGORY = {
    # modern_exteriors
    "terrains_and_fences": "ground",
    "city_terrains": "ground",
    "city_props": "prop",
    "generic_building": "wall",
    "floor_modular_building": "wall",
    "garage_sales": "prop",
    "villas": "wall",
    "worksite": "prop",
    "shopping_center_and_markets": "furniture",
    "vehicles": "vehicle",
    "camping": "prop",
    "hotel_and_hospital": "furniture",
    "school": "furniture",
    "swimming_pool": "prop",
    "police_station": "furniture",
    "office": "furniture",
    "garden": "plant",
    "fire_station": "furniture",
    "graveyard": "decor",
    "subway_and_train_station": "prop",
    "beach": "prop",
    "post_office": "furniture",
    "military_base": "prop",
    "additional_houses": "wall",
    # modern_interiors
    "living_room": "furniture",
    "bathroom": "furniture",
    "bedroom": "furniture",
    "classroom_and_library": "furniture",
    "music_and_sport": "furniture",
    "art": "furniture",
    "gym": "furniture",
    "fishing": "prop",
    "birthday_party": "decor",
    "halloween": "decor",
    "kitchen": "furniture",
    "conference_hall": "furniture",
    "basement": "furniture",
    "christmas": "decor",
    "grocery_store": "furniture",
    "jail": "furniture",
    "hospital": "furniture",
    "japanese_interiors": "furniture",
    "clothing_store": "furniture",
    "museum": "furniture",
    "television_and_film_studio": "furniture",
    "ice_cream_shop": "furniture",
    "shooting_range": "furniture",
    "condominium": "furniture",
    # modern_farm
    "crops": "plant",
    "fences": "wall",
    "fruit_trees": "plant",
    "pickup_items": "prop",
    "props_and_buildings": "prop",
    "trees": "plant",
}

# category -> default isObstacle. "decor" is deliberately left ambiguous
# (could be a floor decoration that blocks a tile, or a wall-mounted picture
# that doesn't) rather than guessed either way.
CATEGORY_IS_OBSTACLE = {
    "ground": False,
    "wall": True,
    "furniture": True,
    "plant": True,
    "prop": True,
    "vehicle": True,
    "decor": None,
    "unknown": None,
}

# Rough hints from theme to the current scenes 01-requirements.md names
# (卧室/客厅/厨房/浴室/咖啡馆/书房/花园/城市道具). This is a convenience index for
# reporting and future scene-building, not a claim that every item in, say,
# "kitchen" belongs in the café -- it's the closest available theme.
SCENE_THEME_HINTS = {
    "bedroom": ["卧室"],
    "living_room": ["客厅", "咖啡馆"],
    "kitchen": ["厨房", "咖啡馆"],
    "bathroom": ["浴室"],
    "office": ["书房"],
    "classroom_and_library": ["书房"],
    "garden": ["花园"],
    "trees": ["花园"],
    "fruit_trees": ["花园"],
    "crops": ["花园"],
    "city_props": ["城市道具"],
    "city_terrains": ["城市道具"],
    "terrains_and_fences": ["城市道具"],
    "floor_modular_building": ["咖啡馆"],
}


def theme_slug(dirname: str) -> str:
    """'19_Hospital_SIngles_32x32' -> 'hospital'; '6_Music_and_Sport_32x32' -> 'music_and_sport'."""
    name = re.sub(r"^\d+_", "", dirname)
    name = re.sub(r"(?i)_singles", "", name)
    name = re.sub(r"_32x32$", "", name)
    name = name.strip("_")
    return name.lower() or "misc"


def slug_component(text: str) -> str:
    text = text.strip()
    text = re.sub(r"[^A-Za-z0-9]+", "_", text)
    text = re.sub(r"_+", "_", text).strip("_")
    return text.lower()


def short_label(stem: str) -> str:
    """A shorter, human-friendlier label for contact sheets: strip the
    theme-name-and-resolution prefix LimeZu bakes into every filename, e.g.
    'Bedroom_Singles_32x32_140' -> '140', 'ME_Singles_City_Props_32x32_Barrel_10' -> 'Barrel_10'."""
    stem = stem.strip()
    m = re.search(r"32x32_(.+)$", stem, flags=re.IGNORECASE)
    if m and m.group(1).strip("_ "):
        return m.group(1).strip("_ ")
    return re.sub(r"_32x32$", "", stem, flags=re.IGNORECASE).strip("_ ") or stem


def iter_pack_pngs(zf: zipfile.ZipFile, pack_id: str, cfg: dict):
    root = cfg["root"].rstrip("/") + "/"
    exclude_dirs = cfg["exclude_dirs"]
    flat_theme = cfg["flat_theme"]
    for info in zf.infolist():
        name = info.filename
        if not name.startswith(root) or not name.lower().endswith(".png"):
            continue
        rel = name[len(root):]
        parts = rel.split("/")
        if flat_theme is not None:
            theme = flat_theme
        else:
            if len(parts) < 2:
                continue  # a PNG sitting directly under root with no theme subfolder
            top_dir = parts[0]
            if top_dir in exclude_dirs:
                continue
            theme = theme_slug(top_dir)
        stem = Path(parts[-1]).stem
        yield name, theme, stem


def compute_geometry(im: Image.Image) -> dict:
    im = im.convert("RGBA")
    w, h = im.size
    bbox = im.getbbox()
    if bbox is None:
        return {
            "canvas": {"w": w, "h": h},
            "bbox": None,
            "groundContactWidth": 0,
            "groundContactOpaquePixels": 0,
            "groundContactRow": None,
            "footprintTiles": {"w": 0, "h": 0},
        }
    x0, y0, x1, y1 = bbox  # x1, y1 exclusive
    alpha = im.split()[-1]
    bottom_row_y = y1 - 1
    opaque_xs = [x for x in range(x0, x1) if alpha.getpixel((x, bottom_row_y)) > 0]
    if opaque_xs:
        span = max(opaque_xs) - min(opaque_xs) + 1
        count = len(opaque_xs)
    else:
        span, count = 0, 0
    bw, bh = x1 - x0, y1 - y0
    return {
        "canvas": {"w": w, "h": h},
        "bbox": {"x0": x0, "y0": y0, "x1": x1, "y1": y1, "w": bw, "h": bh},
        "groundContactWidth": span,
        "groundContactOpaquePixels": count,
        "groundContactRow": bottom_row_y,
        "footprintTiles": {"w": max(1, -(-bw // 32)), "h": max(1, -(-bh // 32))},
    }


def classify(theme: str) -> dict:
    category = THEME_CATEGORY.get(theme, "unknown")
    return {
        "category": category,
        "isObstacle": CATEGORY_IS_OBSTACLE.get(category, None),
        "confidence": "theme_heuristic" if category != "unknown" else "unset",
    }


def empty_affordance() -> dict:
    # Deliberately left unset. A keyword-guessed "sittable"/"surface" here
    # would repeat the exact mistake this catalog exists to prevent (see
    # module docstring: a wardrobe read as a desk). Filled in later by a
    # human or a vision-model pass over the contact sheets, never guessed
    # from a filename substring.
    return {
        "sittable": None,
        "sleepable": None,
        "surface": None,
        "wallMountable": None,
        "source": "unfilled",
        "notes": None,
    }


def build_items(zip_paths: dict) -> list[dict]:
    items = []
    seen_ids: dict[str, int] = {}
    for pack_id, cfg in PACKS.items():
        zip_path = zip_paths[pack_id]
        if not zip_path.exists():
            print(f"  ! {pack_id}: {zip_path} not found, skipping pack", file=sys.stderr)
            continue
        zf = zipfile.ZipFile(zip_path)
        entries = sorted(iter_pack_pngs(zf, pack_id, cfg), key=lambda e: e[0])
        print(f"  {pack_id}: {len(entries)} singles ({zip_path.name})")
        for zpath, theme, stem in entries:
            base_id = f"{pack_id}__{theme}__{slug_component(stem)}"
            n = seen_ids.get(base_id, 0) + 1
            seen_ids[base_id] = n
            item_id = base_id if n == 1 else f"{base_id}__{n}"
            with zf.open(zpath) as fh:
                im = Image.open(io.BytesIO(fh.read()))
                im.load()
            geometry = compute_geometry(im)
            classification = classify(theme)
            items.append({
                "id": item_id,
                "pack": pack_id,
                "theme": theme,
                "sourceZip": str(cfg["zip"]),
                "sourcePath": zpath,
                "sourceFile": Path(zpath).name,
                "shortLabel": short_label(stem),
                "resolution": "32x32",
                "shadowVariant": "with_shadow",
                "geometry": geometry,
                "classification": classification,
                "affordance": empty_affordance(),
                "sceneHints": SCENE_THEME_HINTS.get(theme, []),
                "tags": [],
                "contactSheet": None,  # filled in by build_contact_sheets()
            })
    return items


# --- contact sheets -----------------------------------------------------------
# Canvas sizes across these packs range from 16x16 single props all the way to
# 896x1088 composited multi-tile buildings (e.g. Garden's "Palace_Example"), so
# icons are scaled to *fit* a fixed box rather than zoomed by a flat factor --
# a flat zoom is what produced the first, broken version of these sheets
# (tall wardrobes/curtains overran their cell and smeared into the next row).
ICON_BOX = 112           # max icon width/height inside a cell, in px
MAX_UPSCALE = 4.0        # cap zoom for tiny 16x16 icons so they don't dominate the sheet
CELL_W, CELL_H = 140, 168
ICON_AREA_H = 128        # vertical space reserved for the icon; label sits right below it
COLS = 10
ROWS_PER_PAGE = 24        # 10*24 = 240 items/sheet: legible, and comfortably under the
                          # 600-images-per-request / 8192px-edge vision budget even if
                          # every sheet in a pack were sent at once.
BG = (235, 235, 235, 255)
CARD_BG = (255, 255, 255, 255)
BORDER = (200, 200, 200, 255)
BBOX_COLOR = (220, 30, 30, 255)


def fit_resize(im: Image.Image, box: int):
    scale = min(box / im.width, box / im.height, MAX_UPSCALE)
    resample = Image.NEAREST if scale >= 1 else Image.LANCZOS
    w, h = max(1, round(im.width * scale)), max(1, round(im.height * scale))
    return im.resize((w, h), resample), scale

FONT_CANDIDATES = [
    "/System/Library/Fonts/Supplemental/Arial.ttf",
    "/System/Library/Fonts/Supplemental/Andale Mono.ttf",
]


def load_font(size: int):
    for path in FONT_CANDIDATES:
        if Path(path).exists():
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def natural_key(item: dict):
    # Sort by the numeric tail of shortLabel when present, else alphabetically,
    # so e.g. Bedroom 2 sorts before Bedroom 10 on the sheet.
    label = item["shortLabel"]
    m = re.search(r"(\d+)$", label)
    return (label[: m.start()] if m else label, int(m.group(1)) if m else -1, item["sourceFile"])


def build_contact_sheets(zip_paths: dict, items: list[dict]) -> list[dict]:
    SHEETS_DIR.mkdir(parents=True, exist_ok=True)
    for f in SHEETS_DIR.glob("*.png"):
        f.unlink()

    open_zips = {pid: zipfile.ZipFile(p) for pid, p in zip_paths.items() if p.exists()}
    font_label = load_font(13)
    font_index = load_font(11)

    by_group: dict[tuple[str, str], list[dict]] = {}
    for it in items:
        by_group.setdefault((it["pack"], it["theme"]), []).append(it)

    sheet_index = []  # for contact-sheets/index.json
    for (pack_id, theme), group_items in sorted(by_group.items()):
        group_items.sort(key=natural_key)
        chunk_size = COLS * ROWS_PER_PAGE
        pages = [group_items[i:i + chunk_size] for i in range(0, len(group_items), chunk_size)]
        for page_no, page_items in enumerate(pages, start=1):
            rows = -(-len(page_items) // COLS)
            sheet_w = COLS * CELL_W
            sheet_h = rows * CELL_H
            sheet = Image.new("RGBA", (sheet_w, sheet_h), BG)
            draw = ImageDraw.Draw(sheet)
            file_name = f"{pack_id}__{theme}__p{page_no:02d}.png"
            grid = []
            for i, it in enumerate(page_items):
                col, row = i % COLS, i // COLS
                cx, cy = col * CELL_W, row * CELL_H
                draw.rectangle([cx + 2, cy + 2, cx + CELL_W - 3, cy + CELL_H - 3], fill=CARD_BG, outline=BORDER)
                zf = open_zips[it["pack"]]
                with zf.open(it["sourcePath"]) as fh:
                    im = Image.open(io.BytesIO(fh.read())).convert("RGBA")
                icon, scale = fit_resize(im, ICON_BOX)
                icon_x = cx + (CELL_W - icon.width) // 2
                icon_y = cy + 4 + (ICON_AREA_H - icon.height) // 2
                sheet.alpha_composite(icon, (icon_x, icon_y))
                bbox = it["geometry"]["bbox"]
                if bbox:
                    rx0 = icon_x + bbox["x0"] * scale
                    ry0 = icon_y + bbox["y0"] * scale
                    rx1 = icon_x + bbox["x1"] * scale
                    ry1 = icon_y + bbox["y1"] * scale
                    draw.rectangle([rx0, ry0, rx1 - 1, ry1 - 1], outline=BBOX_COLOR)
                label = it["shortLabel"]
                if len(label) > 16:
                    label = label[:15] + "…"
                text_y = cy + 4 + ICON_AREA_H + 2
                draw.text((cx + CELL_W // 2, text_y), label, fill=(20, 20, 20, 255), font=font_label, anchor="ma")
                draw.text((cx + CELL_W // 2, text_y + 16), f"#{i}", fill=(120, 120, 120, 255), font=font_index, anchor="ma")
                it["contactSheet"] = {"file": f"contact-sheets/{file_name}", "page": page_no, "index": i, "col": col, "row": row}
                grid.append(it["id"])
            sheet.save(SHEETS_DIR / file_name)
            sheet_index.append({
                "file": file_name,
                "pack": pack_id,
                "theme": theme,
                "page": page_no,
                "pages": len(pages),
                "cols": COLS,
                "cellW": CELL_W,
                "cellH": CELL_H,
                "width": sheet_w,
                "height": sheet_h,
                "items": grid,
            })
    return sheet_index


# --- human index page ---------------------------------------------------------
# The contact sheets themselves are for a person: build_contact_sheets() was
# already tuned for that (icons fit-scaled to a legible box, bbox drawn in red,
# a number under each). What was missing is a *front door* -- something that
# answers "I want a desk, which sheet(s) do I even open?" without grepping
# theme folder names. FINDER below is a hand-curated pointer from common
# 找家具 queries to the pack/theme(s) most likely to have it, built by skimming
# the theme names and a few sheets by eye -- it is a shortcut for a person
# doing the picking, not a claim about what's actually usable in the town, and
# not something to feed back into `classification` or `affordance`.
FINDER: list[tuple[str, list[str]]] = [
    ("桌子 / 书桌", ["modern_interiors/living_room", "modern_interiors/kitchen",
                    "modern_interiors/classroom_and_library", "modern_interiors/conference_hall",
                    "modern_office/office", "modern_farm/props_and_buildings"]),
    ("椅子 / 凳子", ["modern_interiors/living_room", "modern_interiors/kitchen",
                    "modern_interiors/classroom_and_library", "modern_interiors/conference_hall",
                    "modern_office/office"]),
    ("床", ["modern_interiors/bedroom", "modern_interiors/condominium"]),
    ("书架 / 书柜", ["modern_interiors/classroom_and_library", "modern_interiors/basement",
                    "modern_interiors/living_room"]),
    ("沙发 / 单人椅", ["modern_interiors/living_room", "modern_interiors/condominium"]),
    ("收纳柜 / 衣柜", ["modern_interiors/bedroom", "modern_interiors/basement",
                     "modern_interiors/clothing_store"]),
    ("灯具", ["modern_interiors/living_room", "modern_interiors/bedroom", "modern_office/office"]),
    ("厨房台面 / 灶具 / 咖啡吧台", ["modern_interiors/kitchen", "modern_interiors/grocery_store",
                                "modern_interiors/ice_cream_shop"]),
    ("浴室洁具", ["modern_interiors/bathroom"]),
    ("植物 / 花草 / 盆栽", ["modern_exteriors/garden", "modern_farm/trees",
                          "modern_farm/fruit_trees", "modern_farm/crops"]),
    ("户外地面 / 篱笆 / 围栏", ["modern_exteriors/terrains_and_fences", "modern_farm/fences"]),
    ("街道杂物（信箱、路灯、垃圾桶……）", ["modern_exteriors/city_props", "modern_exteriors/city_terrains"]),
    ("建筑外墙 / 屋顶模块", ["modern_exteriors/floor_modular_building", "modern_exteriors/generic_building"]),
]


def build_index_page(items: list[dict], sheet_index: list[dict], summary: dict) -> str:
    theme_sheets: dict[str, list[dict]] = {}
    for s in sheet_index:
        theme_sheets.setdefault(f"{s['pack']}/{s['theme']}", []).append(s)
    for v in theme_sheets.values():
        v.sort(key=lambda s: s["page"])

    theme_categories: dict[str, dict[str, int]] = {}
    for it in items:
        key = f"{it['pack']}/{it['theme']}"
        cats = theme_categories.setdefault(key, {})
        cat = it["classification"]["category"]
        cats[cat] = cats.get(cat, 0) + 1

    def esc(s: str) -> str:
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    def sheet_links(theme_key: str) -> str:
        sheets = theme_sheets.get(theme_key, [])
        if not sheets:
            return "<em>(无联系表 -- 该主题可能没有产出条目)</em>"
        return " ".join(
            f'<a href="contact-sheets/{esc(s["file"])}">p{s["page"]}</a>' for s in sheets
        )

    finder_rows = []
    for query, theme_keys in FINDER:
        cells = []
        for tk in theme_keys:
            count = summary["byTheme"].get(tk, 0)
            if count == 0:
                continue
            cells.append(f'<code>{esc(tk)}</code> ({count}) {sheet_links(tk)}')
        finder_rows.append(
            f"<tr><td>{esc(query)}</td><td>{'<br>'.join(cells)}</td></tr>"
        )

    theme_rows = []
    for theme_key in sorted(summary["byTheme"]):
        count = summary["byTheme"][theme_key]
        cats = theme_categories.get(theme_key, {})
        cat_str = ", ".join(f"{k}×{v}" for k, v in sorted(cats.items(), key=lambda kv: -kv[1]))
        theme_rows.append(
            f"<tr><td><code>{esc(theme_key)}</code></td><td>{count}</td>"
            f"<td>{esc(cat_str)}</td><td>{sheet_links(theme_key)}</td></tr>"
        )

    return f"""<!doctype html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>素材联系表索引</title>
<style>
body {{ font-family: -apple-system, "PingFang SC", sans-serif; max-width: 980px; margin: 2rem auto; padding: 0 1rem; color: #222; }}
h1 {{ font-size: 1.4rem; }}
h2 {{ font-size: 1.1rem; margin-top: 2.5rem; border-bottom: 1px solid #ddd; padding-bottom: .3rem; }}
table {{ border-collapse: collapse; width: 100%; margin-top: .8rem; }}
td, th {{ border: 1px solid #ddd; padding: .4rem .6rem; text-align: left; vertical-align: top; font-size: .92rem; }}
th {{ background: #f4f4f4; }}
code {{ background: #f0f0f0; padding: .05rem .3rem; border-radius: 3px; font-size: .85rem; }}
.stats {{ color: #555; }}
.note {{ background: #fffbe6; border: 1px solid #f0e0a0; padding: .6rem .9rem; border-radius: 4px; margin: 1rem 0; }}
a {{ text-decoration: none; color: #2563eb; margin-right: .4rem; }}
a:hover {{ text-decoration: underline; }}
</style>
</head>
<body>
<h1>素材联系表索引</h1>
<p class="stats">共 {summary['totalItems']} 条，来自 {len(summary['byPack'])} 个素材包
（{', '.join(f'{esc(k)} {v}' for k, v in summary['byPack'].items())}）。
每张联系表是一页 PNG 缩略图网格，每格一个素材、编号、红框标出内容 bbox。</p>

<div class="note">
这份目录不负责自动选素材——上一版视觉模型自动打标签的准确率只有 38%，这条路第一版走不通。
它的作用是<strong>让一万三千条素材变得可翻</strong>：小街实际会用到的大概一两百个，
从下面按用途或按主题翻到对应联系表，人工挑、人工填 affordance。
</div>

<h2>按用途找（常见家具/道具速查）</h2>
<p class="stats">这是按主题名人工猜的入口，不是保证——猜错/没列到就去下面的完整主题目录翻。</p>
<table>
<tr><th style="width:14em">我想找……</th><th>先去看这些主题的联系表</th></tr>
{"".join(finder_rows)}
</table>

<h2>完整主题目录（{len(summary['byTheme'])} 个主题）</h2>
<table>
<tr><th>pack/theme</th><th>条目数</th><th>性质构成</th><th>联系表（按页）</th></tr>
{"".join(theme_rows)}
</table>

<h2>数据文件</h2>
<ul>
<li><code>asset-catalog.json</code> -- 完整结构化目录，每条含 geometry / classification / affordance（affordance 第一版留空，见下）</li>
<li><code>summary.json</code> -- 本页表格数据来源</li>
<li><code>contact-sheets/index.json</code> -- 联系表到条目 id 的机器可读映射</li>
</ul>
<p class="stats">affordance（能不能坐/睡/放东西/是不是障碍）字段结构已在 schema 里留好位置，
第一版故意留空，不用视觉模型批量猜——等实际用到的那一两百条素材确定后再人工填。</p>
</body>
</html>
"""


def build_summary(items: list[dict]) -> dict:
    by_pack: dict[str, int] = {}
    by_theme: dict[str, int] = {}
    by_category: dict[str, int] = {}
    by_scene: dict[str, int] = {}
    for it in items:
        by_pack[it["pack"]] = by_pack.get(it["pack"], 0) + 1
        key = f"{it['pack']}/{it['theme']}"
        by_theme[key] = by_theme.get(key, 0) + 1
        by_category[it["classification"]["category"]] = by_category.get(it["classification"]["category"], 0) + 1
        for scene in it["sceneHints"]:
            by_scene[scene] = by_scene.get(scene, 0) + 1
    return {
        "totalItems": len(items),
        "byPack": dict(sorted(by_pack.items())),
        "byTheme": dict(sorted(by_theme.items())),
        "byCategory": dict(sorted(by_category.items())),
        "bySceneHint": dict(sorted(by_scene.items())),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--exteriors", default=PACKS["modern_exteriors"]["zip"])
    parser.add_argument("--interiors", default=PACKS["modern_interiors"]["zip"])
    parser.add_argument("--farm", default=PACKS["modern_farm"]["zip"])
    parser.add_argument("--office", default=PACKS["modern_office"]["zip"])
    parser.add_argument("--skip-contact-sheets", action="store_true", help="only write asset-catalog.json (faster iteration)")
    args = parser.parse_args()

    zip_paths = {
        "modern_exteriors": (ROOT / args.exteriors),
        "modern_interiors": (ROOT / args.interiors),
        "modern_farm": (ROOT / args.farm),
        "modern_office": (ROOT / args.office),
    }

    OUT.mkdir(parents=True, exist_ok=True)

    print("Scanning packs...")
    items = build_items(zip_paths)
    print(f"  total: {len(items)} catalog items")

    sheet_index = []
    if not args.skip_contact_sheets:
        print("Building contact sheets...")
        sheet_index = build_contact_sheets(zip_paths, items)
        print(f"  {len(sheet_index)} sheet(s) written to {SHEETS_DIR}")
        (SHEETS_DIR / "index.json").write_text(json.dumps(sheet_index, ensure_ascii=False, indent=2))

    catalog = {
        "schemaVersion": SCHEMA_VERSION,
        "generatedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "generator": {
            "script": "scripts/build-asset-catalog.py",
            "resolution": "32x32",
            "shadowVariant": "with_shadow",
        },
        "packs": {pid: {"zip": str(cfg["zip"]), "root": cfg["root"]} for pid, cfg in PACKS.items()},
        "items": items,
    }
    (OUT / "asset-catalog.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=2))

    summary = build_summary(items)
    (OUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))

    index_html = build_index_page(items, sheet_index, summary)
    (OUT / "index.html").write_text(index_html)

    print(f"Wrote {OUT / 'asset-catalog.json'}")
    print(f"Wrote {OUT / 'summary.json'}")
    print(f"Wrote {OUT / 'index.html'}")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
