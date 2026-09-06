#!/usr/bin/env python3
"""Build the 成长小镇 texture atlas from the purchased LimeZu asset packs.

The Modern Exteriors / Modern Interiors / Modern Farm packs are licensed for use in
this project but may not be redistributed, so the raw packs and the generated atlas
stay out of git. Run this script locally after downloading the packs:

    python3 scripts/build-town-assets.py --exteriors tmp/modernexteriors-win.zip \
        --interiors tmp/moderninteriors-win.zip --farm tmp/Modern_Farm_v1.2.zip

Output goes to frontend/public/assets/town/ (gitignored):
    town-atlas.png / town-atlas.json   Phaser JSON-hash atlas of buildings, terrain, props,
                                        and (from the farm pack) small animal idle/walk frames
                                        (dogs, doghouse, rabbits, chickens, ducks)
    characters/c01.png .. c20.png      LimeZu premade character sheets (32x64 frames)
    characters/labour_*.png            Farm pack labour animation sheets (chopping / watering /
                                        fishing / harvesting / digging), one Phaser spritesheet
                                        per animation, geometry described by labour-anims.json
    characters/labour-anims.json       {name: {file, frameWidth, frameHeight, frames, rows,
                                        directions}} sidecar for the labour spritesheets above
    interior-atlas.png / .json         Interior furniture/tile atlas (academy + data-driven rooms)
    maps/*.json                        Room maps for map-loader.ts / interior.scene.ts (no zip
                                        needed — regenerated from generate_room_maps() alone).

The farm pack (--farm) is optional: if the zip is missing, or a member inside it is
missing, this script still succeeds and simply omits the frames/files it would have
produced (same graceful-skip behaviour as the exteriors/interiors PIECES tables).

Requires Pillow (pip install pillow).
"""
from __future__ import annotations

import argparse
import io
import json
import re
import sys
import zipfile
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover
    sys.exit("Pillow is required: pip install pillow")

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "frontend" / "public" / "assets" / "town"
EXT = "Modern_Exteriors_32x32/ME_Theme_Sorter_32x32/"
SINGLE = "ME_Singles_"

# (atlas frame prefix, zip folder, file stem pattern, max variants)
PIECES = [
    ("grass", "1_Terrains_and_Fences_Singles_32x32", "Terrains_and_Fences_32x32_Grass_1_{i}", 40),
    ("sidewalk", "2_City_Terrains_Singles_32x32", "City_Terrains_32x32_Sidewalk_1_{i}", 40),
    ("asphalt", "2_City_Terrains_Singles_32x32", "City_Terrains_32x32_Asphalt_1_Variation_{i}", 40),
    ("gym", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Ground_Floor_Gym_{i}", 12),
    ("condo", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Ground_Floor_Condo_{i}", 12),
    ("bakery", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Ground_Floor_Bakery_{i}", 12),
    ("shop", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Ground_Floor_Shop_{i}", 36),
    ("music", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Ground_Floor_Music_Store_{i}", 12),
    ("icecream", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Ground_Floor_Ice_Cream_Shop_{i}", 6),
    ("middle", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Middle_Floor_{i}", 18),
    ("roof", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Roof_{i}", 11),
    ("roofprop", "5_Floor_Modular_Building_Singles_32x32", "Floor_Modular_Building_32x32_Roof_Props_{i}", 16),
    ("school", "13_School_Singles_32x32", "School_32x32_School_{i}", 1),
    ("clocktower", "13_School_Singles_32x32", "School_32x32_Clock_Tower_{i}", 1),
    ("schoolflag", "13_School_Singles_32x32", "School_32x32_Flag_{i}", 2),
    ("schoolramp", "13_School_Singles_32x32", "School_32x32_School_Entrance_Ramp_{i}", 1),
    ("schoolbench", "13_School_Singles_32x32", "School_32x32_Bench_{i}", 2),
    ("tree", "3_City_Props_Singles_32x32", "City_Props_32x32_Tree_{i}", 8),
    ("lamp", "3_City_Props_Singles_32x32", "City_Props_32x32_Street_Lamp_{i}", 5),
    ("bench", "3_City_Props_Singles_32x32", "City_Props_32x32_Bench_{i}", 2),
    ("mailbox", "3_City_Props_Singles_32x32", "City_Props_32x32_Mailbox_{i}", 1),
    ("flowerbush", "3_City_Props_Singles_32x32", "City_Props_32x32_Flower_Bush_{i}", 6),
    ("flowers", "3_City_Props_Singles_32x32", "City_Props_32x32_Flowers_{i}", 5),
    ("shrub", "3_City_Props_Singles_32x32", "City_Props_32x32_Shrub_{i}", 1),
    ("fountain", "3_City_Props_Singles_32x32", "City_Props_32x32_Fountain_{i}", 1),
    ("infosign", "3_City_Props_Singles_32x32", "City_Props_32x32_Info_Sign_{i}", 2),
    ("gardenbench", "17_Garden_Singles_32x32", "Garden_32x32_Big_Bench_Horizontal", 0),
    ("bush", "17_Garden_Singles_32x32", "Garden_32x32_Bush_{i}", 4),
    ("sunflower", "17_Garden_Singles_32x32", "Garden_32x32_Big_Sunflower", 0),
    ("carleft", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Car_Left_{i}", 8),
    ("carright", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Car_Right_{i}", 8),
    ("busleft", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Bus_Left_{i}", 2),
    ("busright", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Bus_Right_{i}", 2),
    ("busstop", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Bus_Stop_{i}", 1),
    ("busstopsign", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Bus_Stop_Sign_{i}", 1),
    ("flowercart", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Fruit_Flowers_Cart_{i}", 1),
    ("foodcart", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Street_Food_Cart_{i}", 1),
    ("hotdogcart", "10_Vehicles_Singles_32x32", "Vehicles_32x32_Hot_Dog_Cart_{i}", 1),
    ("court", "13_School_Singles_32x32", "School_32x32_Basketball_Court_{i}", 8),
    ("basketnet", "13_School_Singles_32x32", "School_32x32_Basketball_Net_{i}", 2),
    ("yardtoy", "13_School_Singles_32x32", "School_32x32_School_Yard_Toy_{i}", 19),
    ("railing", "13_School_Singles_32x32", "School_32x32_School_Railing_{i}", 1),
    ("stadiumlight", "13_School_Singles_32x32", "School_32x32_Stadium_Light_{i}", 2),
    ("soccerball", "13_School_Singles_32x32", "School_32x32_Soccer_Ball_{i}", 1),
]

# Extra character sheets shipped with the exteriors pack (same 32x64 frame layout as the premade characters).
EXTRA_CHARACTERS = [
    ("postman", "Modern_Exteriors_32x32/Character_Generator_Addons_32x32/Characters_32x32/Modern_Exteriors_Characters_Postman_32x32_1.png"),
    ("scout", "Modern_Exteriors_32x32/Character_Generator_Addons_32x32/Characters_32x32/Modern_Exteriors_Characters_Scout_32x32_1.png"),
]

# Animated strips from the exteriors pack: (frame prefix, path, frame width, frame height)
ANIMATED = [
    ("pigeon", "Modern_Exteriors_32x32/Animated_32x32/Animated_sheets_32x32/Pigeon_32x32.png", 32, 32),
    ("crow", "Modern_Exteriors_32x32/Animated_32x32/Animated_sheets_32x32/Crow_idle_Down_32x32.png", 32, 64),
]

CHARACTERS = "2_Characters/Character_Generator/0_Premade_Characters/32x32/Premade_Character_32x32_{i:02d}.png"
EMOTES = "4_User_Interface_Elements/UI_thinking_emotes_animation_32x32.png"

# --- Modern Farm pack (M0-2 / M0-3): animal idle+walk frames added to the town atlas, plus
# standalone labour-animation spritesheets for the character. None of these sheets ship pre-cut
# singles or an official frame map, so every grid below was determined empirically (see the PR
# description / git history for the analysis): load the sheet, inspect alpha bounding boxes per
# candidate cell size, and confirm against the in-image "ROW:_ COL:_ FRAME:_x_px" legend most of
# these sheets bake into their own top-left corner.
#
# Animals: every sheet is a documentation-style strip — a legend/preview band, then repeating
# [ANIMATION NAME label][frame row] bands. Two empirically-confirmed shapes cover all of them:
#   - "simple" (rabbits, chickens, roosters, ducks, ducklings): exactly 4 rows, no per-row labels.
#     Row0 = legend/preview icons, row1 = an idle-ish loop, row2 = a walk/hop/run loop, row3 =
#     another idle variant. Row height is `height // 4`. Column width is `width // 24` (COL:24 is
#     a hard invariant printed in every one of these sheets' own legend) — cells are NOT always
#     square: rabbits/ducks/chickens happen to have width == height (e.g. 1536x256 -> 64x64,
#     768x128 -> 32x32), but Rooster_Brown_32x32.png is 768x256 -> a 32-wide x 64-tall cell (taller
#     for the tail/comb, same width as the plain Chicken sheets); assuming a square `height // 4`
#     cell for width too visibly merges two roosters into one "frame". The one sheet where even
#     `width // 24` fails is Duck_White_32x32.png (1658x256): floor-dividing gives 69px, which cuts
#     every duck in half, because the sheet has ~58px of unused trailing padding after the 24th
#     real column rather than being an exact multiple of the cell size — confirmed by comparison
#     against Duck_Brown/Duck_Green_Head_32x32.png (clean 1536x256, cell 64) and is special-cased
#     below via FARM_CELL_WIDTH_OVERRIDE.
#   - "dog" (dog breed sheets): a taller, explicitly-labelled sheet (each section has its own
#     "IDLE"/"WALK"/"RUN"/... text banner). Row height is fixed at 64px regardless of breed; only
#     the column width varies (COL:24 always, so `width // 24`: 96px for the Labrador/German
#     Shepherd family at 2304x832, 64px for the smaller Basenji family at 1536x960). The IDLE frame
#     row always starts at y=128 and the WALK frame row always starts at y=256 in both families
#     (verified by cropping and visually inspecting both bands for several variants).
#   - Dogs_Doghouse_Sleeping_32x32.png (512x858) is its own one-off grid: 64x96 cells, 8 columns x
#     ~9 rows, one dog-color variant per row, one breathing-loop animation frame per column
#     (confirmed distinct via per-cell MD5 hashes — frames only look identical at a glance).
FARM = "32x32/Animals_32x32/"
FARM_DOGHOUSE = "Dogs_32x32/Dogs_Doghouse_Sleeping_32x32.png"
FARM_DOG_IDLE_Y = 128
FARM_DOG_WALK_Y = 256
FARM_DOG_FRAME_H = 64
FARM_WALK_FRAMES = 4

# Duck_White_32x32.png's canvas has trailing padding after its 24th real column (see comment
# above), so `width // 24` misidentifies the cell width; override with the value confirmed against
# its clean siblings (Duck_Brown/Duck_Green_Head_32x32.png, both exactly 1536x256, cell 64).
FARM_CELL_WIDTH_OVERRIDE = {
    "Ducks_32x32/Duck_White_32x32.png": 64,
}

# (frame prefix, kind, path within FARM) — kind "dog" uses the labelled multi-row layout,
# "simple" uses the plain 4-row layout described above.
FARM_ANIMALS = [
    ("dog_basenji_brown", "dog", "Dogs_32x32/Dog_Basenji_Brown_32x32.png"),
    ("dog_basenji_gray", "dog", "Dogs_32x32/Dog_Basenji_Gray_32x32.png"),
    ("dog_basenji_orange", "dog", "Dogs_32x32/Dog_Basenji_Orange_32x32.png"),
    ("dog_german_shepherd_brown", "dog", "Dogs_32x32/Dog_German_Shepherd_Brown_32x32.png"),
    ("dog_german_shepherd_dark_brown", "dog", "Dogs_32x32/Dog_German_Shepherd_Dark_Brown_32x32.png"),
    ("dog_german_shepherd_gray", "dog", "Dogs_32x32/Dog_German_Shepherd_Gray_32x32.png"),
    ("dog_labrador_brown", "dog", "Dogs_32x32/Dog_Labrador_Brown_32x32.png"),
    ("dog_labrador_dark_brown", "dog", "Dogs_32x32/Dog_Labrador_Dark_Brown_32x32.png"),
    ("dog_labrador_white", "dog", "Dogs_32x32/Dog_Labrador_White_32x32.png"),
    ("rabbit_baby_brown", "simple", "Rabbits_32x32/Rabbit_Baby_Brown_32x32.png"),
    ("rabbit_baby_gray", "simple", "Rabbits_32x32/Rabbit_Baby_Gray_32x32.png"),
    ("rabbit_baby_white", "simple", "Rabbits_32x32/Rabbit_Baby_White_32x32.png"),
    ("rabbit_brown", "simple", "Rabbits_32x32/Rabbit_Brown_32x32.png"),
    ("rabbit_brown_dark_ears", "simple", "Rabbits_32x32/Rabbit_Brown_Dark_Ears_32x32.png"),
    ("rabbit_gray", "simple", "Rabbits_32x32/Rabbit_Gray_32x32.png"),
    ("rabbit_gray_and_white", "simple", "Rabbits_32x32/Rabbit_Gray_and_White_32x32.png"),
    ("rabbit_spotted", "simple", "Rabbits_32x32/Rabbit_Spotted_32x32.png"),
    ("rabbit_white", "simple", "Rabbits_32x32/Rabbit_White_32x32.png"),
    ("chicken_brown", "simple", "Chickens_and_Roosters_32x32/Chicken_Brown_32x32.png"),
    ("chicken_white", "simple", "Chickens_and_Roosters_32x32/Chicken_White_32x32.png"),
    ("chicken_golden", "simple", "Chickens_and_Roosters_32x32/Chicken_Golden_32x32.png"),
    ("chicken_chick", "simple", "Chickens_and_Roosters_32x32/Chick_32x32.png"),
    ("rooster_brown", "simple", "Chickens_and_Roosters_32x32/Rooster_Brown_32x32.png"),
    ("duck_white", "simple", "Ducks_32x32/Duck_White_32x32.png"),
    ("duck_brown", "simple", "Ducks_32x32/Duck_Brown_32x32.png"),
    ("duck_green_head", "simple", "Ducks_32x32/Duck_Green_Head_32x32.png"),
    ("duck_duckling_yellow", "simple", "Ducks_32x32/Duckling_Yellow_32x32.png"),
]

# Labour animations (M0-3): each Farmer_1_* sheet is a single row, single (facing-down) direction,
# with the frame count baked into the filename. Frame size differs per animation because the tool
# swing extends past the character's body — determined empirically by dividing the sheet's pixel
# width by its filename-stated frame count and confirming every resulting cell's alpha bounding box
# stays inside its own cell with no bleed into neighbours (see PR notes for the verification script).
LABOUR_CHARACTERS = "32x32/Characters_32x32/"
# Each labour strip is one row holding FOUR direction blocks back to back, not a single
# facing: frames_per_direction = frame_count // 4. Which block faces the camera is not
# consistent across the pack -- fishing starts facing away and only turns to camera in its
# second block -- so the down-facing block index is recorded per animation rather than
# assumed. Verified by rendering the first frames of all four blocks of every sheet.
# (name, source filename, frame width, frame height, frame count, down-facing block index)
LABOUR_ANIMATIONS = [
    ("chopping", "Farmer_1_Chopping_40_frames_32x32.png", 64, 128, 40, 0),
    ("watering", "Farmer_1_Watering_56_frames_32x32.png", 96, 192, 56, 0),
    ("fishing", "Farmer_1_Fishing_128_frames_32x32.png", 96, 256, 128, 1),
    ("harvesting", "Farmer_1_Harvesting_36_frames_32x32.png", 32, 64, 36, 0),
    ("digging", "Farmer_1_Dig_36_frames_32x32.png", 64, 64, 36, 0),
]

# --- 成长学院 自习室 (task 7): a second, interior-only atlas built from a handful of
# hand-picked singles cropped out of Modern Interiors' full theme sheets. Those sheets
# don't ship pre-cut singles for every theme, so each entry below is a literal pixel
# box (x0, y0, x1, y1, inclusive) found by inspecting the sheet once; unlike PIECES this
# has no naming convention to iterate, so it stays a flat lookup table.
INTERIOR_SHEETS = {
    "classroom": "1_Interiors/32x32/Theme_Sorter_32x32/5_Classroom_and_library_32x32.png",
    "generic": "1_Interiors/32x32/Theme_Sorter_32x32/1_Generic_32x32.png",
    "floors": "1_Interiors/32x32/Room_Bulder_subfiles_32x32/Room_Builder_Floors_32x32.png",
    "walls": "1_Interiors/32x32/Room_Bulder_subfiles_32x32/Room_Builder_Walls_32x32.png",
    # Added for the data-driven room engine (multi-room task): 自己家·客厅 and 健身房.
    "livingroom": "1_Interiors/32x32/Theme_Sorter_32x32/2_LivingRoom_32x32.png",
    "gym": "1_Interiors/32x32/Theme_Sorter_32x32/8_Gym_32x32.png",
    "kitchen": "1_Interiors/32x32/Theme_Sorter_32x32/12_Kitchen_32x32.png",
}

# (frame name, sheet key, pixel box)
INTERIOR_PIECES = [
    ("chair_1", "classroom", (2, 24, 29, 69)),
    ("chair_2", "classroom", (98, 98, 121, 139)),
    ("desk_1", "classroom", (70, 98, 95, 141)),
    ("bookshelf_1", "classroom", (0, 420, 95, 499)),
    ("bookshelf_2", "classroom", (196, 420, 249, 499)),
    ("bookshelf_3", "classroom", (384, 420, 415, 461)),
    ("board_1", "classroom", (166, 162, 189, 203)),
    ("board_2", "classroom", (420, 174, 475, 219)),
    ("notice_1", "classroom", (6, 192, 57, 217)),
    ("globe_1", "classroom", (418, 34, 443, 75)),
    ("rug_1", "generic", (26, 258, 101, 331)),
    ("doormat_1", "generic", (170, 272, 213, 285)),
    ("plant_1", "generic", (430, 914, 465, 975)),
    ("plant_2", "generic", (480, 2042, 511, 2091)),
    ("floor_1", "floors", (32, 384, 63, 415)),
    ("wall_1", "walls", (384, 32, 415, 63)),
    ("wall_2", "walls", (32, 224, 63, 255)),
    # --- Data-driven rooms (map-loader.ts / interior.scene.ts): 自己家·客厅 ---
    ("sofa_1", "livingroom", (32, 906, 134, 1015)),
    ("bookshelf_home_1", "livingroom", (320, 780, 511, 847)),
    ("plant_3", "livingroom", (426, 0, 471, 61)),
    ("frame_1", "livingroom", (4, 852, 29, 881)),
    ("frame_2", "livingroom", (36, 852, 61, 881)),
    ("frame_3", "livingroom", (68, 852, 93, 881)),
    ("frame_4", "livingroom", (4, 916, 29, 945)),
    # --- 成长学院·自习室 book-count data slot (a single standing book spine, repeated) ---
    ("book_1", "classroom", (392, 710, 411, 771)),
    # --- 健身房 (public place) ---
    ("gymmirror_1", "gym", (132, 760, 187, 795)),
    ("gymrack_1", "gym", (258, 804, 319, 845)),
    ("gymplate_1", "gym", (226, 772, 253, 797)),
    ("gymmat_1", "gym", (48, 896, 79, 925)),
    # --- 客厅新增家具（视觉打磨第二轮：温馨成套家具）---
    ("armchair_wood", "livingroom", (0, 192, 31, 255)),
    ("loveseat_wood", "livingroom", (32, 192, 63, 255)),
    ("armchair_blue", "livingroom", (64, 192, 95, 255)),
    ("coffee_table_wood", "livingroom", (32, 256, 63, 287)),
    ("side_table_round", "livingroom", (0, 256, 31, 287)),
    ("rug_pattern_1", "livingroom", (0, 960, 95, 1055)),
    ("rug_pattern_2", "livingroom", (96, 960, 191, 1055)),
    ("tv_cabinet_1", "livingroom", (256, 64, 287, 111)),
    ("tv_screen_1", "livingroom", (256, 32, 287, 63)),
    ("dining_table_1", "livingroom", (64, 384, 127, 415)),
    ("dining_chair_1", "livingroom", (0, 384, 31, 447)),
    ("dining_chair_2", "livingroom", (32, 384, 63, 447)),
    ("floor_lamp_1", "livingroom", (384, 448, 415, 511)),
    ("side_table_1", "livingroom", (128, 448, 159, 479)),
    ("bar_stool_1", "livingroom", (448, 320, 479, 383)),
    ("bar_stool_2", "livingroom", (480, 320, 511, 383)),
    ("curtain_1", "livingroom", (0, 768, 31, 895)),
    ("wall_clock_1", "livingroom", (96, 832, 127, 863)),
    ("painting_1", "livingroom", (0, 832, 31, 895)),
    ("painting_2", "livingroom", (32, 832, 63, 895)),
    # --- 厨房素材（客厅需要厨房区） ---
    ("fridge_1", "kitchen", (0, 128, 63, 223)),
    ("stove_1", "kitchen", (256, 192, 287, 223)),
    ("sink_1", "kitchen", (256, 256, 287, 287)),
    ("counter_1", "kitchen", (128, 256, 191, 287)),
    ("wall_cabinet_1", "kitchen", (192, 64, 223, 95)),
    # --- 健身房新增家具（达到 15-30 件密度）---
    ("treadmill_1", "gym", (128, 448, 191, 511)),
    ("treadmill_2", "gym", (192, 448, 255, 511)),
    ("exercise_ball_1", "gym", (64, 64, 95, 95)),
    ("exercise_ball_2", "gym", (128, 64, 159, 95)),
    ("bench_1", "gym", (320, 128, 383, 159)),
    ("bench_2", "gym", (320, 192, 383, 223)),
    ("locker_1", "gym", (128, 896, 191, 1023)),
    ("water_dispenser_1", "gym", (448, 896, 479, 959)),
    ("barbell_1", "gym", (256, 128, 319, 159)),
    ("yoga_block_1", "gym", (0, 448, 31, 479)),
    ("cable_machine_1", "gym", (384, 128, 447, 255)),
]

# Floor swatches in the gym sheet are ~30px tall preview squares rather than a seamless 32x32
# tile, so this one frame is force-resized to a clean 32x32 after cropping (see collect_interior).
INTERIOR_RESIZE = {
    "gymmat_1": (32, 32),
}


def load(zf: zipfile.ZipFile, name: str) -> Image.Image | None:
    try:
        return Image.open(io.BytesIO(zf.read(name))).convert("RGBA")
    except KeyError:
        return None


def collect(zf: zipfile.ZipFile) -> dict[str, Image.Image]:
    frames: dict[str, Image.Image] = {}
    for prefix, folder, pattern, count in PIECES:
        indexes = range(1, count + 1) if count else [None]
        for i in indexes:
            stem = pattern.format(i=i) if i is not None else pattern
            image = load(zf, f"{EXT}{folder}/{SINGLE}{stem}.png")
            if image is None:
                continue
            frames[f"{prefix}_{i or 1}"] = image
    for prefix, path, fw, fh in ANIMATED:
        strip = load(zf, path)
        if strip is None:
            continue
        for i in range(strip.width // fw):
            frames[f"{prefix}_{i + 1}"] = strip.crop((i * fw, 0, (i + 1) * fw, fh))
    return frames


def collect_interior(zf: zipfile.ZipFile) -> dict[str, Image.Image]:
    sheets = {key: load(zf, path) for key, path in INTERIOR_SHEETS.items()}
    frames: dict[str, Image.Image] = {}
    for name, sheet_key, (x0, y0, x1, y1) in INTERIOR_PIECES:
        sheet = sheets.get(sheet_key)
        if sheet is None:
            continue
        piece = sheet.crop((x0, y0, x1 + 1, y1 + 1))
        size = INTERIOR_RESIZE.get(name)
        if size is not None:
            piece = piece.resize(size, Image.NEAREST)
        frames[name] = piece
    return frames


def _dog_frames(sheet: Image.Image, prefix: str) -> dict[str, Image.Image]:
    """One idle pose + a short walk cycle from a labelled dog animation sheet (see FARM_ANIMALS
    comment for how the 96px-or-64px cell width and the fixed y=128/y=256 row offsets were found)."""
    cell_w = sheet.width // 24
    frames = {
        f"{prefix}_idle_1": sheet.crop((0, FARM_DOG_IDLE_Y, cell_w, FARM_DOG_IDLE_Y + FARM_DOG_FRAME_H)),
    }
    for i in range(FARM_WALK_FRAMES):
        x0 = i * cell_w
        box = (x0, FARM_DOG_WALK_Y, x0 + cell_w, FARM_DOG_WALK_Y + FARM_DOG_FRAME_H)
        frames[f"{prefix}_walk_{i + 1}"] = sheet.crop(box)
    return frames


def _simple_animal_frames(sheet: Image.Image, prefix: str, cell_w: int) -> dict[str, Image.Image]:
    """One idle pose + a short walk cycle from a plain 4-row animal sheet (rabbits, chickens,
    roosters, ducks, ducklings — see FARM_ANIMALS comment for how the `width // 24` column width
    and `height // 4` row height were found and confirmed against each sheet's own "COL:24"
    legend; cells are not always square, e.g. roosters are narrower than they are tall)."""
    cell_h = sheet.height // 4
    frames = {
        f"{prefix}_idle_1": sheet.crop((0, cell_h, cell_w, cell_h * 2)),
    }
    for i in range(FARM_WALK_FRAMES):
        x0 = i * cell_w
        frames[f"{prefix}_walk_{i + 1}"] = sheet.crop((x0, cell_h * 2, x0 + cell_w, cell_h * 3))
    return frames


def collect_farm(zf: zipfile.ZipFile) -> dict[str, Image.Image]:
    frames: dict[str, Image.Image] = {}
    for prefix, kind, path in FARM_ANIMALS:
        sheet = load(zf, f"{FARM}{path}")
        if sheet is None:
            continue
        if kind == "dog":
            frames.update(_dog_frames(sheet, prefix))
        else:
            cell_w = FARM_CELL_WIDTH_OVERRIDE.get(path, sheet.width // 24)
            frames.update(_simple_animal_frames(sheet, prefix, cell_w))
    doghouse = load(zf, f"{FARM}{FARM_DOGHOUSE}")
    if doghouse is not None:
        for i in range(FARM_WALK_FRAMES):
            frames[f"doghouse_sleep_{i + 1}"] = doghouse.crop((i * 64, 0, (i + 1) * 64, 96))
    return frames


def collect_labour(zf: zipfile.ZipFile, out: Path) -> dict[str, dict]:
    """Save each Farmer_1_* labour sheet as its own spritesheet PNG under characters/ and return
    the {name: geometry} manifest that also gets written to characters/labour-anims.json, so the
    frontend loads each with `this.load.spritesheet(key, path, {frameWidth, frameHeight})` without
    hardcoding per-animation geometry."""
    manifest: dict[str, dict] = {}
    for name, filename, frame_w, frame_h, frame_count, down_block in LABOUR_ANIMATIONS:
        sheet = load(zf, f"{LABOUR_CHARACTERS}{filename}")
        if sheet is None:
            continue
        out_name = f"labour_{name}.png"
        sheet.save(out / "characters" / out_name, optimize=True)
        per_direction = frame_count // 4
        manifest[name] = {
            "file": out_name,
            "frameWidth": frame_w,
            "frameHeight": frame_h,
            "frames": frame_count,
            "rows": 1,
            "directions": 4,
            "framesPerDirection": per_direction,
            # Half-open [downStart, downEnd) -- the only block the town actually plays, since
            # NPCs doing chores are always drawn facing the camera.
            "downStart": down_block * per_direction,
            "downEnd": (down_block + 1) * per_direction,
        }
    if manifest:
        (out / "characters" / "labour-anims.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    return manifest


def pack(frames: dict[str, Image.Image], width: int = 2048, pad: int = 2) -> tuple[Image.Image, dict]:
    """Shelf packer with edge extrusion so nearest-neighbour sampling at fractional
    zoom never bleeds the transparent gutter into a tile seam."""
    items = sorted(frames.items(), key=lambda kv: (-kv[1].height, -kv[1].width, kv[0]))
    placements: dict[str, tuple[int, int]] = {}
    x = y = shelf = 0
    for name, image in items:
        if x + image.width + pad * 2 > width:
            x, y, shelf = 0, y + shelf + pad * 2, 0
        placements[name] = (x + pad, y + pad)
        x += image.width + pad * 2
        shelf = max(shelf, image.height)
    height = y + shelf + pad * 2
    atlas = Image.new("RGBA", (width, height))
    data = {"frames": {}, "meta": {"app": "build-town-assets.py", "image": "town-atlas.png", "size": {"w": width, "h": height}, "scale": "1"}}
    for name, image in items:
        px, py = placements[name]
        w, h = image.size
        # extrude: paint the image shifted into the gutter first, then the real pixels on top
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, -1), (-1, 1), (1, 1)):
            atlas.paste(image, (px + dx, py + dy))
        atlas.paste(image, (px, py))
        data["frames"][name] = {
            "frame": {"x": px, "y": py, "w": w, "h": h},
            "rotated": False,
            "trimmed": False,
            "spriteSourceSize": {"x": 0, "y": 0, "w": w, "h": h},
            "sourceSize": {"w": w, "h": h},
        }
    return atlas, data


# --- Data-driven room maps (frontend/src/modules/town/map-loader.ts + interior.scene.ts) ---
# Independent of everything above: reads no zip, only emits JSON. frontend/public/assets/town/
# is gitignored (LimeZu is commercial-use-only, no redistribution), so these three rooms must be
# regenerable from this script alone — nothing here is hand-edited after the fact. Re-run this
# script whenever a room's layout changes; it always rewrites all three files.
ROOM_TILE = 32


def _room_shell(cols: int, rows: int, wall_rows: int = 3, floor_tile: str = "floor_1") -> tuple[list[list[str | None]], list[list[str | None]]]:
    """Floor + wall tile grids for a plain rectangular room: a back-wall band (`wall_rows`
    rows, wallpaper over one row of wainscot — see academy.scene.ts's drawWalls) and side walls
    running the full height, floor tiles everywhere below the wall band."""
    floor: list[list[str | None]] = [[None] * cols for _ in range(rows)]
    for r in range(wall_rows, rows):
        for c in range(cols):
            floor[r][c] = floor_tile
    walls: list[list[str | None]] = [[None] * cols for _ in range(rows)]
    for r in range(wall_rows):
        tile = "wall_1" if r == wall_rows - 1 else "wall_2"
        for c in range(cols):
            walls[r][c] = tile
    for r in range(rows):
        walls[r][0] = "wall_1"
        walls[r][cols - 1] = "wall_1"
    return floor, walls


def _perimeter_collisions(cols: int, rows: int, wall_rows: int = 3) -> list[dict]:
    width, height = cols * ROOM_TILE, rows * ROOM_TILE
    return [
        {"x": 0, "y": 0, "w": width, "h": wall_rows * ROOM_TILE},
        {"x": 0, "y": 0, "w": ROOM_TILE, "h": height},
        {"x": width - ROOM_TILE, "y": 0, "w": ROOM_TILE, "h": height},
    ]


def _home_living_room() -> dict:
    cols, rows, wall_rows = 18, 11, 3
    width, height = cols * ROOM_TILE, rows * ROOM_TILE
    wall_bottom = wall_rows * ROOM_TILE
    floor, walls = _room_shell(cols, rows, wall_rows)
    door_x = width // 2
    return {
        "id": "home-living-room",
        "title": "自己家 · 客厅",
        "tileSize": ROOM_TILE,
        "cols": cols,
        "rows": rows,
        "backgroundColor": "#f1e6d2",
        "spawn": {"x": door_x, "y": height - 50},
        "layers": {"floor": floor, "walls": walls},
        "collisions": [
            *_perimeter_collisions(cols, rows, wall_rows),
            {"x": 50, "y": 170, "w": 130, "h": 90},  # 沙发组区域
            {"x": 430, "y": wall_bottom, "w": 120, "h": 50},  # 书架（挂墙）
            {"x": 30, "y": wall_bottom + 10, "w": 150, "h": 60},  # 电视柜
            {"x": 330, "y": 150, "w": 90, "h": 70},  # 餐桌
            {"x": 520, "y": wall_bottom + 10, "w": 50, "h": 90},  # 冰箱
        ],
        "doors": [
            {"id": "front-door", "rect": {"x": door_x - 48, "y": height - 34, "w": 96, "h": 30}, "target": "town", "label": "回到小镇"},
        ],
        "furniture": [
            # 客厅区（左侧）：温馨沙发组 + 茶几 + 地毯
            {"id": "loveseat", "frame": "loveseat_wood", "x": 100, "y": 255},
            {"id": "armchair-1", "frame": "armchair_wood", "x": 150, "y": 255},
            {"id": "armchair-2", "frame": "armchair_blue", "x": 60, "y": 220},
            {"id": "coffee-table", "frame": "coffee_table_wood", "x": 110, "y": 210},
            {"id": "side-table", "frame": "side_table_round", "x": 160, "y": 195},
            {"id": "floor-lamp", "frame": "floor_lamp_1", "x": 45, "y": 270},
            {"id": "rug-living", "frame": "rug_pattern_1", "x": 110, "y": 230, "displayWidth": 150, "displayHeight": 150, "depth": 1},
            # 电视墙（后墙左段）
            {"id": "tv-cabinet", "frame": "tv_cabinet_1", "x": 90, "y": wall_bottom + 40},
            {"id": "tv-screen", "frame": "tv_screen_1", "x": 90, "y": wall_bottom},
            {"id": "plant-tv", "frame": "plant_3", "x": 40, "y": wall_bottom + 50},
            {"id": "curtain-left", "frame": "curtain_1", "x": 20, "y": wall_bottom - 40, "depth": wall_bottom - 40},
            {"id": "painting-tv", "frame": "painting_1", "x": 150, "y": 60, "depth": 60},
            # 书架墙（后墙右段）
            {"id": "bookshelf", "frame": "bookshelf_home_1", "x": 490, "y": wall_bottom},
            {"id": "wall-clock", "frame": "wall_clock_1", "x": 400, "y": 60, "depth": 60},
            {"id": "plant-shelf", "frame": "plant_1", "x": 555, "y": wall_bottom + 50},
            {"id": "painting-shelf", "frame": "painting_2", "x": 360, "y": 60, "depth": 60},
            # 餐厅区（中部）：餐桌 + 4 把椅子
            {"id": "dining-table", "frame": "dining_table_1", "x": 375, "y": 215},
            {"id": "chair-1", "frame": "dining_chair_1", "x": 330, "y": 185},
            {"id": "chair-2", "frame": "dining_chair_2", "x": 420, "y": 185},
            {"id": "chair-3", "frame": "dining_chair_1", "x": 330, "y": 245},
            {"id": "chair-4", "frame": "dining_chair_2", "x": 420, "y": 245},
            # 厨房角（右上）：冰箱 + 炉灶 + 水槽 + 操作台 + 吊柜
            {"id": "fridge", "frame": "fridge_1", "x": 545, "y": wall_bottom + 85},
            {"id": "counter", "frame": "counter_1", "x": 485, "y": wall_bottom + 56},
            {"id": "stove", "frame": "stove_1", "x": 425, "y": wall_bottom + 56},
            {"id": "sink", "frame": "sink_1", "x": 365, "y": wall_bottom + 56},
            {"id": "wall-cabinet", "frame": "wall_cabinet_1", "x": 425, "y": wall_bottom, "depth": wall_bottom},
            # 吧台区（右侧）
            {"id": "bar-stool-1", "frame": "bar_stool_1", "x": 500, "y": 175},
            {"id": "bar-stool-2", "frame": "bar_stool_2", "x": 535, "y": 175},
            {"id": "plant-corner", "frame": "plant_2", "x": 560, "y": 295},
            # 门口地毯
            {"id": "doormat", "frame": "doormat_1", "x": door_x, "y": height - 6, "displayWidth": 96, "depth": 1},
            # M3-3/M3-4：三件可交互家具，interactive.actionId 对应 world-actions.ts 里注册的能力，
            # 点开的效果由那边的 run() 决定——这里只标"点这里能干嘛"。位置都挑了本图里原本空着的地板。
            {"id": "desk", "frame": "desk_1", "x": 480, "y": 320,
             "interactive": {"actionId": "home.open-desk", "label": "打开书桌"}},
            {"id": "achievement-board", "frame": "board_2", "x": 315, "y": wall_bottom,
             "interactive": {"actionId": "home.open-achievement-wall", "label": "看看成就墙"}},
            # 宠物窝：M3-5 的宠物（有像素素材的狗/兔/鸟，或占位窝）就画在这件家具的位置上，
            # 见 interior.scene.ts 的 drawPet()——它按 interactive.actionId 找这个点，不需要
            # 房间数据再单独描述一次宠物的坐标。
            {"id": "pet-bed", "frame": "rug_pattern_2", "x": 195, "y": 320,
             "displayWidth": 40, "displayHeight": 40, "depth": 1,
             "interactive": {"actionId": "home.open-pet-house", "label": "宠物窝"}},
        ],
        "slots": [
            # 照片墙：成就进度决定挂几张照片（最多 4 张，循环 4 种画框）
            {"id": "wall-frames", "frames": ["frame_1", "frame_2", "frame_3", "frame_4"], "metric": "homeAchievements",
             "max": 4, "anchor": {"x": 240, "y": 50}, "step": {"x": 50, "y": 0}},
        ],
        "seats": [],
        "lights": [
            {"type": "window", "x": 20, "y": wall_bottom - 20, "label": "左侧窗户"},
            {"type": "ceiling", "x": width // 2, "y": wall_bottom + 80, "label": "吊灯"},
            {"type": "ambient", "x": 50, "y": 270, "label": "落地灯"},
        ],
    }


def _academy_study() -> dict:
    cols, rows, wall_rows = 20, 12, 3
    width, height = cols * ROOM_TILE, rows * ROOM_TILE
    wall_bottom = wall_rows * ROOM_TILE
    floor, walls = _room_shell(cols, rows, wall_rows)
    door_x = width // 2
    rows_y = [172, 272]
    columns_x = [116, 218, 320, 422, 524]
    furniture: list[dict] = [
        {"id": "bookshelf-1", "frame": "bookshelf_1", "x": 90, "y": wall_bottom},
        {"id": "bookshelf-2", "frame": "bookshelf_2", "x": 300, "y": wall_bottom},
        {"id": "bookshelf-3", "frame": "bookshelf_3", "x": 560, "y": wall_bottom},
        {"id": "board", "frame": "board_2", "x": 430, "y": wall_bottom},
        {"id": "notice", "frame": "notice_1", "x": 190, "y": 56, "depth": 60},
        {"id": "globe", "frame": "globe_1", "x": 300, "y": wall_bottom - 6},
        {"id": "chalkboard", "frame": "board_1", "x": 40, "y": height - 20},
        {"id": "plant-1", "frame": "plant_1", "x": 30, "y": wall_bottom + 40},
        {"id": "plant-2", "frame": "plant_2", "x": width - 30, "y": wall_bottom + 40},
        # Explicit low depth: a rug must never out-rank the desks/chairs/residents on top of it.
        {"id": "rug", "frame": "rug_1", "x": door_x, "y": height - 60, "displayWidth": width - 260, "displayHeight": 190, "depth": 1},
        {"id": "doormat", "frame": "doormat_1", "x": door_x, "y": height - 6, "displayWidth": 96},
        {"id": "book-table", "frame": "desk_1", "x": 600, "y": 320},
    ]
    seats: list[dict] = []
    for y in rows_y:
        for x in columns_x:
            furniture.append({"id": f"chair-{x}-{y}", "frame": "chair_2", "x": x, "y": y, "depth": y - 1})
            furniture.append({"id": f"desk-{x}-{y}", "frame": "desk_1", "x": x, "y": y + 34, "depth": y + 34})
            seats.append({"id": f"seat-{x}-{y}", "x": x, "y": y + 6})
    return {
        "id": "academy-study",
        "title": "成长学院 · 自习室",
        "tileSize": ROOM_TILE,
        "cols": cols,
        "rows": rows,
        "backgroundColor": "#e7d9bd",
        "spawn": {"x": door_x, "y": height - 40},
        "layers": {"floor": floor, "walls": walls},
        "collisions": _perimeter_collisions(cols, rows, wall_rows),
        "doors": [
            {"id": "front-door", "rect": {"x": door_x - 48, "y": height - 40, "w": 96, "h": 40}, "target": "town", "label": "回到小镇"},
        ],
        "furniture": furniture,
        "slots": [
            # 字面照搬需求里的例子：完成的知识类任务数决定书桌上摞几本书 (每 2 个任务多摞一本，最多 6 本)。
            {"id": "study-books", "frames": ["book_1"], "metric": "knowledgeDone", "max": 6, "perItem": 2,
             "anchor": {"x": 600, "y": 296}, "step": {"x": 0, "y": -14}},
        ],
        "seats": seats,
        "lights": [
            {"type": "ceiling", "x": width // 2, "y": height // 2, "label": "中央吊灯"},
            {"type": "window", "x": 30, "y": wall_bottom - 20, "label": "左侧窗户"},
            {"type": "window", "x": width - 30, "y": wall_bottom - 20, "label": "右侧窗户"},
        ],
    }


def _public_gym() -> dict:
    cols, rows, wall_rows = 16, 10, 3
    width, height = cols * ROOM_TILE, rows * ROOM_TILE
    wall_bottom = wall_rows * ROOM_TILE
    floor, walls = _room_shell(cols, rows, wall_rows, floor_tile="gymmat_1")
    door_x = width // 2
    return {
        "id": "public-gym",
        "title": "健身房",
        "tileSize": ROOM_TILE,
        "cols": cols,
        "rows": rows,
        "backgroundColor": "#dfe6ee",
        "spawn": {"x": door_x, "y": height - 40},
        "layers": {"floor": floor, "walls": walls},
        "collisions": [
            *_perimeter_collisions(cols, rows, wall_rows),
            {"x": 50, "y": 150, "w": 80, "h": 70},  # 哑铃架区
            {"x": 140, "y": 150, "w": 100, "h": 60},  # 长凳区
            {"x": 360, "y": 160, "w": 80, "h": 100},  # 拉力器
            {"x": 100, "y": 260, "w": 150, "h": 60},  # 跑步机
            {"x": 430, "y": wall_bottom, "w": 60, "h": 120},  # 储物柜
        ],
        "doors": [
            {"id": "front-door", "rect": {"x": door_x - 48, "y": height - 34, "w": 96, "h": 30}, "target": "town", "label": "离开健身房"},
        ],
        "furniture": [
            # 镜墙（后墙左中段）
            {"id": "mirror-1", "frame": "gymmirror_1", "x": 120, "y": wall_bottom},
            {"id": "mirror-2", "frame": "gymmirror_1", "x": 200, "y": wall_bottom},
            {"id": "mirror-3", "frame": "gymmirror_1", "x": 280, "y": wall_bottom},
            # 哑铃架区（左上）
            {"id": "rack", "frame": "gymrack_1", "x": 90, "y": 220},
            {"id": "exercise-ball-1", "frame": "exercise_ball_1", "x": 50, "y": 170},
            {"id": "exercise-ball-2", "frame": "exercise_ball_2", "x": 120, "y": 170},
            # 长凳区（中上）
            {"id": "bench-1", "frame": "bench_1", "x": 190, "y": 180},
            {"id": "bench-2", "frame": "bench_2", "x": 190, "y": 230},
            {"id": "barbell-1", "frame": "barbell_1", "x": 240, "y": 160},
            {"id": "yoga-block-1", "frame": "yoga_block_1", "x": 150, "y": 220},
            {"id": "yoga-block-2", "frame": "yoga_block_1", "x": 230, "y": 220},
            # 拉力器（右上）
            {"id": "cable-machine", "frame": "cable_machine_1", "x": 400, "y": 220},
            # 跑步机区（左下）
            {"id": "treadmill-1", "frame": "treadmill_1", "x": 130, "y": 310},
            {"id": "treadmill-2", "frame": "treadmill_2", "x": 210, "y": 310},
            # 储物柜 + 饮水机（右侧）
            {"id": "locker", "frame": "locker_1", "x": 460, "y": wall_bottom + 100},
            {"id": "water-dispenser", "frame": "water_dispenser_1", "x": 460, "y": 240},
            # 打卡黑板（右下角）
            {"id": "board", "frame": "board_1", "x": 450, "y": 300},
            # 绿植装饰
            {"id": "plant-1", "frame": "plant_1", "x": 30, "y": wall_bottom + 50},
            {"id": "plant-2", "frame": "plant_2", "x": 330, "y": wall_bottom + 50},
            {"id": "plant-3", "frame": "plant_1", "x": width - 30, "y": 280},
            # 瑜伽垫区标记（地面装饰，低 depth）
            {"id": "yoga-mat-1", "frame": "doormat_1", "x": 340, "y": 280, "displayWidth": 48, "displayHeight": 80, "depth": 1},
            {"id": "yoga-mat-2", "frame": "doormat_1", "x": 340, "y": 240, "displayWidth": 48, "displayHeight": 80, "depth": 1},
        ],
        "slots": [
            # 健康任务完成数决定杠铃片数量（最多 6 片，横向排列）
            {"id": "weight-plates", "frames": ["gymplate_1"], "metric": "healthDone", "max": 6,
             "anchor": {"x": 150, "y": 200}, "step": {"x": 18, "y": 0}},
        ],
        "seats": [],
        "lights": [
            {"type": "ceiling", "x": width // 2, "y": height // 2, "label": "中央顶灯"},
            {"type": "ceiling", "x": 120, "y": 180, "label": "左侧顶灯"},
            {"type": "ceiling", "x": width - 120, "y": 180, "label": "右侧顶灯"},
        ],
    }


def generate_room_maps(out: Path) -> list[dict]:
    """Regenerate the three room maps under out/maps/*.json. Pure Python, no zip/Pillow
    involved — safe to call on its own, and always produces the same output for the same code
    (the actual requirement, since the output directory is gitignored)."""
    rooms = [_home_living_room(), _academy_study(), _public_gym()]
    maps_dir = out / "maps"
    maps_dir.mkdir(parents=True, exist_ok=True)
    for room in rooms:
        (maps_dir / f"{room['id']}.json").write_text(json.dumps(room, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return rooms


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--exteriors", default=str(ROOT / "tmp" / "modernexteriors-win.zip"))
    parser.add_argument("--interiors", default=str(ROOT / "tmp" / "moderninteriors-win.zip"))
    parser.add_argument("--farm", default=str(ROOT / "tmp" / "Modern_Farm_v1.2.zip"))
    parser.add_argument("--out", default=str(OUT))
    args = parser.parse_args()

    out = Path(args.out)
    (out / "characters").mkdir(parents=True, exist_ok=True)

    with zipfile.ZipFile(args.exteriors) as zf:
        frames = collect(zf)
        for name, path in EXTRA_CHARACTERS:
            image = load(zf, path)
            if image is not None:
                image.save(out / "characters" / f"{name}.png", optimize=True)

    # Modern Farm pack (M0-2 animals + M0-3 labour animations): optional — a missing zip, or a
    # missing member inside an otherwise-present zip, must not fail the build (see module
    # docstring). Animal frames merge straight into the main town atlas; labour sheets are saved
    # standalone under characters/ since they're loaded as their own Phaser spritesheets.
    farm_path = Path(args.farm)
    labour_manifest: dict[str, dict] = {}
    if farm_path.exists():
        with zipfile.ZipFile(farm_path) as zf:
            farm_frames = collect_farm(zf)
            frames.update(farm_frames)
            labour_manifest = collect_labour(zf, out)
        print(f"farm pack: {len(farm_frames)} animal frames, {len(labour_manifest)} labour animations -> {farm_path}")
    else:
        print(f"farm pack not found at {farm_path}, skipping animal frames and labour animations")

    atlas, data = pack(frames)
    atlas.save(out / "town-atlas.png", optimize=True)
    (out / "town-atlas.json").write_text(json.dumps(data, separators=(",", ":")))

    copied = 0
    with zipfile.ZipFile(args.interiors) as zf:
        for i in range(1, 21):
            image = load(zf, CHARACTERS.format(i=i))
            if image is None:
                continue
            image.save(out / "characters" / f"c{i:02d}.png", optimize=True)
            copied += 1
        emotes = load(zf, EMOTES)
        if emotes is not None:
            emotes.save(out / "emotes.png", optimize=True)

    counts: dict[str, int] = {}
    for name in data["frames"]:
        counts[name.rsplit("_", 1)[0]] = counts.get(name.rsplit("_", 1)[0], 0) + 1
    print(f"atlas {atlas.width}x{atlas.height}, {len(frames)} frames, {copied} character sheets -> {out}")
    print(" ".join(f"{k}:{v}" for k, v in sorted(counts.items())))

    # Second, interior-only atlas for the 自习室 (task 7); additive, does not touch town-atlas above.
    with zipfile.ZipFile(args.interiors) as zf:
        interior_frames = collect_interior(zf)
    if interior_frames:
        interior_atlas, interior_data = pack(interior_frames)
        interior_data["meta"]["image"] = "interior-atlas.png"
        interior_atlas.save(out / "interior-atlas.png", optimize=True)
        (out / "interior-atlas.json").write_text(json.dumps(interior_data, separators=(",", ":")))
        print(f"interior atlas {interior_atlas.width}x{interior_atlas.height}, {len(interior_frames)} frames -> {out}")
        print(" ".join(f"{name}:{im.width}x{im.height}" for name, im in interior_frames.items()))

    # Data-driven room maps (map-loader.ts / interior.scene.ts); independent of the atlases above,
    # always regenerated so frontend/public/assets/town/maps stays in sync with this script.
    rooms = generate_room_maps(out)
    print(f"room maps: {', '.join(room['id'] for room in rooms)} -> {out / 'maps'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
