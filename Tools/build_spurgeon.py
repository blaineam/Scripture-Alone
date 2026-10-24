#!/usr/bin/env python3
"""Builds ScriptureAlone/Resources/Plans/SpurgeonMorningEvening.json, the reading-plan form of C. H.
Spurgeon's *Morning and Evening* (1866; Spurgeon died in 1892, so the text is public domain).

The source is the Christian Classics Ethereal Library's ThML edition, rights "Public Domain"
(https://ccel.org/ccel/spurgeon/morneve — its XML is https://ccel.org/ccel/s/spurgeon/morneve.xml). Not
the modernised Alistair Begg edition, which is copyrighted. Each of the 366 days has a morning and an
evening entry: the key verse Spurgeon quotes (KJV wording, in his own words) and its reference, then the
meditation.

The output is a `ReadingPlan` in the same JSON the apps store (`ScriptureAloneCore/Plans/ReadingPlan.swift`,
`android/.../data/plans/PlanJson.kt`): `annual` (days are month/day, not a count from a start date),
`passages` = [morning key verse, evening key verse], `devotions` = [morning, evening]. Both apps read the
one file: iOS from the app bundle, Android from `assets/` (syncBundledData). Deterministic.

    python3 Tools/build_spurgeon.py [path/to/morneve.xml]   # downloads it when no path is given
"""
import html
import json
import re
import sys
import urllib.request
from pathlib import Path

URL = "https://ccel.org/ccel/s/spurgeon/morneve.xml"
OUT = Path(__file__).resolve().parent.parent / "ScriptureAlone/Resources/Plans/SpurgeonMorningEvening.json"

# OSIS book ids as CCEL writes them -> canonical ordinal (BookID.rawValue).
BOOKS = ("Gen Exod Lev Num Deut Josh Judg Ruth 1Sam 2Sam 1Kgs 2Kgs 1Chr 2Chr Ezra Neh Esth Job Ps Prov Eccl "
         "Song Isa Jer Lam Ezek Dan Hos Joel Amos Obad Jonah Mic Nah Hab Zeph Hag Zech Mal Matt Mark Luke "
         "John Acts Rom 1Cor 2Cor Gal Eph Phil Col 1Thess 2Thess 1Tim 2Tim Titus Phlm Heb Jas 1Pet 2Pet "
         "1John 2John 3John Jude Rev").split()
ORDINAL = {code: i + 1 for i, code in enumerate(BOOKS)}
MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October",
          "November", "December"]


def text_of(fragment: str) -> str:
    s = re.sub(r"<[^>]+>", "", fragment)
    s = html.unescape(s)
    return re.sub(r"\s+", " ", s).strip()


def passage(parsed: str) -> dict:
    # "|Josh|5|12|0|0": book, chapter, verse, end chapter, end verse (0 = none). Two refs are ";"-separated:
    # the first is the key verse.
    book, ch, vs, ch2, vs2 = parsed.split(";")[0].strip("|").split("|")
    if book not in ORDINAL:
        raise SystemExit(f"unknown book code {book!r} in {parsed!r}")
    p = {"book": ORDINAL[book], "startChapter": int(ch), "endChapter": int(ch2) if int(ch2) else int(ch)}
    if int(vs):
        p["startVerse"] = int(vs)
        p["endVerse"] = int(vs2) if int(vs2) else int(vs)
    return p


def entry(block: str) -> tuple[dict, dict]:
    quote = re.search(r'<(p|h3) class="passage"[^>]*>(.*?)</\1>', block, re.S)
    parsed = re.search(r'<scripCom[^>]*parsed="([^"]*)"', block)
    paras = [text_of(m) for m in re.findall(r'<p class="(?:normal|poetry|indent)[^"]*"[^>]*>(.*?)</p>', block, re.S)]
    paras = [p for p in paras if p]
    if not (quote and parsed and paras):
        raise SystemExit("entry without a quote, reference or text: " + block[:200])
    return passage(parsed.group(1)), {"quote": text_of(quote.group(2)), "text": "\n\n".join(paras)}


def main() -> None:
    if len(sys.argv) > 1:
        xml = Path(sys.argv[1]).read_text(encoding="utf-8")
    else:
        xml = urllib.request.urlopen(URL).read().decode("utf-8")
    blocks = {m.group(2): m.group(3) for m in re.finditer(r'<div2 title="([^"]*)"[^>]*id="(d\d{4}(?:am|pm))">(.*?)</div2>', xml, re.S)}
    days = []
    for month in range(1, 13):
        for day in range(1, 32):
            key = f"d{month:02d}{day:02d}"
            if key + "am" not in blocks:
                continue
            passages, devotions = [], []
            for suffix, heading in (("am", "Morning"), ("pm", "Evening")):
                p, d = entry(blocks[key + suffix])
                passages.append(p)
                devotions.append({"heading": heading, **d})
            days.append({"index": len(days), "date": f"{month:02d}-{day:02d}", "label": f"{MONTHS[month - 1]} {day}",
                         "passages": passages, "devotions": devotions})
    assert len(days) == 366, len(days)
    plan = {
        "id": "devotional-spurgeon-morning-evening", "kind": "devotional", "annual": True,
        "title": "Spurgeon’s Morning and Evening",
        "summary": "A morning and an evening meditation for every day of the year, by C. H. Spurgeon (1866).",
        "days": days, "skipWeekdays": [],
        "source": {"siteURL": "https://ccel.org/ccel/spurgeon/morneve",
                   "name": "Morning and Evening by C. H. Spurgeon (1866), public domain — Christian Classics Ethereal Library"},
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(plan, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"{len(days)} days, {OUT.stat().st_size / 1e6:.2f} MB -> {OUT}")


if __name__ == "__main__":
    main()
