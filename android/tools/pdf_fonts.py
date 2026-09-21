"""Static instances of the reader's variable faces, for the notes PDF (ui/export/NotesPdfRenderer.kt).

Android's PDF writer (Skia) cannot embed a variable font: it draws every glyph of one as a Type 3
outline, per page, which made a 30-note export 3.9 MB and its text awkward to search. A static
instance embeds as an ordinary subset TrueType font, as Core Text's PDFs do on iOS.

Reads the bundled faces in app/src/main/res/font (already cut to Latin, Greek and Cyrillic by
subset_fonts.py) and writes, into app/src/main/assets/pdf-fonts:

  literata_regular.ttf, literata_bold.ttf, literata_italic.ttf   — Iowan Old Style's stand-in
  inter_regular.ttf, inter_semibold.ttf                          — San Francisco's stand-in

Same faces and licences as the reader's (OFL 1.1; see assets/licenses/). Needs fontTools
(`pip install fonttools`). Run from android/: python3 tools/pdf_fonts.py
"""
import os
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

FONTS = "app/src/main/res/font"
OUT = "app/src/main/assets/pdf-fonts"

JOBS = [
    # source, output, weight, family, style
    ("literata.ttf", "literata_regular.ttf", 400, "Literata", "Regular"),
    ("literata.ttf", "literata_bold.ttf", 700, "Literata", "Bold"),
    ("literata_italic.ttf", "literata_italic.ttf", 400, "Literata", "Italic"),
    ("inter.ttf", "inter_regular.ttf", 400, "Inter", "Regular"),
    ("inter.ttf", "inter_semibold.ttf", 600, "Inter", "SemiBold"),
]

os.makedirs(OUT, exist_ok=True)
for source, output, weight, family, style in JOBS:
    font = TTFont(os.path.join(FONTS, source))
    axes = {axis.axisTag: axis for axis in font["fvar"].axes}
    location = {tag: (weight if tag == "wght" else axis.defaultValue) for tag, axis in axes.items()}
    static = instancer.instantiateVariableFont(font, location, updateFontNames=False)
    # The instance's own names, so a PDF lists "Literata-Bold" rather than the variable default's.
    names = static["name"]
    for record in list(names.names):
        if record.nameID in (1, 2, 4, 6, 16, 17):
            names.removeNames(nameID=record.nameID)
    for name_id, value in ((1, family), (2, style), (4, f"{family} {style}"), (6, f"{family}-{style}")):
        names.setName(value, name_id, 3, 1, 0x409)
    static["OS/2"].usWeightClass = weight
    path = os.path.join(OUT, output)
    static.save(path)
    print(f"{output:24s} wght {weight}  {os.path.getsize(path):>9,d}")
