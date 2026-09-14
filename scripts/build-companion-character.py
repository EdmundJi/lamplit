#!/usr/bin/env python3
"""Synthesize new companion identities from LimeZu's layered Character Generator parts (body +
outfit + hairstyle + accessory + eyes) instead of picking one of the 20 premade characters.
docs/01: 25 appearances = 20 ready-made + 5 synthesised from these parts (200 hairstyles, 132
outfits, 84 accessories, 9 bodies, 7 eyes). The licence allows editing, forbids resale/redistribution
- this script only ever reads a locally purchased zip and writes locally, same as the other
scripts/build-*.py.

The checked-in companion-character-specs.json is the reviewed, deterministic recipe for c21-c25.
Generated PNGs stay ignored with the other licensed derivatives; rebuilding from the local pack
produces the same five identities without redistributing the vendor layers.

Usage:
    python3 scripts/build-companion-character.py tmp/moderninteriors-win.zip --inspect
    python3 scripts/build-companion-character.py tmp/moderninteriors-win.zip --spec characters.json

--spec takes a JSON list of {"id": "c21", "body": "...png", "outfit": "...png",
"hairstyle": "...png", "accessory": "...png", "eyes": "...png"} (accessory optional) naming files
under each LAYER_FOLDERS entry - fill these in from inspect_pack()'s real listing, never guessed.
"""
from __future__ import annotations

import argparse
import io
import json
import sys
import zipfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'frontend/public/assets/town/characters'
DEFAULT_SPEC = ROOT / 'scripts/companion-character-specs.json'

# Unverified assumption (see module docstring): inferred by analogy with the one sibling path this
# repo has already confirmed against the real zip - build-town-assets.py's CHARACTERS constant,
# "2_Characters/Character_Generator/0_Premade_Characters/32x32/Premade_Character_32x32_{i:02d}.png".
# The premade characters are documented (docs/01) as vendor-composed from these same layered parts,
# so the sibling folders are assumed to sit next to "0_Premade_Characters" under the same
# Character_Generator root, numbered similarly - inspect_pack() checks this before compose() ever
# trusts it.
GENERATOR_ROOT = '2_Characters/Character_Generator/'
LAYER_FOLDERS = {
    'body': 'Bodies/32x32/',
    'eyes': 'Eyes/32x32/',
    'hairstyle': 'Hairstyles/32x32/',
    'outfit': 'Outfits/32x32/',
    'accessory': 'Accessories/32x32/',
}
# MEASURED against the real pack on 2026-09-13, not inferred. Opening
# tmp/moderninteriors-win.zip and reading every PNG header under Character_Generator/*/32x32/:
#
#   0_Premade_Characters  20 sheets   1792x1312   = 56 cols x 41 rows of 32px, exactly
#   Hairstyles           200 sheets   1792x1312
#   Outfits              132 sheets   1792x1312
#   Eyes                   7 sheets   1792x1312
#   Bodies                 9 sheets   1854x1312   <- WIDER, and 1854/32 = 57.94, not a whole grid
#   Accessories           84 sheets   1792x1312 (80) and 1854x1312 (4)
#
# So docs/01's counts are all exactly right (20 ready-made, 200 hairstyles, 132 outfits, 84
# accessories, 9 bodies, 7 eyes) and its "56 列" is right for every layer EXCEPT bodies.
#
# This is the 坑 docs/01 predicted nobody had tried ("56 列全帧对齐有没有坑，没人试过"): a body sheet
# is 62px wider than everything it has to be stacked with. The question that actually matters is
# whether that means a different grid or merely extra frames on the right, and it is the second -
# measured by walking both sheets cell by cell over the shared 56x41 region: every outfit cell with
# ink and every eyes cell with ink lands on a cell where the body also has ink (0 exceptions, out of
# 460 outfit cells and 337 eyes cells). Layers share the origin and the grid; bodies just carry
# extra animation columns past column 56.
#
# Therefore the compositor must align on the shared top-left 56-column region and must NOT require
# equal sheet sizes. An earlier version of this file raised a hard error on any size mismatch, which
# reads like caution and would have rejected the real pack on its very first run.
EXPECTED_COLUMNS = 56
# One cell is 32x32, measured: every 1792x1312 sheet is exactly 56 x 41 of them. Note 1312/64 =
# 20.5, so these sheets are NOT on a 32x64 grid however tall a walking figure looks - an earlier
# guess of (32, 64) here would have sliced every frame in half.
FRAME_PIXELS = 32
FRAME_ROWS = 41
RUNTIME_HEIGHT = 8 * 64  # idle/walk/sleep/sit/read are the only Phaser rows the town loads
LAYER_ORDER = ('body', 'outfit', 'hairstyle', 'accessory', 'eyes')


def inspect_pack(zip_path: Path) -> bool:
    """Print the real folder tree under Character_Generator and one sample sheet's real size per
    layer, explicitly PASS/FAIL against FRAME_PIXELS/FRAME_ROWS and EXPECTED_COLUMNS - never assume they hold.
    Returns True only if every layer folder was found and every sampled sheet matched exactly."""
    ok = True
    with zipfile.ZipFile(zip_path) as pack:
        names = [n for n in pack.namelist() if n.startswith(GENERATOR_ROOT)]
        if not names:
            print(f'FAIL: no entries under {GENERATOR_ROOT!r} in {zip_path.name} - the assumed root itself is wrong.')
            return False
        top_level = sorted({n[len(GENERATOR_ROOT):].split('/')[0] for n in names if len(n) > len(GENERATOR_ROOT)})
        print(f'Found {len(names)} entries under {GENERATOR_ROOT}. Real top-level folders:')
        for folder in top_level:
            print(f'  {folder}')
        print()
        for layer, folder in LAYER_FOLDERS.items():
            path = GENERATOR_ROOT + folder
            candidates = sorted(n for n in names if n.startswith(path) and n.lower().endswith('.png'))
            if not candidates:
                print(f'FAIL      {layer:10s} {path!r}: no .png found under this assumed path (see real folders above).')
                ok = False
                continue
            sample = candidates[0]
            with Image.open(io.BytesIO(pack.read(sample))) as image:
                w, h = image.size
            columns = w // FRAME_PIXELS
            # Bodies are legitimately wider than the shared region (measured: 1854 vs 1792) and
            # 1854 is not even a whole number of columns, so width is checked as "at least the shared
            # 56 columns", not as an exact multiple. Row count is checked exactly - that one really
            # would be a different grid.
            matches = w >= EXPECTED_COLUMNS * FRAME_PIXELS and h == FRAME_ROWS * FRAME_PIXELS
            ok = ok and matches
            print(f'{"OK" if matches else "MISMATCH":10s}{layer:10s} {sample}: {w}x{h}px -> {columns} columns of {FRAME_PIXELS}px, {h // FRAME_PIXELS} rows (need >={EXPECTED_COLUMNS} columns, exactly {FRAME_ROWS} rows)'
                  f' [{len(candidates)} files in this folder]')
    print()
    print('Every layer OK - safe to write a --spec and run --compose.' if ok else
          'At least one FAIL/MISMATCH above - fix LAYER_FOLDERS/FRAME_PIXELS/FRAME_ROWS/EXPECTED_COLUMNS to match the real folders first. Do not run --compose yet.')
    return ok


def compose(zip_path: Path, spec_path: Path, out: Path = OUT) -> None:
    """Layer body -> outfit -> hairstyle -> accessory -> eyes into one sheet per character, same
    frame format as c01.png..c20.png. Refuses to composite anything whose layer sheets disagree on
    frame geometry: a silent size mismatch between two "aligned" sheets is exactly the misaligned-
    limb bug docs/01 asked this pipeline to check for before it ever reaches a screenshot - it must
    fail the build, not draw a bad frame that only a person staring at it would catch (docs/05's
    wardrobe-as-table class of bug, applied to character art instead of furniture)."""
    specs = json.loads(spec_path.read_text())
    out.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as pack:
        for spec in specs:
            char_id = spec['id']
            layers: list[tuple[str, Image.Image]] = []
            for layer in LAYER_ORDER:
                filename = spec.get(layer)
                if not filename:
                    continue
                entry = GENERATOR_ROOT + LAYER_FOLDERS[layer] + filename
                image = Image.open(io.BytesIO(pack.read(entry))).convert('RGBA')
                layers.append((layer, image))
            if not layers:
                raise ValueError(f'{char_id}: spec named no layers at all')
            base_layer, base_image = layers[0]
            # Height must match exactly - a different row count really is a different grid. Width may
            # differ, because bodies legitimately carry extra frames past the shared 56 columns (see
            # the measurement at the top of this file); what must hold is that every layer is at
            # least as wide as the shared region we compose over.
            shared_width = EXPECTED_COLUMNS * FRAME_PIXELS
            mismatched = [(name, image.size) for name, image in layers
                          if image.size[1] != base_image.size[1] or image.size[0] < shared_width]
            if mismatched:
                raise ValueError(
                    f'{char_id}: layer sheets do not share one frame grid - {base_layer} is {base_image.size}, '
                    f'but {mismatched} disagree on row count or are narrower than the shared '
                    f'{shared_width}px region. Widths above that may differ (bodies do); heights may not; '
                    f'fix LAYER_FOLDERS or the spec before compositing anything.')
            # Composed at the shared width, not the base layer's own width: the extra body-only frames
            # past column 56 have no outfit, hair or eyes to wear and would be a half-dressed figure.
            composed = Image.new('RGBA', (EXPECTED_COLUMNS * FRAME_PIXELS, base_image.size[1]))
            for _, image in layers:
                composed.alpha_composite(image)
            runtime_sheet = composed.crop((0, 0, composed.width, RUNTIME_HEIGHT))
            runtime_sheet.save(out / f'{char_id}.png', optimize=True)
            # The composed sheet's own size, not the base layer's - a body base is wider than what we
            # actually write (see the measurement at the top), and reporting the input size here would
            # have quietly claimed a 1854px sheet while writing a 1792px one.
            print(f'Composed {char_id}.png from {[name for name, _ in layers]} '
                  f'({runtime_sheet.size[0]}x{runtime_sheet.size[1]}px runtime sheet; source grid {composed.size[0]}x{composed.size[1]})')


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('pack', type=Path, help='tmp/moderninteriors-win.zip (or wherever the Character Generator layers actually ship)')
    parser.add_argument('--inspect', action='store_true', help='print the real folder/sheet layout and exit; composes nothing')
    parser.add_argument('--spec', type=Path, help='JSON list of {id, body, outfit, hairstyle, accessory, eyes} filenames, hand-filled from --inspect\'s real listing')
    parser.add_argument('--defaults', action='store_true', help='compose the checked-in c21-c25 recipe')
    parser.add_argument('--out', type=Path, default=OUT, help='character output directory')
    args = parser.parse_args()
    if not args.pack.exists():
        print(f'{args.pack} does not exist - this script has never been run in this environment; see the module docstring.')
        sys.exit(1)
    spec = DEFAULT_SPEC if args.defaults else args.spec
    if args.inspect or not spec:
        passed = inspect_pack(args.pack)
        if not spec:
            print('\n--spec was not given; ran --inspect only.')
        sys.exit(0 if passed else 1)
    compose(args.pack, spec, args.out)


if __name__ == '__main__':
    main()
