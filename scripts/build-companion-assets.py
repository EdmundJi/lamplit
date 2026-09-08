#!/usr/bin/env python3
"""Extract a small, reproducible companion atlas from locally purchased LimeZu packs.
Run build-town-assets.py first for Modern Exteriors + Modern Interiors.
This adds visible study furniture from Modern Office and plants/animals from Modern Farm.
Generated licensed images must not be committed or redistributed.
"""
from pathlib import Path
import io, json, zipfile
from PIL import Image
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'frontend/public/assets/town'

def read_image(pack, path):
    return Image.open(io.BytesIO(pack.read(path))).convert('RGBA')

def build_actions(interiors, farm):
    """Compose native character poses and licensed props on one fixed foot anchor.

    Row 4 of the premade-character sheet ("Sit" in LimeZu's own
    Spritesheet_animations_GUIDE.png) is a single self-contained body: head,
    torso, resting hands, tucked legs and shoes, verified by Pillow to keep a
    solid bbox in every one of its 12 populated columns (never clipped at the
    64px cell edge). Row 11 ("lift"/reach) is not a sit pose at all -- its
    frames butt against the cell's bottom edge (bbox y2 == 64, i.e. clipped),
    which is why grafting a second pose's legs onto it produced a floating
    half-body with no chair to sit in. Use row 4 alone, cycled across its 12
    columns for a small idle-breathing loop, as the body for all three
    actions. Watering uses the Farm tool's actual 14-frame stream (row 2,
    96px cells), not the Interiors pickup strip. All five residents retain
    their own clothes.
    """
    office = zipfile.ZipFile(ROOT / 'tmp/Modern_Office_Revamped_v1.2.zip')
    paper = read_image(office, '4_Modern_Office_singles/32x32/Modern_Office_Singles_32x32_113.png')
    paper = paper.crop(paper.getbbox()).resize((18, 16), Image.Resampling.NEAREST)
    pencil = read_image(farm, '32x32/Single_Files_32x32/Props_and_Buildings_32x32/Pencil_DIY_Crafting_Table_32x32.png')
    pencil = pencil.crop(pencil.getbbox()).resize((6, 5), Image.Resampling.NEAREST)
    kitchen = read_image(interiors, '1_Interiors/32x32/Theme_Sorter_32x32/12_Kitchen_32x32.png')
    cup = kitchen.crop((72, 738, 90, 756)).resize((10, 10), Image.Resampling.NEAREST)
    tool = read_image(farm, 'Farmer_Generator_Pieces/Tools/32x32/Tool_Watering_Can.png')
    for number in [1, 3, 6, 9, 12]:
        source = read_image(interiors, f'2_Characters/Character_Generator/0_Premade_Characters/32x32/Premade_Character_32x32_{number:02d}.png')
        def pose(row, col): return source.crop((col * 32, row * 64, (col + 1) * 32, (row + 1) * 64))
        strip = Image.new('RGBA', (14 * 96, 3 * 96))
        for i in range(14):
            # Columns 0-11 of the Sit row are all populated; cycling them gives a
            # seamless idle-breathing loop instead of one static frame.
            body = pose(4, i % 12)
            writing = Image.new('RGBA', (96, 96))
            writing.alpha_composite(body, (16, 16))
            writing.alpha_composite(paper, (38, 60))
            stroke = [0, 0, 1, 2, 1, 0, 1, 2, 1, 0, 0, 0, 0, 0][i]
            writing.alpha_composite(pencil, (40 + stroke, 64))
            strip.alpha_composite(writing, (i * 96, 0))
            drinking = Image.new('RGBA', (96, 96))
            drinking.alpha_composite(body, (16, 16))
            # The cup rises to meet the resting hand, pauses, then lowers again.
            lift = [0, 1, 2, 3, 3, 3, 2, 1, 0, 0, 1, 2, 1, 0][i]
            drinking.alpha_composite(cup, (28, 66 - lift))
            strip.alpha_composite(drinking, (i * 96, 96))
            watering = Image.new('RGBA', (96, 96))
            watering.alpha_composite(body, (16, 16))
            watering.alpha_composite(tool.crop((i * 96, 192, (i + 1) * 96, 288)), (-12, 24))
            strip.alpha_composite(watering, (i * 96, 192))
        strip.save(OUT / f'characters/c{number:02d}-actions.png')

def pack(frames, width=512, pad=2):
    """Shelf packer with edge extrusion, ported from build-town-assets.py's pack():
    without it, nearest-neighbour sampling at the fractional zoom/scale this scene
    actually uses bleeds the transparent gutter into a tile's edge, tinting seams."""
    items = sorted(frames.items(), key=lambda kv: (-kv[1].height, -kv[1].width, kv[0]))
    placements = {}
    x = y = shelf = 0
    for name, image in items:
        if x + image.width + pad * 2 > width:
            x, y, shelf = 0, y + shelf + pad * 2, 0
        placements[name] = (x + pad, y + pad)
        x += image.width + pad * 2
        shelf = max(shelf, image.height)
    height = y + shelf + pad * 2
    atlas = Image.new('RGBA', (width, height))
    data = {'frames': {}}
    for name, image in items:
        px, py = placements[name]
        w, h = image.size
        for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1), (-1, -1), (1, -1), (-1, 1), (1, 1)):
            atlas.paste(image, (px + dx, py + dy))
        atlas.paste(image, (px, py))
        data['frames'][name] = {'frame': {'x': px, 'y': py, 'w': w, 'h': h}, 'rotated': False, 'trimmed': False, 'spriteSourceSize': {'x': 0, 'y': 0, 'w': w, 'h': h}, 'sourceSize': {'w': w, 'h': h}}
    return atlas, data

def main():
    office = zipfile.ZipFile(ROOT / 'tmp/Modern_Office_Revamped_v1.2.zip')
    farm = zipfile.ZipFile(ROOT / 'tmp/Modern_Farm_v1.2.zip')
    assets = {}
    interiors = zipfile.ZipFile(ROOT / 'tmp/moderninteriors-win.zip')
    (OUT / 'characters').mkdir(parents=True, exist_ok=True)
    build_actions(interiors, farm)
    for key, number in [('home_bed_blue', 140), ('home_bed_ochre', 150), ('home_bed_green', 146), ('home_bed_lilac', 151)]:
        im = Image.open(io.BytesIO(interiors.read(f'1_Interiors/32x32/Theme_Sorter_Singles_32x32/4_Bedroom_Singles_32x32/Bedroom_Singles_32x32_{number}.png'))).convert('RGBA')
        assets[key] = im.crop(im.getbbox())
    # office_table was 195 (a wardrobe) and office_books was 339 (a bush) -- both
    # verified by rendering the sheet with Pillow. 269 is a grey L-shaped top-down
    # desk; the Office pack has no bookshelf art at all, so office_books borrows
    # the Interiors classroom sheet's bookshelf_2 (same one build-town-assets.py
    # uses for bookshelf_1/2/3), a front-facing shelf full of book spines.
    for key, n in [('office_desk', 248), ('office_table', 269), ('office_chair', 112), ('office_lamp', 143), ('office_plant', 98), ('office_board', 170), ('project_poster_blank', 170), ('project_poster', 171)]:
        im = Image.open(io.BytesIO(office.read(f'4_Modern_Office_singles/32x32/Modern_Office_Singles_32x32_{n}.png'))).convert('RGBA')
        assets[key] = im.crop(im.getbbox())
    classroom = Image.open(io.BytesIO(interiors.read('1_Interiors/32x32/Theme_Sorter_32x32/5_Classroom_and_library_32x32.png'))).convert('RGBA')
    assets['office_books'] = classroom.crop((196, 420, 249, 499))
    crops = Image.open(io.BytesIO(farm.read('32x32/4_Crops_32x32.png'))).convert('RGBA')
    for key, box in [('farm_lettuce', (192, 0, 256, 64)), ('farm_tomatoes', (256, 320, 320, 384)), ('farm_carrots', (288, 128, 352, 192))]: assets[key] = crops.crop(box)
    terrain = Image.open(io.BytesIO(farm.read('32x32/1_Terrains_32x32.png'))).convert('RGBA')
    assets['farm_soil'] = terrain.crop((32, 288, 64, 320))
    chicken = Image.open(io.BytesIO(farm.read('16x16/Animals_16x16/Chickens_and_Roosters/Chicken_White_16x16.png'))).convert('RGBA')
    for n in range(4): assets[f'farm_chicken_{n}'] = chicken.crop((n * 16, 0, (n + 1) * 16, 16)).resize((32, 32), Image.Resampling.NEAREST)
    OUT.mkdir(parents=True, exist_ok=True)
    atlas, data = pack(assets)
    data['meta'] = {'image': 'companion-atlas.png', 'author': 'LimeZu', 'packs': ['Modern Office Revamped', 'Modern Farm']}
    atlas.save(OUT / 'companion-atlas.png')
    (OUT / 'companion-atlas.json').write_text(json.dumps(data))
    print(f'Built {len(data["frames"])} companion frames in {OUT}')
if __name__ == '__main__': main()
