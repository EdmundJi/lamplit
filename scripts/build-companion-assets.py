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

def main():
    office = zipfile.ZipFile(ROOT / 'tmp/Modern_Office_Revamped_v1.2.zip')
    farm = zipfile.ZipFile(ROOT / 'tmp/Modern_Farm_v1.2.zip')
    assets = {}
    interiors = zipfile.ZipFile(ROOT / 'tmp/moderninteriors-win.zip')
    for key, number in [('home_bed_blue', 140), ('home_bed_ochre', 150), ('home_bed_green', 146), ('home_bed_lilac', 151)]:
        assets[key] = Image.open(io.BytesIO(interiors.read(f'1_Interiors/32x32/Theme_Sorter_Singles_32x32/4_Bedroom_Singles_32x32/Bedroom_Singles_32x32_{number}.png'))).convert('RGBA')
    for key, n in [('office_desk', 248), ('office_table', 195), ('office_chair', 112), ('office_lamp', 143), ('office_books', 339), ('office_plant', 98), ('office_board', 170)]:
        assets[key] = Image.open(io.BytesIO(office.read(f'4_Modern_Office_singles/32x32/Modern_Office_Singles_32x32_{n}.png'))).convert('RGBA')
    crops = Image.open(io.BytesIO(farm.read('32x32/4_Crops_32x32.png'))).convert('RGBA')
    for key, box in [('farm_lettuce', (192, 0, 256, 64)), ('farm_tomatoes', (256, 320, 320, 384)), ('farm_carrots', (288, 128, 352, 192))]: assets[key] = crops.crop(box)
    terrain = Image.open(io.BytesIO(farm.read('32x32/1_Terrains_32x32.png'))).convert('RGBA')
    assets['farm_soil'] = terrain.crop((32, 288, 64, 320))
    chicken = Image.open(io.BytesIO(farm.read('16x16/Animals_16x16/Chickens_and_Roosters/Chicken_White_16x16.png'))).convert('RGBA')
    for n in range(4): assets[f'farm_chicken_{n}'] = chicken.crop((n * 16, 0, (n + 1) * 16, 16)).resize((32, 32), Image.Resampling.NEAREST)
    width = 512; atlas = Image.new('RGBA', (width, 512)); frames = {}; x = y = row = 2
    for key, im in assets.items():
        if x + im.width + 2 > width: x = 2; y += row + 2; row = 0
        atlas.alpha_composite(im, (x, y)); frames[key] = {'frame': {'x': x, 'y': y, 'w': im.width, 'h': im.height}, 'rotated': False, 'trimmed': False, 'spriteSourceSize': {'x': 0, 'y': 0, 'w': im.width, 'h': im.height}, 'sourceSize': {'w': im.width, 'h': im.height}}
        row = max(row, im.height); x += im.width + 2
    OUT.mkdir(parents=True, exist_ok=True)
    atlas.crop((0, 0, width, y + row + 2)).save(OUT / 'companion-atlas.png')
    (OUT / 'companion-atlas.json').write_text(json.dumps({'frames': frames, 'meta': {'image': 'companion-atlas.png', 'author': 'LimeZu', 'packs': ['Modern Office Revamped', 'Modern Farm']}}))
    print(f'Built {len(frames)} companion frames in {OUT}')
if __name__ == '__main__': main()
