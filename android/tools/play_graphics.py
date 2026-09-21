#!/usr/bin/env python3
"""Google Play listing graphics: the 512 px app icon, the 1024x500 feature graphic, and the
screenshot post-processing (flatten to RGB, Wear downscale, size checks).

    python3 android/tools/play_graphics.py graphics <out_dir> [glyph_cache.png]
    python3 android/tools/play_graphics.py shots <out_dir> <manifest.json>

The icon artwork is re-rendered at 1024 px the way render_launcher_icon.py does it (Icon Composer's
ictool over black and white, coverage from the difference); pass a cache path to reuse a render.
Needs Pillow and Xcode's Icon Composer.
"""
import json
import os
import sys
import tempfile

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import render_launcher_icon as rli  # noqa: E402

FONTS = os.path.join(HERE, "..", "app", "src", "main", "res", "font")
TOP, BOTTOM = (0x0C, 0x11, 0x2E), (0x27, 0x33, 0x64)


def glyph(cache: str | None) -> Image.Image:
    if cache and os.path.exists(cache):
        return Image.open(cache).convert("RGBA")
    with tempfile.TemporaryDirectory() as work:
        art = rli.artwork(rli.render_over("0.00000", work), rli.render_over("1.00000", work))
    if cache:
        art.save(cache)
    return art


def gradient(w: int, h: int) -> Image.Image:
    """The icon's night sky: #0C112E at the top to #273364 at the bottom."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        c = tuple(round(a + (b - a) * t) for a, b in zip(TOP, BOTTOM))
        for x in range(w):
            px[x, y] = c
    return img


def icon512(art: Image.Image) -> Image.Image:
    base = gradient(1024, 1024).convert("RGBA")
    base.alpha_composite(art)
    return base.convert("RGB").resize((512, 512), Image.LANCZOS)


def font(name: str, size: int, weight: int | None = None) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(os.path.join(FONTS, name), size)
    if weight is not None:
        try:
            f.set_variation_by_axes([weight])
        except Exception:
            pass
    return f


def feature(art: Image.Image) -> Image.Image:
    W, H = 1024, 500
    img = gradient(W, H).convert("RGBA")
    # The artwork, cropped to its visible content.
    box = art.getchannel("A").point(lambda a: 255 if a > 8 else 0).getbbox()
    mark = art.crop(box)
    scale = 290 / max(mark.size)
    mark = mark.resize((round(mark.width * scale), round(mark.height * scale)), Image.LANCZOS)

    title = font("source_serif_4.ttf", 68, 600)
    tag = font("inter.ttf", 31, 400)
    d = ImageDraw.Draw(img)
    t1, t2 = "Scripture Alone", "A private, offline Bible"
    tw = d.textbbox((0, 0), t1, font=title)
    sw = d.textbbox((0, 0), t2, font=tag)
    text_w = max(tw[2] - tw[0], sw[2] - sw[0])
    gap = 40
    group_w = mark.width + gap + text_w
    x0 = (W - group_w) // 2
    my = (H - mark.height) // 2
    # A soft warm glow behind the sun, echoing the icon's light.
    glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gx, gy, r = x0 + mark.width // 2, my + mark.height * 2 // 5, 170
    ImageDraw.Draw(glow).ellipse((gx - r, gy - r, gx + r, gy + r), fill=(255, 196, 110, 60))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(70)))
    img.alpha_composite(mark, (x0, my))
    tx = x0 + mark.width + gap
    th = tw[3] - tw[1]
    sh = sw[3] - sw[1]
    block = th + 26 + sh
    ty = (H - block) // 2
    d.text((tx - tw[0], ty - tw[1]), t1, font=title, fill=(248, 240, 222))
    rule_y = ty + th + 13
    d.line((tx, rule_y, tx + 64, rule_y), fill=(226, 176, 92), width=3)
    d.text((tx - sw[0], ty + th + 26 - sw[1]), t2, font=tag, fill=(214, 206, 230))
    return img.convert("RGB")


def shots(out: str, manifest: str) -> None:
    """manifest: {"<dest relative path>": {"src": path, "resize": [w, h]?}}"""
    spec = json.load(open(manifest))
    for dest, item in spec.items():
        im = Image.open(item["src"])
        if im.mode != "RGB":
            im = im.convert("RGB")
        if "resize" in item:
            im = im.resize(tuple(item["resize"]), Image.LANCZOS)
        path = os.path.join(out, dest)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        im.save(path, optimize=True)
        print(f"{dest}: {im.size[0]}x{im.size[1]} {im.mode}")


def main() -> int:
    cmd, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    if cmd == "graphics":
        art = glyph(sys.argv[3] if len(sys.argv) > 3 else None)
        icon512(art).save(os.path.join(out, "icon-512.png"), optimize=True)
        feature(art).save(os.path.join(out, "feature-graphic.png"), optimize=True)
        print("wrote icon-512.png, feature-graphic.png")
    elif cmd == "shots":
        shots(out, sys.argv[3])
    return 0


if __name__ == "__main__":
    sys.exit(main())
