"""Instance and subset the reader's bundled faces (see ui/reader/ReaderTypography.kt).

Rebuilds android/app/src/main/res/font/* (except Source Serif 4, bundled earlier as-is) from the
upstream files, which it expects in the working directory:

  google/fonts  ofl/inter          Inter[opsz,wght].ttf, Inter-Italic[opsz,wght].ttf
                ofl/literata       Literata[opsz,wght].ttf, Literata-Italic[opsz,wght].ttf
                ofl/gelasio        Gelasio[wght].ttf, Gelasio-Italic[wght].ttf
                ofl/nunitosans     NunitoSans[YTLC,opsz,wdth,wght].ttf, NunitoSans-Italic[...].ttf
                ofl/charissil      CharisSIL-Regular.ttf, -Italic.ttf, -Bold.ttf (copied unmodified)
  CTAN          fonts/domitian.zip unzipped: domitian/opentype/Domitian-{Roman,Italic,Bold}.otf

Each face is pinned to weights 400-700 (the reader draws 400, the share card's reference 700),
Inter and Literata to one optical size, Nunito Sans to its default width, and every face is cut to
Latin, IPA, Greek, Cyrillic and common punctuation, unhinted; letters outside fall back to the
system face. Needs fontTools (`pip install fonttools`). Output goes to out/ with the resource names;
copy it into res/font and keep each licence in app/src/main/assets/licenses/.
"""
import os
import shutil
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

UNICODES = (
    list(range(0x0000, 0x0250)) + list(range(0x0250, 0x0370)) + list(range(0x0370, 0x0400)) +
    list(range(0x0400, 0x0530)) + list(range(0x1E00, 0x1F00)) + list(range(0x2000, 0x2070)) +
    list(range(0x20A0, 0x20D0)) + list(range(0x2100, 0x2160)) + list(range(0x2190, 0x2194)) +
    [0x2212, 0x2215, 0x2219, 0x221E, 0x2248, 0x2260, 0x2264, 0x2265, 0x25CC, 0x2605, 0x2606, 0x2610, 0x2611,
     0x2713, 0x2717, 0xFEFF, 0xFFFD] + list(range(0xFB00, 0xFB07))
)

JOBS = [
    # source, output, axis limits (None = static)
    ("Inter[opsz,wght].ttf", "inter.ttf", {"wght": (400, 700), "opsz": 18}),
    ("Inter-Italic[opsz,wght].ttf", "inter_italic.ttf", {"wght": (400, 700), "opsz": 18}),
    ("Literata[opsz,wght].ttf", "literata.ttf", {"wght": (400, 700), "opsz": 18}),
    ("Literata-Italic[opsz,wght].ttf", "literata_italic.ttf", {"wght": (400, 700), "opsz": 18}),
    ("Gelasio[wght].ttf", "gelasio.ttf", None),
    ("Gelasio-Italic[wght].ttf", "gelasio_italic.ttf", None),
    ("NunitoSans[YTLC,opsz,wdth,wght].ttf", "nunito_sans.ttf", {"wght": (400, 700), "wdth": 100, "opsz": 12, "YTLC": 500}),
    ("NunitoSans-Italic[YTLC,opsz,wdth,wght].ttf", "nunito_sans_italic.ttf", {"wght": (400, 700), "wdth": 100, "opsz": 12, "YTLC": 500}),
    ("domitian/opentype/Domitian-Roman.otf", "domitian.otf", None),
    ("domitian/opentype/Domitian-Italic.otf", "domitian_italic.otf", None),
    ("domitian/opentype/Domitian-Bold.otf", "domitian_bold.otf", None),
]

# Charis SIL's licence reserves the names "Charis" and "SIL", and a subset is a Modified Version under
# the OFL, which could not keep them. So Charis ships exactly as SIL publishes it, byte for byte.
UNMODIFIED = [
    ("CharisSIL-Regular.ttf", "charis_sil.ttf"),
    ("CharisSIL-Italic.ttf", "charis_sil_italic.ttf"),
    ("CharisSIL-Bold.ttf", "charis_sil_bold.ttf"),
]

os.makedirs("out", exist_ok=True)
for src, out in UNMODIFIED:
    shutil.copyfile(src, os.path.join("out", out))
    print(f"{out:28s} {os.path.getsize(src):>9,d} (unmodified)")
for src, out, limits in JOBS:
    font = TTFont(src)
    if limits:
        font = instancer.instantiateVariableFont(font, limits)
        font.save("tmp.ttf")
        font = TTFont("tmp.ttf")
    options = subset.Options()
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.glyph_names = False
    options.hinting = False
    options.drop_tables += ["DSIG"]
    sub = subset.Subsetter(options)
    sub.populate(unicodes=UNICODES)
    sub.subset(font)
    path = os.path.join("out", out)
    font.save(path)
    print(f"{out:28s} {os.path.getsize(src):>9,d} -> {os.path.getsize(path):>9,d}")
