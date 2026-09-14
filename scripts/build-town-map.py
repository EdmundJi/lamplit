#!/usr/bin/env python3
"""Generate the town's ground as a Tiled map from the shared layout.

The layout (frontend/src/modules/companion/town-layout.json) is the one source of where buildings,
homes and walkways are; the scene draws rooms from it and the pathfinder walks it. This script
paints the ground under all of that from the licensed LimeZu Modern Exteriors pack:

    python3 scripts/build-town-map.py --exteriors tmp/modernexteriors-win.zip [--preview out.png]

Output (gitignored, the pack may not be redistributed):
    frontend/public/assets/town/maps/town.tmj        Tiled JSON map: ground, paths, water layers
                                                      and a `decor` object layer of trees/bushes
    frontend/public/assets/town/maps/town-tiles.png  the tiles actually used, colour-graded warm

Tile coordinates below were verified for seamless tiling (6x6 repeats and edge compositions).
Requires Pillow.
"""
from __future__ import annotations

import argparse
import colorsys
import io
import json
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
LAYOUT = ROOT / "frontend/src/modules/companion/town-layout.json"
OUT = ROOT / "frontend/public/assets/town/maps"
T = 32

TERRAIN = "Modern_Exteriors_32x32/ME_Theme_Sorter_32x32/1_Terrains_and_Fences_32x32.png"
CITY = "Modern_Exteriors_32x32/ME_Theme_Sorter_32x32/2_City_Terrains_32x32.png"

PALETTE = {
    "grass": [(TERRAIN, 1, 7), (TERRAIN, 1, 12), (TERRAIN, 9, 7)],
    "sidewalk": [(CITY, 10, 0), (CITY, 9, 1)],
    "herringbone": [(TERRAIN, 27, 10)],
    "cobblestone": [(TERRAIN, 25, 10)],
    "dirt": [(TERRAIN, 1, 61)],
    "fence": {k: (TERRAIN, c, r) for k, (c, r) in {
        "nw": (16, 16), "n": (17, 16), "ne": (18, 16), "w": (16, 17), "e": (18, 17),
        "sw": (16, 18), "s": (17, 18), "se": (18, 18)}.items()},
    "pond": {k: (TERRAIN, c, r) for k, (c, r) in {
        "nw": (0, 35), "n": (1, 35), "ne": (2, 35), "w": (0, 36), "c": (1, 36),
        "e": (2, 36), "sw": (0, 37), "s": (1, 37), "se": (2, 37)}.items()},
}


def hash2(x: int, y: int, salt: int = 0) -> int:
    """Deterministic per-cell choice - the same layout always paints the same map."""
    h = (x * 73856093) ^ (y * 19349663) ^ (salt * 83492791)
    return (h ^ (h >> 13)) & 0x7FFFFFFF


# Mean colour a graded tile is shifted to, so the tiled town keeps the painted fallback's tones
# (companion-stage.ts: lawn 0x96a486, lanes 0xc5bfa8) and only gains texture.
MATCH = {"grass": (150, 164, 134), "sidewalk": (197, 191, 168)}


def grade(tile: Image.Image, offset: list[float] | None = None) -> Image.Image:
    """Pull the pack's bright greens toward the town's warm, muted paper palette, then shift by `offset`."""
    px = tile.convert("RGBA").load()
    out = Image.new("RGBA", (T, T))
    opx = out.load()
    warm = (176, 170, 140)
    for y in range(T):
        for x in range(T):
            r, g, b, a = px[x, y]
            h, l, s = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            r2, g2, b2 = colorsys.hls_to_rgb(h, min(1, l * 0.98 + 0.03), s * 0.55)
            mix = 0.22
            opx[x, y] = (round((r2 * (1 - mix) * 255) + warm[0] * mix),
                         round((g2 * (1 - mix) * 255) + warm[1] * mix),
                         round((b2 * (1 - mix) * 255) + warm[2] * mix), a)
    if offset:
        for y in range(T):
            for x in range(T):
                r, g, b, a = opx[x, y]
                opx[x, y] = tuple(max(0, min(255, round(v + offset[k]))) for k, v in enumerate((r, g, b))) + (a,)
    return out


def mean(tile: Image.Image) -> list[float]:
    px = tile.load()
    solid = [px[x, y] for y in range(tile.height) for x in range(tile.width) if px[x, y][3] > 0]
    return [sum(p[k] for p in solid) / len(solid) for k in range(3)]


class Tileset:
    def __init__(self, zf: zipfile.ZipFile):
        self.zf, self.sheets, self.tiles, self.index = zf, {}, [], {}
        # Lawn and pond shore share one shift so their grass stays the same green.
        self.offsets = {name: [t - m for t, m in zip(target, mean(grade(self.crop(PALETTE[name][0]))))]
                        for name, target in MATCH.items()}

    def crop(self, ref) -> Image.Image:
        sheet, col, row = ref
        if sheet not in self.sheets:
            self.sheets[sheet] = Image.open(io.BytesIO(self.zf.read(sheet))).convert("RGBA")
        return self.sheets[sheet].crop((col * T, row * T, col * T + T, row * T + T))

    def gid(self, ref, tone: str | None = None) -> int:
        if ref not in self.index:
            self.tiles.append(grade(self.crop(ref), self.offsets.get(tone)))
            self.index[ref] = len(self.tiles)
        return self.index[ref]

    def image(self, columns: int = 16) -> Image.Image:
        rows = (len(self.tiles) + columns - 1) // columns
        img = Image.new("RGBA", (columns * T, rows * T))
        for i, tile in enumerate(self.tiles):
            img.paste(tile, ((i % columns) * T, (i // columns) * T))
        return img


def cells(rect: dict, cols: int, rows: int, share: float = 0.4):
    """Cells a pixel rectangle covers by at least `share` of their area (walkways are not grid-aligned)."""
    x0, y0, x1, y1 = rect["x"], rect["y"], rect["x"] + rect["w"], rect["y"] + rect["h"]
    for r in range(max(0, y0 // T), min(rows, y1 // T + 1)):
        for c in range(max(0, x0 // T), min(cols, x1 // T + 1)):
            ox = max(0, min(x1, (c + 1) * T) - max(x0, c * T))
            oy = max(0, min(y1, (r + 1) * T) - max(y0, r * T))
            if ox * oy >= share * T * T or (ox >= T * share and oy >= T * share and (rect["w"] < T * 1.5 or rect["h"] < T * 1.5) and ox * oy >= 0.25 * T * T):
                yield c, r


def overlaps(rect: dict, x: float, y: float, pad: float) -> bool:
    return rect["x"] - pad <= x <= rect["x"] + rect["w"] + pad and rect["y"] - pad <= y <= rect["y"] + rect["h"] + pad


def build(layout: dict, zf: zipfile.ZipFile):
    world_w, world_h = layout["world"]["width"], layout["world"]["height"]
    cols, rows = world_w // T, world_h // T
    ts = Tileset(zf)
    ground = [ts.gid(PALETTE["grass"][hash2(c, r) % len(PALETTE["grass"])], "grass") for r in range(rows) for c in range(cols)]
    paths = [0] * (cols * rows)
    water = [0] * (cols * rows)
    fences = [0] * (cols * rows)

    b = layout["buildings"]
    def paint(rect, name, share=0.4):
        refs = PALETTE[name]
        for c, r in cells(rect, cols, rows, share):
            paths[r * cols + c] = ts.gid(refs[hash2(c, r, 7) % len(refs)], name if name in MATCH else None)

    for walk in layout["walkways"]:
        paint(walk, "sidewalk")
    # The street in front of the cafe is a plaza, not a lane; the noticeboard stands on cobbles.
    paint({"x": b["street"]["x"], "y": b["street"]["y"] + 20, "w": b["street"]["w"], "h": 96}, "herringbone")
    paint(b["board"], "cobblestone", 0.3)
    # Doorsteps: every door meets paving. The same rects double as no-lamp zones so a lamp
    # never stands in a doorway.
    door_keepouts = []
    for home in layout["homes"].values():
        door_x = home["x"] + home["w"] - 38
        rect = {"x": door_x - 16, "y": home["y"] + home["h"], "w": 32, "h": 36}
        paint(rect, "sidewalk", 0.3)
        door_keepouts.append(rect)
    for key in ("cafe", "academy", "gym", "shop"):
        bb = b[key]
        rect = {"x": bb["x"] + bb["doorX"] - 16, "y": bb["y"] + bb["h"], "w": 32, "h": 36}
        paint(rect, "sidewalk", 0.3)
        door_keepouts.append(rect)
    # Garden beds sit on turned earth.
    g = b["garden"]
    paint({"x": g["x"] + 12, "y": g["y"] + 40, "w": g["w"] - 24, "h": g["h"] - 60}, "dirt", 0.5)

    built = [*layout["homes"].values(), *b.values(),
             {"x": b["cafe"]["x"] - 12, "y": b["cafe"]["y"] - 12, "w": 664, "h": 560}]
    occupied = [*layout["walkways"], *built]
    right = max(r["x"] + r["w"] for r in occupied)
    bottom = max(r["y"] + r["h"] for r in occupied)
    homes_right = max(h["x"] + h["w"] for h in layout["homes"].values())
    # Only lanes that run well east of the house rows (the shop lane), not ones that merely clip
    # past homes_right by a few pixels (the home-row lanes end just past it too).
    lane_bottom = max(w["y"] + w["h"] for w in layout["walkways"] if w["x"] + w["w"] > homes_right + 64)

    # A fenced park fills the open lawn south-east of the houses and the shop, with a pond at its heart.
    park = {"x": (homes_right + 96) // T * T, "y": (lane_bottom + 64) // T * T}
    park["w"] = (world_w - 96) // T * T - park["x"]
    park["h"] = (world_h - 96) // T * T - park["y"]
    pc, pr, pw, ph = park["x"] // T, park["y"] // T, park["w"] // T, park["h"] // T
    gate = pc + pw // 2
    for dc in range(pw):
        for dr in (0, ph - 1):
            if dr == 0 and abs(pc + dc - gate) <= 1:
                continue  # the gate faces the lane
            v = "n" if dr == 0 else "s"
            h = "w" if dc == 0 else "e" if dc == pw - 1 else ""
            fences[(pr + dr) * cols + pc + dc] = ts.gid(PALETTE["fence"][v + h])
    for dr in range(1, ph - 1):
        fences[(pr + dr) * cols + pc] = ts.gid(PALETTE["fence"]["w"])
        fences[(pr + dr) * cols + pc + pw - 1] = ts.gid(PALETTE["fence"]["e"])
    pond_w, pond_h = min(11, pw - 8), min(6, ph - 8)
    pond_c, pond_r = pc + (pw - pond_w) // 2, pr + (ph - pond_h) // 2
    for dr in range(pond_h):
        for dc in range(pond_w):
            v = "n" if dr == 0 else "s" if dr == pond_h - 1 else ""
            h = "w" if dc == 0 else "e" if dc == pond_w - 1 else ""
            water[(pond_r + dr) * cols + pond_c + dc] = ts.gid(PALETTE["pond"][(v + h) or "c"], "grass")
    pond = {"x": pond_c * T, "y": pond_r * T, "w": pond_w * T, "h": pond_h * T}
    # A dirt ring around the pond and a trail from the gate: the park reads as a place to walk.
    ring = {"x": pond["x"] - 64, "y": pond["y"] - 64, "w": pond["w"] + 128, "h": pond["h"] + 128}
    for side in ({**ring, "h": 32}, {**ring, "y": ring["y"] + ring["h"] - 32, "h": 32},
                 {**ring, "w": 32}, {**ring, "x": ring["x"] + ring["w"] - 32, "w": 32},
                 {"x": gate * T, "y": park["y"], "w": 32, "h": ring["y"] - park["y"]}):
        paint(side, "dirt", 0.5)

    decor = []
    def put(frame, x, y, scale=1.0):
        # Every decor object is anchored bottom-centre, so its base - not its canopy - must
        # stay inside the world: keep x off the very edge and y no lower than the bottom row.
        decor.append({"frame": frame, "x": round(min(world_w - 16, max(16, x))),
                       "y": round(min(world_h - 4, y)), "scale": scale})

    # Small, mostly-transparent ground plants (grass_* frames are 100%-opaque autotile edge
    # tiles, not plant sprites - they render as solid dark-green squares and are never used here).
    small_plants = ["flowers_1", "flowers_2", "flowers_3", "flowers_4", "flowers_5", "shrub_1"]
    trees = ["tree_1", "tree_2", "tree_3", "tree_4", "tree_5", "tree_6"]

    # Park furniture: benches facing the water, flower beds at the corners of the ring, ducks.
    cx = pond["x"] + pond["w"] / 2
    put("gardenbench_1", cx - 96, ring["y"] - 4)
    put("gardenbench_1", cx + 96, ring["y"] - 4)
    put("bench_2", cx, ring["y"] + ring["h"] + 60)
    for i, (x, y) in enumerate(((ring["x"] - 40, ring["y"] - 8), (ring["x"] + ring["w"] + 40, ring["y"] - 8),
                                (ring["x"] - 40, ring["y"] + ring["h"] + 40), (ring["x"] + ring["w"] + 40, ring["y"] + ring["h"] + 40))):
        put(f"flowerbush_{1 + i % 3}", x, y)
    put("duck_white_idle_1", pond["x"] + pond["w"] * 0.35, pond["y"] + pond["h"] * 0.6, 0.8)
    put("duck_green_head_idle_1", pond["x"] + pond["w"] * 0.62, pond["y"] + pond["h"] * 0.45, 0.8)
    park_keep = {"x": ring["x"] - 72, "y": park["y"], "w": ring["w"] + 144, "h": ring["y"] + ring["h"] + 104 - park["y"]}

    # The only open ground the ring/trail keepout leaves is a band along the south fence - put a
    # couple of trees in its corners and a pair of flower beds between them, plus a lamp either
    # side of the gate at the north fence.
    south_y = park["y"] + park["h"] - 44
    put("tree_2", park["x"] + 60, south_y, 0.95)
    put("tree_4", park["x"] + 112, south_y + 14, 0.85)
    put("tree_3", park["x"] + park["w"] - 60, south_y, 0.95)
    put("tree_5", park["x"] + park["w"] - 112, south_y + 14, 0.85)
    put("flowerbush_2", park["x"] + park["w"] * 0.32, south_y + 20, 0.75)
    put("flowerbush_3", park["x"] + park["w"] * 0.68, south_y + 20, 0.75)
    put("lamp_5", gate * T - 56, park["y"] - 2, 0.6)
    put("lamp_5", gate * T + 56, park["y"] - 2, 0.6)

    # Lamps stand beside the long lanes at roughly even spacing, never on the paving itself and
    # never overlapping a building or a doorway (with a small pad, checked against both the lamp's
    # base and the top of its 32x128 sprite).
    lamp_scale = 0.6
    lamp_h = 128 * lamp_scale
    lamp_blockers = built + door_keepouts
    def lamp_clear(x, y):
        if any(overlaps(w, x, y, 6) for w in layout["walkways"]):
            return False
        return not any(overlaps(o, x, y, 10) or overlaps(o, x, y - lamp_h, 10) for o in lamp_blockers)

    placed_lamps = []
    for walk in layout["walkways"]:
        horiz = walk["w"] >= walk["h"]
        length = walk["w"] if horiz else walk["h"]
        if length < 260:
            continue
        margin = 140
        span = length - 2 * margin
        if span <= 0:
            continue
        slots = max(1, round(span / 380))
        step = span / slots
        for i in range(slots + 1):
            pos = margin + i * step
            for offset in (18, 40, 65, 95, 130):
                for sign in (1, -1):
                    if horiz:
                        x, y = walk["x"] + pos, walk["y"] + (walk["h"] + offset if sign > 0 else -offset)
                    else:
                        x, y = walk["x"] + (walk["w"] + offset if sign > 0 else -offset), walk["y"] + pos
                    if lamp_clear(x, y) and all(abs(x - lx) + abs(y - ly) > 150 for lx, ly in placed_lamps):
                        put("lamp_5", x, y, lamp_scale)
                        placed_lamps.append((x, y))
                        break
                else:
                    continue
                break

    # Planting by distance from anything built: a woodland along the town's edges, scattered trees in
    # open lawn, and only tufts and flowers close to houses so nothing crowds a door or a window.
    for r in range(2, rows):
        for c in range(0, cols):
            x, y = c * T + T // 2, r * T + T
            if any(overlaps(o, x, y, 24) for o in occupied) or overlaps(park, x, y, 12) and not (
                    x < park["x"] + 72 or x > park["x"] + park["w"] - 72 or y > park["y"] + park["h"] - 40) or overlaps(park_keep, x, y, 0):
                continue
            near = any(overlaps(o, x, y, 72) for o in occupied)
            edge = c < 3 or c >= cols - 3 or r < 4 or r >= rows - 2 or x > right + 96 or y > bottom + 96 or overlaps(park, x, y, 12)
            roll = hash2(c, r, 3) % 100
            jitter = hash2(c, r, 5) % 16 - 8
            if near:
                if roll < 6:
                    put(small_plants[hash2(c, r, 11) % len(small_plants)], x + jitter, y)
                elif roll < 9:
                    put(f"flowers_{1 + hash2(c, r, 13) % 5}", x + jitter, y)
            elif edge and roll < 34 or roll < 7:
                put(trees[hash2(c, r, 17) % len(trees)], x + jitter, y + hash2(c, r, 19) % 12, round(0.85 + (hash2(c, r, 9) % 4) / 10, 2))
            elif roll < 13:
                put(["bush_1", "bush_2", "bush_3", "shrub_1"][hash2(c, r, 23) % 4], x + jitter, y, 0.9)
            elif roll < 19:
                put(small_plants[hash2(c, r, 11) % len(small_plants)], x + jitter, y)
    # Keep trees from clumping into a wall; small plants may sit closer.
    spaced = []
    for d in decor:
        gap = 52 if d["frame"].startswith("tree") else 28
        if all(abs(d["x"] - e["x"]) + abs(d["y"] - e["y"]) >= gap for e in spaced if e["frame"].startswith("tree") or not d["frame"].startswith("tree")):
            spaced.append(d)

    def layer(name, data, lid):
        return {"id": lid, "name": name, "type": "tilelayer", "width": cols, "height": rows, "x": 0, "y": 0,
                "opacity": 1, "visible": True, "data": data}

    tiles_img = ts.image()
    tmj = {
        "type": "map", "version": "1.10", "tiledversion": "1.10.2", "orientation": "orthogonal",
        "renderorder": "right-down", "infinite": False, "width": cols, "height": rows,
        "tilewidth": T, "tileheight": T, "nextlayerid": 6, "nextobjectid": len(spaced) + 1,
        "layers": [
            layer("ground", ground, 1), layer("paths", paths, 2), layer("water", water, 3), layer("fences", fences, 5),
            {"id": 4, "name": "decor", "type": "objectgroup", "x": 0, "y": 0, "opacity": 1, "visible": True,
             "draworder": "topdown",
             "objects": [{"id": i + 1, "name": d["frame"], "type": "plant", "x": d["x"], "y": d["y"], "width": 0,
                          "height": 0, "rotation": 0, "visible": True, "point": True,
                          "properties": [{"name": "scale", "type": "float", "value": d["scale"]}]}
                         for i, d in enumerate(spaced)]},
        ],
        "tilesets": [{"firstgid": 1, "name": "town-tiles", "image": "town-tiles.png",
                      "imagewidth": tiles_img.width, "imageheight": tiles_img.height,
                      "tilewidth": T, "tileheight": T, "margin": 0, "spacing": 0,
                      "tilecount": len(ts.tiles), "columns": tiles_img.width // T}],
    }
    meta = {"park": park, "pond": pond}
    return tmj, tiles_img, ts, meta


def preview(tmj: dict, tiles_img: Image.Image, layout: dict) -> Image.Image:
    cols, rows = tmj["width"], tmj["height"]
    img = Image.new("RGBA", (cols * T, rows * T))
    per_row = tiles_img.width // T
    for layer in tmj["layers"]:
        if layer["type"] != "tilelayer":
            continue
        for i, gid in enumerate(layer["data"]):
            if gid:
                t = gid - 1
                tile = tiles_img.crop(((t % per_row) * T, (t // per_row) * T, (t % per_row) * T + T, (t // per_row) * T + T))
                img.alpha_composite(tile, ((i % cols) * T, (i // cols) * T))
    from PIL import ImageDraw
    d = ImageDraw.Draw(img)
    for rect in [*layout["homes"].values(), *layout["buildings"].values()]:
        d.rectangle([rect["x"], rect["y"], rect["x"] + rect["w"], rect["y"] + rect["h"]], outline=(90, 70, 50, 255), width=3)
    for obj in next(l for l in tmj["layers"] if l["type"] == "objectgroup")["objects"]:
        d.ellipse([obj["x"] - 14, obj["y"] - 28, obj["x"] + 14, obj["y"]], fill=(70, 95, 60, 220))
    return img


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--exteriors", default=str(ROOT / "tmp/modernexteriors-win.zip"))
    parser.add_argument("--preview", help="also write a flattened preview PNG here")
    args = parser.parse_args()
    layout = json.loads(LAYOUT.read_text())
    with zipfile.ZipFile(args.exteriors) as zf:
        tmj, tiles_img, ts, meta = build(layout, zf)
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "town.tmj").write_text(json.dumps(tmj))
    tiles_img.save(OUT / "town-tiles.png")
    park, pond = meta["park"], meta["pond"]
    print(f"town.tmj {tmj['width']}x{tmj['height']} tiles, {len(ts.tiles)} unique tiles, "
          f"{len(tmj['layers'][4]['objects'])} decor objects, "
          f"park x={park['x']}..{park['x'] + park['w']} y={park['y']}..{park['y'] + park['h']}, "
          f"pond {pond['w']}x{pond['h']}px at ({pond['x']},{pond['y']})")
    if args.preview:
        preview(tmj, tiles_img, layout).save(args.preview)


if __name__ == "__main__":
    main()
