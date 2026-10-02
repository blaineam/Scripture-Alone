#!/usr/bin/env python3
"""Describes the *shape* of a Lockman Foundation coded text file without printing any of its text.

    python3 Tools/lockman_probe.py ~/secure/nasb/"NASB 2020(b+n-r-num)(08-12-26).txt"

The licence forbids giving the NASB text to an AI system of any kind, including the coding agent
that maintains Tools/lockman.py. This probe is how that agent learns the file's structure: it prints
only encodings, code names (<PM>, <$F, ...), punctuation characters, counts and verse references.
No word of the text, and no masked "shape" of a line, ever leaves this script. Review the output
before sharing it; it should contain nothing you would recognise as Scripture.
"""

import collections
import os
import re
import sqlite3
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
KJV = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles", "KJV.sqlite")

VERSE = re.compile(r"\{\{(\d+)::(\d+)\}\}(\d+)(\^|<T>)?")
# A code is short and has no spaces: <PM>, </PM>, <\>, </>, <N1>, <,>, <$F, $E>, <\N>, </N>.
CODE = re.compile(r"<\$F|\$E>|<[^<>\s]{1,6}>")
LABELS = {"<CN>", "<SN>", "<SB>"}  # chapter / psalm / book-of-psalms labels: "CHAPTER 5" etc.


def decode(data):
    if data.startswith(b"\xef\xbb\xbf"):
        return "utf-8-sig", data[3:].decode("utf-8")
    try:
        return "utf-8", data.decode("utf-8")
    except UnicodeDecodeError as error:
        return f"cp1252 (not UTF-8: first bad byte at offset {error.start})", data.decode("cp1252", errors="replace")


def main(path):
    data = open(os.path.expanduser(path), "rb").read()
    encoding, text = decode(data)
    crlf, lf = data.count(b"\r\n"), data.count(b"\n")
    lines = text.splitlines()
    print(f"bytes {len(data)}  encoding {encoding}  lines {len(lines)}  CRLF {crlf}  bare LF {lf - crlf}  "
          f"bare CR {data.count(bytes([13])) - crlf}  tabs {text.count(chr(9))}  NUL {text.count(chr(0))}")

    codes = collections.Counter()
    position = collections.defaultdict(collections.Counter)   # code -> where it sits on its line
    first = collections.Counter()                             # what each line starts with
    last = collections.Counter()                              # what each line ends with
    pairs = collections.Counter()                             # code immediately followed by code
    labels = collections.Counter()
    markers, suffixes = [], collections.Counter()
    blank = 0
    for line in lines:
        stripped = line.strip()
        if not stripped:
            blank += 1
            continue
        tokens = []
        for m in VERSE.finditer(line):
            markers.append((int(m.group(1)), int(m.group(2)), int(m.group(3))))
            suffixes[m.group(4) or "(none)"] += 1
            tokens.append((m.start(), m.end(), "{{verse}}"))
        for m in CODE.finditer(line):
            if any(s <= m.start() < e for s, e, _ in tokens):
                continue
            tokens.append((m.start(), m.end(), m.group(0)))
        tokens.sort()
        lead = len(line) - len(line.lstrip())
        first[next((t for s, e, t in tokens if s == lead), "(text)")] += 1
        tail = len(line.rstrip())
        last[next((t for s, e, t in tokens if e == tail), "(text)")] += 1
        previous_end, previous = None, None
        for s, e, t in tokens:
            if t == "{{verse}}":
                previous_end, previous = e, t
                continue
            codes[t] += 1
            where = "line-start" if s == lead else "line-end" if e == tail else (
                "after-verse" if previous == "{{verse}}" and s == previous_end else "mid-line")
            position[t][where] += 1
            if previous is not None and s == previous_end:
                pairs[f"{previous}{t}"] += 1
            if t in LABELS:
                close = "</" + t[1:]
                end = line.find(close, e)
                if end > 0:
                    # Only "CHAPTER #"-style labels are shown; anything else (a speaker in the
                    # Song of Solomon) is just counted.
                    label = re.sub(r"[0-9]+", "#", line[e:end]).strip()
                    labels[f"{t} {label if re.fullmatch(r'[A-Z]+ #', label) else '(other)'} {close}"] += 1
            previous_end, previous = e, t
    print(f"blank lines {blank}")

    print("\n-- line starts with --")
    for token, n in first.most_common():
        print(f"  {n:7d}  {token}")
    print("\n-- line ends with --")
    for token, n in last.most_common():
        print(f"  {n:7d}  {token}")
    print("\n-- verse markers --")
    print(f"  {len(markers)} markers; suffix after the number: {dict(suffixes)}")
    print("\n-- codes (count; where on the line) --")
    for token, n in sorted(codes.items()):
        print(f"  {n:7d}  {token:8s} {dict(position[token])}")
    print("\n-- code directly followed by code (top 40) --")
    for token, n in pairs.most_common(40):
        print(f"  {n:7d}  {token}")
    print("\n-- labels (digits as #) --")
    for token, n in labels.most_common():
        print(f"  {n:7d}  {token}")

    # Characters that are not letters, digits or spaces: punctuation and code characters only.
    body = VERSE.sub(" ", CODE.sub(" ", text))
    symbols = collections.Counter(ch for ch in body if not ch.isalnum() and not ch.isspace())
    print("\n-- non-letter characters outside codes --")
    print("  " + "  ".join(f"{ch!r} U+{ord(ch):04X} {n}" for ch, n in sorted(symbols.items())))
    accented = collections.Counter(ch for ch in body if ch.isalpha() and ord(ch) > 127)
    print("\n-- non-ASCII letters --")
    print("  " + ("  ".join(f"{ch!r} U+{ord(ch):04X} {n}" for ch, n in sorted(accented.items())) or "none"))
    for sequence in ("--", "+“", "+‘", "-“", "-‘", "[[", "]]", "{{", "}}", "*"):
        print(f"  sequence {sequence!r}: {body.count(sequence) if sequence not in ('{{', '}}') else text.count(sequence)}")

    # Balance of paired codes, checked per verse marker span.
    print("\n-- pairs opened vs closed --")
    for open_, close in (("{", "}"), ("<\\>", "</>"), ("<$F", "$E>"), ("<\\N>", "</N>"), ("<RS>", "</RS>"),
                         ("<B>", "</B>"), ("<SH>", "</SH>"), ("<SHI>", "</SHI>"), ("<SS>", "</SS>"),
                         ("<SF>", "</SF>"), ("<FN>", "</FN>"), ("<BN>", "</BN>"), ("<CN>", "</CN>")):
        o = text.count(open_) if len(open_) == 1 else codes.get(open_, 0)
        c = text.count(close) if len(close) == 1 else codes.get(close, 0)
        print(f"  {open_:5s} {o:7d}   {close:5s} {c:7d}")

    # Versification against the KJV store the app ships: references only.
    print("\n-- versification vs KJV --")
    seen = collections.Counter(markers)
    dupes = [m for m, n in seen.items() if n > 1]
    print(f"  books {len({b for b, _, _ in markers})}  duplicate markers {len(dupes)} {dupes[:10]}")
    order_breaks = [(a, b) for a, b in zip(markers, markers[1:]) if b <= a]
    print(f"  out-of-order markers {len(order_breaks)} {order_breaks[:10]}")
    if os.path.exists(KJV):
        kjv = set()
        for (vid,) in sqlite3.connect(KJV).execute("SELECT id FROM verses"):
            kjv.add((vid // 1_000_000, vid // 1_000 % 1_000, vid % 1_000))
        mine = set(markers)
        missing = sorted(kjv - mine)
        extra = sorted(mine - kjv)
        print(f"  in KJV, not here ({len(missing)}): {missing[:60]}")
        print(f"  here, not in KJV ({len(extra)}): {extra[:60]}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
