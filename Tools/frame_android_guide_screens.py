#!/usr/bin/env python3
"""The User Guide's Android pictures, from the Play listing captures (docs/manual/README.md).

    python3 Tools/frame_android_guide_screens.py [guide locale ...]     # none given: all nine

Reads android/play-assets/<Play locale>/phone/*.png and wear/02-verse.png (android/tools/play_capture.py;
see android/play-assets/README.md) and writes docs/manual/images/<guide locale>/android/:

  * six phone screens in Monkr's Pixel 7 Pro (obsidian) bezel, upscaled x2.25 — screen hole 480x1039
    at 60,131, corner 26 — the 1080x2160 capture scaled to fill the hole and cropped evenly at the
    sides, framed by Tools/frame_manual_screens.swift at 640 px wide (transparent around the device);
  * the watch's verse, cut to a circle.

Then pngquant --quality 65-90 on each. Needs swiftc, pngquant, Pillow, and Monkr's device art at
~/Documents/scripts/monkr/static/devices (MONKR_DEVICES overrides).
"""
import os
import subprocess
import sys
import tempfile

from PIL import Image, ImageDraw

ROOT = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
DEVICES = os.environ.get("MONKR_DEVICES", os.path.expanduser("~/Documents/scripts/monkr/static/devices"))
BEZEL = os.path.join(DEVICES, "pixel-7-pro", "obsidian.png")
K = 2.25                                      # bezel upscale, so the screen hole is near the capture's size
HOLE = (60 * K, 131 * K, 480 * K, 1039 * K)   # x, y, width, height in the upscaled bezel
CORNER = 26 * K
OUT_WIDTH = 640

# Guide locale -> Play locale
LOCALES = {"en": "en-US", "de-DE": "de-DE", "es-ES": "es-ES", "fr-FR": "fr-FR", "it": "it-IT",
           "ja": "ja-JP", "ko": "ko-KR", "pt-BR": "pt-BR", "zh-Hans": "zh-CN"}

# Guide picture -> phone capture (play_capture.py's PHONE_SCENES numbering)
PHONE = {"01-reader": "01-reader", "02-topics": "02-topics", "03-study": "04-study-context",
         "05-sermon-notes": "07-sermon-slide", "06-highlights": "05-highlights", "09-share": "08-share-image"}


def pngquant(path: str) -> None:
    subprocess.run(["pngquant", "--quality", "65-90", "--force", "--ext", ".png", path], check=True)


def main() -> int:
    locales = sys.argv[1:] or list(LOCALES)
    unknown = [l for l in locales if l not in LOCALES]
    if unknown:
        raise SystemExit(f"unknown guide locale(s): {' '.join(unknown)} (one of {' '.join(LOCALES)})")
    with tempfile.TemporaryDirectory() as tmp:
        frame = os.path.join(tmp, "frame")
        subprocess.run(["swiftc", "-O", os.path.join(ROOT, "Tools", "frame_manual_screens.swift"), "-o", frame],
                       check=True)
        bezel = Image.open(BEZEL).convert("RGBA")
        big = os.path.join(tmp, "bezel.png")
        bezel.resize((round(bezel.width * K), round(bezel.height * K)), Image.LANCZOS).save(big)
        hx, hy, hw, hh = HOLE
        w, h = round(hw), round(hh) + 1
        for guide in locales:
            play = LOCALES[guide]
            src = os.path.join(ROOT, "android", "play-assets", play)
            out = os.path.join(ROOT, "docs", "manual", "images", guide, "android")
            os.makedirs(out, exist_ok=True)
            for name, capture in PHONE.items():
                shot = Image.open(os.path.join(src, "phone", f"{capture}.png")).convert("RGB")
                scale = max(w / shot.width, h / shot.height)
                r = shot.resize((round(shot.width * scale), round(shot.height * scale)), Image.LANCZOS)
                left, top = (r.width - w) // 2, (r.height - h) // 2
                fitted = os.path.join(tmp, "shot.png")
                r.crop((left, top, left + w, top + h)).save(fitted)
                dst = os.path.join(out, f"{name}.png")
                subprocess.run([frame, big, fitted, str(hx), str(hy), str(CORNER), str(OUT_WIDTH), dst], check=True)
                pngquant(dst)
            watch = Image.open(os.path.join(src, "wear", "02-verse.png")).convert("RGBA")
            n, ss = watch.width, 4
            mask = Image.new("L", (n * ss, n * ss), 0)
            ImageDraw.Draw(mask).ellipse((0, 0, n * ss - 1, n * ss - 1), fill=255)
            watch.putalpha(mask.resize((n, n), Image.LANCZOS))
            dst = os.path.join(out, "w02-verse.png")
            watch.save(dst)
            pngquant(dst)
            print(guide, "ok")
    return 0


if __name__ == "__main__":
    sys.exit(main())
