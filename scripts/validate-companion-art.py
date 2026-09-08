#!/usr/bin/env python3
"""Check every shipped action cell for empty/clipped art and render review sheets.

Usage: python3 scripts/validate-companion-art.py [--preview-dir /tmp/companion-art]
Requires Pillow; run build-companion-assets.py first. Previews remain local.
"""
import argparse
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]

# Minimum width, in px, of the lowest opaque row of a cell. A seated body ends in a
# flat-ish foot/shoe base (measured at 16px across all five identities once the sit
# pose was wired in correctly); a body that tapers to a limb-tip or a dangling prop
# corner narrows to single digits. This is what actually distinguishes the "floating
# half body, no legs" regression from a real seated frame -- overall bbox height does
# NOT: it stayed 40-44px either way, because it is dominated by the notebook/cup/can,
# not the body. Checked against 'write' and 'drink' only: 'water' legitimately ends
# in a thin trickle or single drip in its last frames (see companion-art.ts's comment
# on lowering the can after pouring), which would make this assertion fire on
# correct art too.
FOOT_BASE_MIN_WIDTH = 10

def last_row_width(cell, bounds):
    alpha = cell.split()[3]
    y = bounds[3] - 1
    xs = [x for x in range(bounds[0], bounds[2]) if alpha.getpixel((x, y)) > 10]
    return xs[-1] - xs[0] + 1 if xs else 0

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--preview-dir', type=Path)
    args = parser.parse_args()
    sheets = [Image.new('RGBA', (14 * 144, 5 * 150), '#dddcc8') for _ in range(3)]
    count = 0
    for resident, number in enumerate([1, 3, 6, 9, 12]):
        path = ROOT / f'frontend/public/assets/town/characters/c{number:02d}-actions.png'
        with Image.open(path) as source:
            assert source.size == (1344, 288), f'{path}: wrong grid'
            for row, action in enumerate(['write', 'drink', 'water']):
                distinct = set()
                for frame in range(14):
                    cell = source.crop((frame * 96, row * 96, (frame + 1) * 96, (row + 1) * 96))
                    bounds = cell.getbbox()
                    assert bounds and 0 < bounds[0] < bounds[2] < 96 and 0 < bounds[1] < bounds[3] < 96, f'{path}: {action} frame {frame} empty or clipped'
                    if action != 'water':
                        width = last_row_width(cell, bounds)
                        assert width >= FOOT_BASE_MIN_WIDTH, f'{path}: {action} frame {frame} has no foot base (lowest row is {width}px wide, floating body?)'
                    distinct.add(cell.tobytes())
                    if args.preview_dir:
                        sheets[row].alpha_composite(cell.resize((144, 144), Image.Resampling.NEAREST), (frame * 144, resident * 150))
                        ImageDraw.Draw(sheets[row]).text((frame * 144 + 4, resident * 150 + 4), f'c{number:02d} / {frame + 1}', fill='#52604e')
                    count += 1
                assert len(distinct) > 1, f'{path}: {action} has no animation'
    if args.preview_dir:
        args.preview_dir.mkdir(parents=True, exist_ok=True)
        for sheet, action in zip(sheets, ['write', 'drink', 'water']): sheet.save(args.preview_dir / f'{action}-frames.png')
    print(f'Validated {count} action frames: five identities, three loops, no blank or clipped cells.')

if __name__ == '__main__': main()
