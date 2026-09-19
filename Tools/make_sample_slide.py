#!/usr/bin/env python3
"""Draws the sample sermon slide the App Store rig imports for the camera-notes screenshot.

The slide is invented demo content (no real church, speaker or date) photographed from a pew:
a 16:9 slide in a dim room, seen slightly off-axis. `Tools/capture_screenshots.sh` copies the
JPEG into the simulator app's temporary directory; the DEBUG `sermon-notes` scene hands it to
`SlideCapture.accept(data:)` — the path a photo picked from Photos takes — so the review sheet
shows what on-device text recognition really reads from it.

    python3 Tools/make_sample_slide.py   # writes docs/appstore-screenshots/sample-slide.jpg
"""
import pathlib

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = ROOT / "docs/appstore-screenshots/sample-slide.jpg"
FONTS = pathlib.Path("/System/Library/Fonts")

SLIDE_W, SLIDE_H = 1920, 1080
PHOTO_W, PHOTO_H = 2016, 1512  # a 4:3 phone photo


def font(name: str, size: int, index: int = 0) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONTS / name), size, index=index)


def slide() -> Image.Image:
    top, bottom = (34, 35, 79), (104, 70, 110)  # the icon's indigo-to-plum dawn
    image = Image.new("RGB", (SLIDE_W, SLIDE_H))
    draw = ImageDraw.Draw(image)
    for y in range(SLIDE_H):
        t = y / (SLIDE_H - 1)
        draw.line([(0, y), (SLIDE_W, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))

    serif = font("NewYork.ttf", 132)
    sans = font("Avenir Next.ttc", 60, index=5)   # Demi Bold
    body = font("Avenir Next.ttc", 54, index=0)   # Regular
    cream, sun = (251, 248, 241), (240, 190, 128)

    draw.text((150, 150), "The Good Shepherd", font=serif, fill=cream)
    draw.text((154, 330), "John 10:11–18  ·  Psalm 23:1–6", font=sans, fill=sun)
    draw.line([(154, 432), (420, 432)], fill=sun, width=5)
    for i, line in enumerate(["He knows his own by name",
                              "He lays down his life for the sheep",
                              "One flock, one shepherd"]):
        y = 500 + i * 110
        draw.ellipse([(160, y + 22), (180, y + 42)], fill=sun)
        draw.text((215, y), line, font=body, fill=cream)
    draw.text((154, 930), "Sunday Morning Series  ·  Week 4", font=font("Avenir Next.ttc", 38, index=0),
              fill=(200, 196, 214))
    return image


def photograph(slide_image: Image.Image) -> Image.Image:
    """The slide on a screen in a dim room, taken a little off-axis from a few rows back."""
    room = Image.new("RGB", (PHOTO_W, PHOTO_H), (22, 20, 26))
    glow = Image.new("L", (PHOTO_W, PHOTO_H), 0)
    ImageDraw.Draw(glow).ellipse([(160, 180), (PHOTO_W - 160, PHOTO_H - 220)], fill=70)
    glow = glow.filter(ImageFilter.GaussianBlur(160))
    room.paste(Image.new("RGB", room.size, (70, 62, 86)), mask=glow)

    # Where the slide's corners land in the photo: a gentle keystone and tilt.
    quad = [(190, 300), (1850, 250), (1880, 1215), (170, 1190)]  # TL, TR, BR, BL
    coeffs = perspective_coefficients(quad, [(0, 0), (SLIDE_W, 0), (SLIDE_W, SLIDE_H), (0, SLIDE_H)])
    warped = slide_image.transform(room.size, Image.Transform.PERSPECTIVE, coeffs, Image.Resampling.BICUBIC)
    mask = Image.new("L", room.size, 0)
    ImageDraw.Draw(mask).polygon(quad, fill=255)
    room.paste(warped, mask=mask.filter(ImageFilter.GaussianBlur(1.5)))
    return room.filter(ImageFilter.GaussianBlur(0.6))


def perspective_coefficients(dst, src):
    """PIL's PERSPECTIVE maps output (dst) pixels back to input (src) pixels: solve the 8x8 system."""
    rows = []
    for (x, y), (u, v) in zip(dst, src):
        rows.append([x, y, 1, 0, 0, 0, -u * x, -u * y, u])
        rows.append([0, 0, 0, x, y, 1, -v * x, -v * y, v])
    n = 8
    for col in range(n):  # Gauss-Jordan with partial pivoting
        pivot = max(range(col, n), key=lambda r: abs(rows[r][col]))
        rows[col], rows[pivot] = rows[pivot], rows[col]
        lead = rows[col][col]
        rows[col] = [value / lead for value in rows[col]]
        for r in range(n):
            if r != col and rows[r][col]:
                factor = rows[r][col]
                rows[r] = [a - factor * b for a, b in zip(rows[r], rows[col])]
    return [row[n] for row in rows]


if __name__ == "__main__":
    OUT.parent.mkdir(parents=True, exist_ok=True)
    photograph(slide()).save(OUT, quality=88)
    print(f"wrote {OUT.relative_to(ROOT)}")
