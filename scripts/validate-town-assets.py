#!/usr/bin/env python3
"""Validate licensed crops / atlas pixels and render room QA without touching game assets.

python3 scripts/validate-town-assets.py --out /tmp/town-room-review
Then inspect public-gym.png, cafe-interior.png and public-crops.png with view_image.
Images remain local: they contain licensed art and must not be committed.
"""
import argparse
import importlib.util
import json
import sys
from pathlib import Path
import zipfile
from PIL import Image, ImageDraw

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('town_assets', ROOT / 'scripts/build-town-assets.py')
build = importlib.util.module_from_spec(spec)
spec.loader.exec_module(build)


def render(room, frames, out):
    canvas = Image.new('RGBA', (room['cols'] * 32, room['rows'] * 32), room['backgroundColor'])
    for layer in ('floor', 'walls'):
        for y, row in enumerate(room['layers'][layer]):
            for x, frame in enumerate(row):
                if frame:
                    canvas.alpha_composite(frames[frame], (x * 32, y * 32))
    pieces = list(room['furniture'])
    for slot in room['slots']:
        for i in range(slot['max']):
            pieces.append(dict(frame=slot['frames'][i % len(slot['frames'])],
                               x=slot['anchor']['x'] + i * slot['step']['x'],
                               y=slot['anchor']['y'] + i * slot['step']['y'],
                               depth=slot.get('depth', slot['anchor']['y'])))
    for p in sorted(pieces, key=lambda p: p.get('depth', p['y'])):
        im = frames[p['frame']]
        size = (p.get('displayWidth', im.width), p.get('displayHeight', im.height))
        im = im.resize(size, Image.Resampling.NEAREST)
        canvas.alpha_composite(im, (round(p['x'] - im.width * p.get('originX', .5)), round(p['y'] - im.height * p.get('originY', 1))))
    canvas.resize((canvas.width * 2, canvas.height * 2), Image.Resampling.NEAREST).save(out / f"{room['id']}.png")
    d = ImageDraw.Draw(canvas)
    for r in room['collisions']:
        d.rectangle((r['x'], r['y'], r['x'] + r['w'] - 1, r['y'] + r['h'] - 1), outline='#ff6565')
    for door in room['doors']:
        r = door['rect']; d.rectangle((r['x'], r['y'], r['x']+r['w'], r['y']+r['h']), outline='#70ff9b')
    x, y = room['spawn']['x'], room['spawn']['y']
    d.rectangle((x-7, y-10, x+7, y), fill='#70ff9b')
    for p in room['furniture']:
        if p.get('interactive'):
            r = p['interactive']['hit']
            d.rectangle((r['x'], r['y'], r['x']+r['w'], r['y']+r['h']), outline='#67dfff')
    canvas.resize((canvas.width*2, canvas.height*2), Image.Resampling.NEAREST).save(out / f"{room['id']}-collision.png")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=Path('/tmp/town-room-review'))
    parser.add_argument('--assets', type=Path, default=build.OUT)
    args = parser.parse_args(); args.out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(ROOT / 'tmp/moderninteriors-win.zip') as z:
        frames = build.collect_interior(z)
        sheets = {k: build.load(z, p) for k, p in build.INTERIOR_SHEETS.items()}
    atlas = Image.open(args.assets / 'interior-atlas.png').convert('RGBA')
    meta = json.loads((args.assets / 'interior-atlas.json').read_text())['frames']
    public = [build._public_gym(), build._cafe_interior()]
    used = {p['frame'] for room in public for p in room['furniture']}
    used.update(f for room in public for s in room['slots'] for f in s['frames'])
    used.add('gymmat_1')
    for name, key, (x0, y0, x1, y1) in build.INTERIOR_PIECES:
        if name not in used: continue
        sheet = sheets[key]
        assert sheet is not None and 0 <= x0 <= x1 < sheet.width and 0 <= y0 <= y1 < sheet.height, name
        crop = frames[name]
        assert crop.getbbox(), f'{name}: transparent crop'
        # New crops are tightly bounded complete objects, not empty grid-cell guesses.
        if key == 'gym' or name.startswith('cafe_'):
            assert crop.getbbox() == (0, 0, crop.width, crop.height), f'{name}: unexpected padding'
        r = meta[name]['frame']
        actual = atlas.crop((r['x'], r['y'], r['x']+r['w'], r['y']+r['h']))
        assert actual.size == crop.size and actual.tobytes() == crop.tobytes(), f'{name}: stale atlas'
    assert frames['gymmat_1'].size == (32, 32)
    assert frames['gymmat_1'].getchannel('A').getextrema() == (255, 255)
    for room in public: render(room, frames, args.out)
    # Named native crops at 2x for human verification of complete silhouettes.
    contact = Image.new('RGBA', (960, ((len(used)+3)//4)*220), '#353944'); d = ImageDraw.Draw(contact)
    for i, name in enumerate(sorted(used)):
        im = frames[name]; scale = min(2, 228/im.width, 180/im.height)
        im = im.resize((int(im.width*scale), int(im.height*scale)), Image.Resampling.NEAREST)
        x, y = (i%4)*240, (i//4)*220
        d.text((x+4, y+4), name, fill='white'); contact.alpha_composite(im, (x+4, y+26))
    contact.save(args.out / 'public-crops.png')
    print(f'PASS {len(used)} public frames: source bounds, nonempty crops, atlas pixel equality; QA images -> {args.out}')


if __name__ == '__main__':
    main()
