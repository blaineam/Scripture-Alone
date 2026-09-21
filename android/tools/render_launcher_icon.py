#!/usr/bin/env python3
"""Renders the Android adaptive launcher icon from the iOS icon, so both launchers show one icon.

    python3 android/tools/render_launcher_icon.py

Icon Composer's `ictool` renders `ScriptureAlone/Resources/AppIcon.icon` twice, over a pure black
and a pure white fill. The difference between the two gives each pixel's coverage, which separates
the artwork — sun, starburst, open book, with their glass lighting — from the fill: an adaptive icon
wants its foreground and background as separate layers, because the launcher masks and moves them.
A transparent fill can't do this; the glass layers render grey over it.

Writes `mipmap-*/ic_launcher_foreground.png` and `ic_launcher_monochrome.png` (the silhouette
Android 13 tints for themed icons). The 1024 px render maps onto the visible 72 dp of the 108 dp
layer. The background gradient in `drawable/ic_launcher_background.xml` was sampled from the same
render (top #0C112E, bottom #273364); re-sample it if the icon's fill changes. Needs Pillow.
"""
import json
import os
import shutil
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

REPO = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
ICON = os.path.join(REPO, "ScriptureAlone", "Resources", "AppIcon.icon")
RES = os.path.join(REPO, "android", "app", "src", "main", "res")
ICTOOL = "/Applications/Xcode.app/Contents/Applications/Icon Composer.app/Contents/Executables/ictool"
SIZE = 1024
DENSITIES = [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]


def render_over(level: str, work: str) -> Image.Image:
    """The icon rendered with its fill replaced by one solid grey `level` (0 or 1)."""
    doc = os.path.join(work, f"fill-{level}.icon")
    shutil.copytree(ICON, doc)
    path = os.path.join(doc, "icon.json")
    with open(path) as f:
        spec = json.load(f)
    spec.pop("fill-specializations", None)
    spec["fill"] = {"solid": f"srgb:{level},{level},{level},1.00000"}
    with open(path, "w") as f:
        json.dump(spec, f, indent=2)
    out = os.path.join(work, f"over-{level}.png")
    subprocess.run([ICTOOL, doc, "--export-image", "--output-file", out, "--platform", "iOS",
                    "--rendition", "Default", "--width", str(SIZE), "--height", str(SIZE), "--scale", "1"],
                   check=True, stdout=subprocess.DEVNULL)
    return Image.open(out).convert("RGBA")


def artwork(black: Image.Image, white: Image.Image) -> Image.Image:
    """Coverage from the black/white difference; colour un-premultiplied from the black render."""
    # The glass rim along the rounded square's edge belongs to the iOS mask, not the artwork.
    inner = Image.new("L", (SIZE, SIZE), 0)
    ImageDraw.Draw(inner).rounded_rectangle((70, 70, SIZE - 70, SIZE - 70), radius=180, fill=255)
    b, w, keep = black.load(), white.load(), inner.load()
    out = Image.new("RGBA", (SIZE, SIZE))
    o = out.load()
    for y in range(SIZE):
        for x in range(SIZE):
            br, bg, bb, ba = b[x, y]
            wr, wg, wb, _ = w[x, y]
            a = 1 - ((wr - br) + (wg - bg) + (wb - bb)) / 3 / 255
            a = max(0.0, min(1.0, a)) * ba / 255
            if a < 0.004 or not keep[x, y]:
                o[x, y] = (0, 0, 0, 0)
                continue
            o[x, y] = (min(255, round(br / a)), min(255, round(bg / a)), min(255, round(bb / a)), round(a * 255))
    return out


def main() -> int:
    if not os.path.exists(ICTOOL):
        print(f"missing {ICTOOL} (Icon Composer ships with Xcode)", file=sys.stderr)
        return 1
    with tempfile.TemporaryDirectory() as work:
        glyph = artwork(render_over("0.00000", work), render_over("1.00000", work))
    mono = Image.new("RGBA", glyph.size, (255, 255, 255, 0))
    mono.putalpha(glyph.getchannel("A"))
    for name, px in DENSITIES:
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        inner = round(px * 72 / 108)
        for source, filename in [(glyph, "ic_launcher_foreground.png"), (mono, "ic_launcher_monochrome.png")]:
            canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
            canvas.paste(source.resize((inner, inner), Image.LANCZOS), ((px - inner) // 2, (px - inner) // 2))
            canvas.save(os.path.join(folder, filename), optimize=True)
    print("wrote", ", ".join(f"mipmap-{n}" for n, _ in DENSITIES))
    return 0


if __name__ == "__main__":
    sys.exit(main())
