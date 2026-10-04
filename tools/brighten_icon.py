"""Brighten a dark threat icon so it reads on the dark map.

The Russian photo pack ships a near-black carbon quadcopter for FPV (mean luminance ~51/255),
which is invisible against the map while every sibling icon sits around 180. Rather than repaint
the artwork, this lifts the mid-tones while keeping hue and alpha: the silhouette stops
disappearing and the type stays recognisable.

Usage:  python tools/brighten_icon.py <icon.webp> [target_mean]
"""

import sys
from PIL import Image, ImageEnhance


def mean_luma(img: Image.Image) -> float:
    px = img.convert("RGBA")
    total = 0.0
    n = 0
    for r, g, b, a in px.getdata():
        if a < 128:
            continue
        total += 0.2126 * r + 0.7152 * g + 0.0722 * b
        n += 1
    return total / n if n else 0.0


def main() -> None:
    if len(sys.argv) < 2:
        print(__doc__)
        raise SystemExit(2)
    path = sys.argv[1]
    # Gamma < 1 lifts shadows without washing the highlights out. 0.65 takes the Russian pack's
    # 51-luma FPV up to roughly the 90-140 family the other icons already sit in.
    gamma = float(sys.argv[2]) if len(sys.argv) > 2 else 0.65

    img = Image.open(path).convert("RGBA")
    before = mean_luma(img)

    rgb = img.convert("RGB")
    rgb = rgb.point(lambda v: min(255, int(255 * ((v / 255.0) ** gamma))))
    # A gentle contrast trim, so the lift does not turn it into a flat grey blob.
    rgb = ImageEnhance.Contrast(rgb).enhance(1.08)
    rgb = ImageEnhance.Color(rgb).enhance(1.1)

    out = rgb.convert("RGBA")
    out.putalpha(img.getchannel("A"))

    after = mean_luma(out)
    out.save(path, "WEBP", quality=95, method=6)
    print(f"{path}: mean luma {before:.1f} -> {after:.1f} (gamma {gamma})")


if __name__ == "__main__":
    main()