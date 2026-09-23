#!/usr/bin/env python3
"""Draws the sample sermon slide the App Store rig imports for the camera-notes screenshot.

The slide is invented demo content (no real church, speaker or date) photographed from a pew:
a 16:9 slide in a dim room, seen slightly off-axis. `Tools/capture_screenshots.sh` copies the
JPEG into the simulator app's temporary directory; the DEBUG `sermon-notes` scene hands it to
`SlideCapture.accept(data:)` — the path a photo picked from Photos takes — so the review sheet
shows what on-device text recognition really reads from it.

    python3 Tools/make_sample_slide.py   # writes docs/appstore-screenshots/sample-slide.jpg
                                          # and sample-slide.<locale>.jpg for the big 8

Each locale's slide is the same sermon in that language, the passages named as that language's Bible
names them, so a localized screenshot shows a French church's slide, not an English one.
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


# (title, passages, three points, footer) per ASC locale; English is the default slide.
TEXT = {
    None: ("The Good Shepherd", "John 10:11–18  ·  Psalm 23:1–6",
           ["He knows his own by name", "He lays down his life for the sheep", "One flock, one shepherd"],
           "Sunday Morning Series  ·  Week 4"),
    "fr-FR": ("Le bon berger", "Jean 10:11–18  ·  Psaumes 23:1–6",
              ["Il connaît les siens par leur nom", "Il donne sa vie pour ses brebis", "Un seul troupeau, un seul berger"],
              "Série du dimanche matin  ·  Semaine 4"),
    "de-DE": ("Der gute Hirte", "Johannes 10,11–18  ·  Psalm 23,1–6",
              ["Er kennt die Seinen beim Namen", "Er lässt sein Leben für die Schafe", "Eine Herde, ein Hirte"],
              "Predigtreihe am Sonntagmorgen  ·  Woche 4"),
    "es-ES": ("El buen pastor", "Juan 10:11–18  ·  Salmos 23:1–6",
              ["Conoce a los suyos por su nombre", "Da su vida por las ovejas", "Un rebaño, un pastor"],
              "Serie del domingo por la mañana  ·  Semana 4"),
    "pt-BR": ("O bom pastor", "João 10:11–18  ·  Salmos 23:1–6",
              ["Conhece as suas ovelhas pelo nome", "Dá a vida pelas ovelhas", "Um rebanho, um pastor"],
              "Série de domingo de manhã  ·  Semana 4"),
    "it": ("Il buon pastore", "Giovanni 10:11–18  ·  Salmi 23:1–6",
           ["Conosce le sue pecore per nome", "Dà la vita per le pecore", "Un solo gregge, un solo pastore"],
           "Serie della domenica mattina  ·  Settimana 4"),
    "zh-Hans": ("好牧人", "约翰福音 10:11–18  ·  诗篇 23:1–6",
                ["他按着名叫自己的羊", "好牧人为羊舍命", "一群羊，一个牧人"], "主日早晨系列  ·  第4周"),
    "ja": ("善き牧者", "ヨハネ傳福音書 10:11–18  ·  詩篇 23:1–6",
           ["自分の羊の名を呼ぶ", "羊のために命を捨てる", "一つの群れ、一人の牧者"], "日曜礼拝シリーズ  ·  第4週"),
    "ko": ("선한 목자", "요한복음 10:11–18  ·  시편 23:1–6",
           ["자기 양의 이름을 부르신다", "양을 위하여 목숨을 버리신다", "한 무리, 한 목자"], "주일 오전 시리즈  ·  4주차"),
}
# The Latin-script fonts have no CJK glyphs.
CJK_FONT = {"zh-Hans": ("Hiragino Sans GB.ttc", 0), "ja": ("Hiragino Sans GB.ttc", 0), "ko": ("AppleSDGothicNeo.ttc", 0)}


def slide(locale=None) -> Image.Image:
    title, passages, points, footer = TEXT[locale]
    top, bottom = (34, 35, 79), (104, 70, 110)  # the icon's indigo-to-plum dawn
    image = Image.new("RGB", (SLIDE_W, SLIDE_H))
    draw = ImageDraw.Draw(image)
    for y in range(SLIDE_H):
        t = y / (SLIDE_H - 1)
        draw.line([(0, y), (SLIDE_W, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(top, bottom)))

    if locale in CJK_FONT:
        name, index = CJK_FONT[locale]
        serif, sans, body, small = font(name, 120, index), font(name, 56, index), font(name, 52, index), font(name, 38, index)
    else:
        serif = font("NewYork.ttf", 132)
        sans = font("Avenir Next.ttc", 60, index=5)   # Demi Bold
        body = font("Avenir Next.ttc", 54, index=0)   # Regular
        small = font("Avenir Next.ttc", 38, index=0)
    cream, sun = (251, 248, 241), (240, 190, 128)

    draw.text((150, 150), title, font=serif, fill=cream)
    draw.text((154, 330), passages, font=sans, fill=sun)
    draw.line([(154, 432), (420, 432)], fill=sun, width=5)
    for i, line in enumerate(points):
        y = 500 + i * 110
        draw.ellipse([(160, y + 22), (180, y + 42)], fill=sun)
        draw.text((215, y), line, font=body, fill=cream)
    draw.text((154, 930), footer, font=small, fill=(200, 196, 214))
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
    for locale in (k for k in TEXT if k):
        path = OUT.with_name(f"sample-slide.{locale}.jpg")
        photograph(slide(locale)).save(path, quality=88)
        print(f"wrote {path.relative_to(ROOT)}")
