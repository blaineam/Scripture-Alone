#!/usr/bin/env python3
"""Compile the Study-mode context data: places, base map, timeline and charts.

    python3 Tools/build_context.py            # fetch (once, cached) + build
    python3 Tools/build_context.py --check    # build, then assert known facts

Outputs (bundled by the app):
  ScriptureAlone/Resources/Study/Context.sqlite   places, verse->place index, eras, events, charts
  ScriptureAlone/Resources/Study/Basemap.bin      land, lakes and rivers for the offline map

Inputs:
  Data/context/*.json, Data/context/charts/*.json   authored here (see docs/context-sources.md)
  OpenBible.info Bible Geocoding Data (CC BY 4.0)     fetched at a pinned commit
  Natural Earth 1:10m physical vectors (public domain) fetched at a pinned commit
Downloads are verified by SHA-256 and cached in Data/cache/ (git-ignored), so later builds
are offline. Verse keys are book * 1_000_000 + chapter * 1_000 + verse, as everywhere else.

Basemap.bin (little-endian):
  "SABM" u16 version=1 u16 layerCount  f32 minLon f32 minLat f32 maxLon f32 maxLat
  layer:  u8 kind (0 land, 1 lake, 2 river)  u8 lod (0 coarse, 1 fine)  u16 0  u32 ringCount
  ring:   u32 pointCount  u8 rank  u8 0 u16 0  then pointCount x (u16 x, u16 y)
Points are quantized across the bounding box: lon = minLon + x / 65535 * (maxLon - minLon).
Land and lake rings are polygons (fill even-odd); river rings are open polylines.
"""

import hashlib
import json
import math
import os
import re
import sqlite3
import struct
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA_DIR = os.path.join(ROOT, "Data", "context")
CACHE_DIR = os.path.join(ROOT, "Data", "cache", "context")
OUTPUT_DIR = os.path.join(ROOT, "ScriptureAlone", "Resources", "Study")
BIBLE_FOR_COUNTS = os.path.join(ROOT, "ScriptureAlone", "Resources", "Bibles", "ASV.sqlite")

OPENBIBLE_COMMIT = "7eb18a5ee62f27b9b93bd6689ea272d76dd23b8f"
NATURAL_EARTH_COMMIT = "ca96624a56bd078437bca8184e78163e5039ad19"  # natural-earth-vector, v5.1.2+
SOURCES = {
    "ancient.jsonl": (
        f"https://raw.githubusercontent.com/openbibleinfo/Bible-Geocoding-Data/{OPENBIBLE_COMMIT}/data/ancient.jsonl",
        "b8187aa4737e8517ccc090f765d2be11da4c548cd2a59d3cdcb62e952cb8c0f2"),
    "modern.jsonl": (
        f"https://raw.githubusercontent.com/openbibleinfo/Bible-Geocoding-Data/{OPENBIBLE_COMMIT}/data/modern.jsonl",
        "da731f6e110bac4ea66a9f037a0a31cfb11c4f1efc1206aa9e109092b2c60087"),
    "ne_10m_land.geojson": (
        f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NATURAL_EARTH_COMMIT}/geojson/ne_10m_land.geojson",
        "1ac90796408bc6ad6911d69448485d3c4dbf2190370080368a09976e1c9f7416"),
    "ne_10m_lakes.geojson": (
        f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NATURAL_EARTH_COMMIT}/geojson/ne_10m_lakes.geojson",
        "2d036f53dedec578001c5c30c2959ee7d4eebc1306900fa4367c49929ec8f2d9"),
    "ne_10m_rivers_lake_centerlines.geojson": (
        f"https://raw.githubusercontent.com/nvkelso/natural-earth-vector/{NATURAL_EARTH_COMMIT}/geojson/ne_10m_rivers_lake_centerlines.geojson",
        "bb854a900ecbd3b408df46d5e16e3e0f974ba55993f9d8b5c26e855273c0905a"),
}

# The map covers the Bible's world: Spain to Persia, Ethiopia's edge to the Black Sea.
BBOX = (-20.0, 10.0, 60.0, 45.0)
# Quantization box, a little larger so clipped edges sit outside the visible map.
QBOX = (-21.0, 9.0, 61.0, 46.0)
COS_LAT = math.cos(math.radians(32.0))
LODS = [  # (lod, Douglas-Peucker tolerance in degrees, minimum ring area in deg^2)
    (0, 0.035, 0.02),
    (1, 0.0045, 0.00015),
]

BOOKS = [
    "GEN", "EXO", "LEV", "NUM", "DEU", "JOS", "JDG", "RUT", "1SA", "2SA", "1KI", "2KI",
    "1CH", "2CH", "EZR", "NEH", "EST", "JOB", "PSA", "PRO", "ECC", "SNG", "ISA", "JER",
    "LAM", "EZK", "DAN", "HOS", "JOL", "AMO", "OBA", "JON", "MIC", "NAM", "HAB", "ZEP",
    "HAG", "ZEC", "MAL", "MAT", "MRK", "LUK", "JHN", "ACT", "ROM", "1CO", "2CO", "GAL",
    "EPH", "PHP", "COL", "1TH", "2TH", "1TI", "2TI", "TIT", "PHM", "HEB", "JAS", "1PE",
    "2PE", "1JN", "2JN", "3JN", "JUD", "REV",
]
BOOK_ORD = {code: i + 1 for i, code in enumerate(BOOKS)}

# Years the sources fix firmly enough to print without "c.".
FIRM_YEARS = {-931, -853, -841, -722, -701, -609, -605, -597, -586, -539, -538, -516, -445,
              -332, -164, -63, -37, 70}


def key(book, chapter, verse):
    return book * 1_000_000 + chapter * 1_000 + verse


# MARK: - Sources

def fetch(name):
    url, digest = SOURCES[name]
    path = os.path.join(CACHE_DIR, name)
    if not os.path.exists(path):
        os.makedirs(CACHE_DIR, exist_ok=True)
        print(f"  fetching {name}")
        tmp = path + ".part"
        urllib.request.urlretrieve(url, tmp)
        os.replace(tmp, path)
    with open(path, "rb") as handle:
        actual = hashlib.sha256(handle.read()).hexdigest()
    if actual != digest:
        sys.exit(f"{name}: SHA-256 {actual} does not match the pinned {digest}. Delete {path} and retry.")
    return path


def load_json(*parts):
    with open(os.path.join(DATA_DIR, *parts), encoding="utf-8") as handle:
        return json.load(handle)


def verse_counts():
    db = sqlite3.connect(f"file:{BIBLE_FOR_COUNTS}?mode=ro", uri=True)
    counts = {(b, c): v for b, c, v in db.execute("SELECT book, chapter, verses FROM chapters")}
    db.close()
    return counts


REF_RE = re.compile(r"^([1-3]?[A-Z]{2,3}) (\d+)(?::(\d+))?(?:-(\d+)(?::(\d+))?)?$")


def parse_ref(text, counts):
    """"GEN 12", "GEN 12:1-9", "GEN 1:1-2:3", "JOS 13:1-21:45" -> (start_key, end_key)."""
    match = REF_RE.match(text.strip())
    if not match:
        raise ValueError(f"bad reference {text!r}")
    code, c1, v1, a, b = match.groups()
    book = BOOK_ORD[code]
    c1 = int(c1)
    if v1 is None:  # whole chapter(s): "GEN 12" or "GEN 12-14"
        c2 = int(a) if a else c1
        start, end = key(book, c1, 1), key(book, c2, counts[(book, c2)])
    else:
        v1 = int(v1)
        if a is None:
            c2, v2 = c1, v1
        elif b is None:
            c2, v2 = c1, int(a)
        else:
            c2, v2 = int(a), int(b)
        start, end = key(book, c1, v1), key(book, c2, v2)
    for k in (start, end):
        b_, c_, v_ = k // 1_000_000, k // 1_000 % 1_000, k % 1_000
        if (b_, c_) not in counts or not 1 <= v_ <= counts[(b_, c_)]:
            raise ValueError(f"{text!r} points outside the text")
    return start, end


def year_label(year, date=None):
    if date:
        return date
    if year is None:
        return None
    prefix = "" if year in FIRM_YEARS else "c. "
    return f"{prefix}{-year} BC" if year < 0 else f"{prefix}AD {year}"


# MARK: - Places (OpenBible.info)

KIND = {
    "settlement": "settlement", "settlement and spring": "settlement", "fortification": "settlement",
    "district in settlement": "settlement", "structure": "site", "gate": "site", "altar": "site",
    "region": "region", "people group": "region", "natural area": "region", "island": "island",
    "river": "water", "body of water": "water", "spring": "water", "well": "water", "pool": "water",
    "wadi": "water", "canal": "water", "ford": "water",
    "mountain": "mountain", "hill": "mountain", "mountain range": "mountain", "mountain ridge": "mountain",
    "mountain pass": "mountain", "promontory": "mountain", "cliff": "mountain", "rock": "mountain",
}


def best_resolution(place):
    """The most confident identification that resolves to coordinates, and its best resolution."""
    candidates = []
    for identification in place.get("identifications", []):
        score = identification.get("score", {}).get("time_total", 0) or 0
        resolutions = [r for r in identification.get("resolutions", []) if r.get("lonlat")]
        if resolutions:
            best = max(resolutions, key=lambda r: r.get("best_path_score", 0))
            candidates.append((score, identification, best))
    if not candidates:
        return None
    return max(candidates, key=lambda c: c[0])


def build_places(counts):
    modern = {}
    with open(fetch("modern.jsonl"), encoding="utf-8") as handle:
        for line in handle:
            row = json.loads(line)
            modern[row["id"]] = row
    places, links = [], []
    skipped = {"no verses": 0, "unresolved": 0, "off map": 0, "verse outside text": 0}
    osm_points = 0
    with open(fetch("ancient.jsonl"), encoding="utf-8") as handle:
        rows = [json.loads(line) for line in handle]
    for row in rows:
        verses = row.get("verses") or []
        if not verses:
            skipped["no verses"] += 1
            continue
        best = best_resolution(row)
        if best is None:
            skipped["unresolved"] += 1
            continue
        score, identification, resolution = best
        lon, lat = (float(x) for x in resolution["lonlat"].split(","))
        if not (BBOX[0] <= lon <= BBOX[2] and BBOX[1] <= lat <= BBOX[3]):
            skipped["off map"] += 1
            continue
        keys = set()
        for verse in verses:
            sort = verse["sort"]  # "BBCCCVVV"
            b, c, v = int(sort[0:2]), int(sort[2:5]), int(sort[5:8])
            if (b, c) in counts and 1 <= v <= counts[(b, c)]:
                keys.add(key(b, c, v))
            else:
                skipped["verse outside text"] += 1
        if not keys:
            continue
        name = re.sub(r" \d+$", "", row["friendly_id"])
        basis = modern.get(resolution.get("modern_basis_id", ""), {})
        modern_name = basis.get("friendly_id") or ""
        if modern_name == name:
            modern_name = ""
        osm = (basis.get("coordinates_source") or {}).get("type") in ("osm", "osm_group")
        osm_points += osm
        types = row.get("types") or [resolution.get("type", "")]
        place_type = types[0] if types else ""
        places.append({
            "obid": row["id"], "name": name, "modern": modern_name,
            "kind": KIND.get(place_type, "site"), "type": place_type,
            "lon": round(lon, 5), "lat": round(lat, 5),
            "confidence": int(score), "precision": resolution.get("lonlat_type", "point"),
            "alternatives": sum(1 for i in row.get("identifications", []) if i is not identification),
            "mentions": len(keys), "osm": int(osm), "keys": sorted(keys),
        })
    places.sort(key=lambda p: (-p["mentions"], p["name"]))
    for index, place in enumerate(places, start=1):
        place["id"] = index
        links.extend((k, index) for k in place["keys"])
    print(f"  places: {len(places)} mapped, {len(links)} verse links; skipped {skipped}; {osm_points} OSM-sourced points")
    return places, links


# MARK: - Base map (Natural Earth)

def clip_polygon(ring, box):
    """Sutherland-Hodgman against an axis-aligned box."""
    x0, y0, x1, y1 = box
    edges = [
        (lambda p: p[0] >= x0, lambda a, b: (x0, a[1] + (b[1] - a[1]) * (x0 - a[0]) / (b[0] - a[0]))),
        (lambda p: p[0] <= x1, lambda a, b: (x1, a[1] + (b[1] - a[1]) * (x1 - a[0]) / (b[0] - a[0]))),
        (lambda p: p[1] >= y0, lambda a, b: (a[0] + (b[0] - a[0]) * (y0 - a[1]) / (b[1] - a[1]), y0)),
        (lambda p: p[1] <= y1, lambda a, b: (a[0] + (b[0] - a[0]) * (y1 - a[1]) / (b[1] - a[1]), y1)),
    ]
    points = ring
    for inside, cross in edges:
        if not points:
            break
        output = []
        prev = points[-1]
        for cur in points:
            if inside(cur):
                if not inside(prev):
                    output.append(cross(prev, cur))
                output.append(cur)
            elif inside(prev):
                output.append(cross(prev, cur))
            prev = cur
        points = output
    return points


def clip_line(line, box):
    """Splits a polyline into the pieces inside the box (segment-level Liang-Barsky)."""
    x0, y0, x1, y1 = box
    pieces, current = [], []

    def inside(p):
        return x0 <= p[0] <= x1 and y0 <= p[1] <= y1

    for a, b in zip(line, line[1:]):
        t0, t1 = 0.0, 1.0
        dx, dy = b[0] - a[0], b[1] - a[1]
        ok = True
        for p, q in ((-dx, a[0] - x0), (dx, x1 - a[0]), (-dy, a[1] - y0), (dy, y1 - a[1])):
            if p == 0:
                if q < 0:
                    ok = False
                    break
            else:
                t = q / p
                if p < 0:
                    t0 = max(t0, t)
                else:
                    t1 = min(t1, t)
        if not ok or t0 > t1:
            if current:
                pieces.append(current)
                current = []
            continue
        start = (a[0] + t0 * dx, a[1] + t0 * dy)
        end = (a[0] + t1 * dx, a[1] + t1 * dy)
        if not current:
            current = [start]
        current.append(end)
        if t1 < 1.0 or not inside(b):
            pieces.append(current)
            current = []
    if current:
        pieces.append(current)
    return [p for p in pieces if len(p) >= 2]


def simplify(points, tolerance):
    """Douglas-Peucker on the projected plane (longitude scaled by cos 32 deg)."""
    if len(points) < 3:
        return points
    proj = [(p[0] * COS_LAT, p[1]) for p in points]
    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    tol2 = tolerance * tolerance
    while stack:
        first, last = stack.pop()
        ax, ay = proj[first]
        bx, by = proj[last]
        dx, dy = bx - ax, by - ay
        length2 = dx * dx + dy * dy
        worst, index = -1.0, -1
        for i in range(first + 1, last):
            px, py = proj[i]
            if length2 == 0:
                d2 = (px - ax) ** 2 + (py - ay) ** 2
            else:
                t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / length2))
                d2 = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2
            if d2 > worst:
                worst, index = d2, i
        if worst > tol2:
            keep[index] = True
            stack.append((first, index))
            stack.append((index, last))
    return [p for p, k in zip(points, keep) if k]


def ring_area(points):
    return abs(sum(a[0] * b[1] - b[0] * a[1] for a, b in zip(points, points[1:] + points[:1]))) / 2 * COS_LAT


def polygons_of(geometry):
    if geometry["type"] == "Polygon":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiPolygon":
        return geometry["coordinates"]
    return []


def lines_of(geometry):
    if geometry["type"] == "LineString":
        return [geometry["coordinates"]]
    if geometry["type"] == "MultiLineString":
        return geometry["coordinates"]
    return []


def touches(points, box):
    xs = [p[0] for p in points]
    ys = [p[1] for p in points]
    return not (max(xs) < box[0] or min(xs) > box[2] or max(ys) < box[1] or min(ys) > box[3])


def build_basemap():
    with open(fetch("ne_10m_land.geojson"), encoding="utf-8") as handle:
        land = json.load(handle)["features"]
    with open(fetch("ne_10m_lakes.geojson"), encoding="utf-8") as handle:
        lakes = json.load(handle)["features"]
    with open(fetch("ne_10m_rivers_lake_centerlines.geojson"), encoding="utf-8") as handle:
        rivers = json.load(handle)["features"]

    def polygon_rings(features, keep):
        rings = []
        for feature in features:
            if not keep(feature["properties"]):
                continue
            rank = int(feature["properties"].get("scalerank") or 0)
            for polygon in polygons_of(feature["geometry"]):
                for ring in polygon:
                    ring = [tuple(p[:2]) for p in ring]
                    if ring and ring[0] == ring[-1]:
                        ring = ring[:-1]
                    if len(ring) >= 3 and touches(ring, QBOX):
                        clipped = clip_polygon(ring, QBOX)
                        if len(clipped) >= 3:
                            rings.append((rank, clipped))
        return rings

    def keep_lake(props):
        # Modern reservoirs (Lake Nasser, Assad, Atatürk...) did not exist in biblical times.
        return props.get("featurecla") != "Reservoir"

    def keep_river(props):
        name = props.get("name") or ""
        return props.get("featurecla") in ("River", "Lake Centerline") and "Canal" not in name and "Channel" not in name

    sources = {
        0: polygon_rings(land, lambda _: True),
        1: polygon_rings(lakes, keep_lake),
    }
    river_lines = []
    for feature in rivers:
        if not keep_river(feature["properties"]):
            continue
        rank = int(feature["properties"].get("scalerank") or 0)
        for line in lines_of(feature["geometry"]):
            line = [tuple(p[:2]) for p in line]
            if len(line) >= 2 and touches(line, QBOX):
                river_lines.extend((rank, piece) for piece in clip_line(line, QBOX))
    sources[2] = river_lines

    layers, stats = [], {}
    for lod, tolerance, min_area in LODS:
        for kind in (0, 1, 2):
            rings = []
            for rank, points in sources[kind]:
                if kind == 2:
                    if lod == 0 and rank > 7:
                        continue
                    simple = simplify(points, tolerance)
                    if len(simple) >= 2:
                        rings.append((rank, simple))
                else:
                    closed = points + [points[0]]
                    simple = simplify(closed, tolerance)[:-1]
                    if len(simple) >= 3 and ring_area(simple) >= min_area:
                        rings.append((rank, simple))
            layers.append((kind, lod, rings))
            stats[(kind, lod)] = (len(rings), sum(len(r[1]) for r in rings))

    qx0, qy0, qx1, qy1 = QBOX
    out = bytearray(b"SABM")
    out += struct.pack("<HH4f", 1, len(layers), qx0, qy0, qx1, qy1)
    for kind, lod, rings in layers:
        out += struct.pack("<BBHI", kind, lod, 0, len(rings))
        for rank, points in rings:
            out += struct.pack("<IBBH", len(points), min(rank, 255), 0, 0)
            for lon, lat in points:
                x = round((lon - qx0) / (qx1 - qx0) * 65535)
                y = round((lat - qy0) / (qy1 - qy0) * 65535)
                out += struct.pack("<HH", max(0, min(65535, x)), max(0, min(65535, y)))
    names = {0: "land", 1: "lakes", 2: "rivers"}
    print("  basemap: " + ", ".join(f"{names[k]}/lod{l} {n} rings {p} pts" for (k, l), (n, p) in sorted(stats.items())))
    return bytes(out)


# MARK: - Timeline and charts (authored)

def expand_chapters(spec):
    a, _, b = spec.partition("-")
    return range(int(a), int(b or a) + 1)


def build_timeline(counts):
    eras_doc = load_json("eras.json")
    eras = eras_doc["eras"]
    era_ids = [e["id"] for e in eras]
    events = []
    for order, event in enumerate(eras_doc["events"]):
        if event["era"] not in era_ids:
            raise ValueError(f"event {event['name']} has unknown era {event['era']}")
        start = end = None
        if event.get("refs"):
            start, end = parse_ref(event["refs"], counts)
        events.append({
            "era": event["era"], "ord": order, "name": event["name"], "year": event.get("year"),
            "date": year_label(event.get("year"), event.get("date")),
            "debated": int(bool(event.get("debated"))), "start": start, "end": end,
        })

    chapter_rows = {}
    for entry in load_json("chapters.json")["books"]:
        book = BOOK_ORD[entry["book"]]
        chapters = list(expand_chapters(entry["chapters"]))
        for era in entry["eras"]:
            if era not in era_ids:
                raise ValueError(f"{entry['book']} {entry['chapters']}: unknown era {era}")
        years = entry.get("years")
        for index, chapter in enumerate(chapters):
            if (book, chapter) in chapter_rows:
                raise ValueError(f"{entry['book']} {chapter} is mapped twice")
            if (book, chapter) not in counts:
                raise ValueError(f"{entry['book']} {chapter} does not exist")
            year = None
            if years:
                fraction = index / (len(chapters) - 1) if len(chapters) > 1 else 0
                year = round(years[0] + (years[1] - years[0]) * fraction)
            chapter_rows[(book, chapter)] = (entry["eras"], year, entry.get("basis", "events"), entry.get("note"))
    missing = [f"{BOOKS[b - 1]} {c}" for (b, c) in counts if (b, c) not in chapter_rows]
    if missing:
        raise ValueError(f"chapters without an era: {missing[:12]}{'…' if len(missing) > 12 else ''}")
    return eras, events, chapter_rows


def build_charts(counts, places_by_obid):
    charts = []
    names = sorted(os.listdir(os.path.join(DATA_DIR, "charts")))
    preferred = ["kings.json", "journeys.json", "tribes.json", "feasts.json"]
    names.sort(key=lambda n: preferred.index(n) if n in preferred else len(preferred))
    for order, name in enumerate(names):
        if not name.endswith(".json"):
            continue
        chart = load_json("charts", name)
        body = chart["body"]

        def resolve(obj):
            """Turns "BOOK c:v" strings into [start, end] keys; validates every reference."""
            if isinstance(obj, dict):
                result = {}
                for k, v in obj.items():
                    if k in ("ref", "refs", "birth", "jacob", "moses", "allotment", "also", "nt") and isinstance(v, str):
                        result[k] = list(parse_ref(v, counts))
                    else:
                        result[k] = resolve(v)
                return result
            if isinstance(obj, list):
                return [resolve(v) for v in obj]
            return obj

        body = resolve(body)
        if chart["kind"] == "journeys":
            for journey in body["journeys"]:
                for stop in journey["stops"]:
                    place = places_by_obid.get(stop["place"])
                    if place is None:
                        raise ValueError(f"journey stop {stop['place']} is not a mapped place")
                    stop.update({"id": place["id"], "name": place["name"], "lon": place["lon"],
                                 "lat": place["lat"], "kind": place["kind"]})
                    del stop["place"]
        charts.append({
            "id": chart["id"], "ord": order, "kind": chart["kind"], "title": chart["title"],
            "subtitle": chart.get("subtitle", ""), "sources": chart.get("sources", ""),
            "scope": json.dumps([BOOK_ORD[b] for b in chart.get("scope", [])]),
            "body": json.dumps(body, ensure_ascii=False, separators=(",", ":")),
        })
    return charts


# MARK: - Output

def load_translations():
    """Data/context/translations/<lang>.json (Tools/translate_context.py) as table rows.

    One table for every language: (lang, kind, source, text), where kind is `place` (source = the
    OpenBible id), `person`, `modern` or `string` (source = the English text it translates). The app
    looks up its language and falls back to the English in the main tables, so a language that is
    missing a string shows English rather than nothing."""
    rows = []
    folder = os.path.join(DATA_DIR, "translations")
    if not os.path.isdir(folder):
        return rows
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".json"):
            continue
        lang = name[:-5]
        data = json.load(open(os.path.join(folder, name), encoding="utf-8"))
        for kind, field in (("place", "places"), ("person", "people"), ("modern", "modern"), ("string", "strings")):
            for source, text in sorted(data.get(field, {}).items()):
                if text:
                    rows.append((lang, kind, source, text))
    return rows


def write_database(path, places, links, eras, events, chapter_rows, charts, labels, translations=()):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path)
    db.executescript("""
        PRAGMA page_size = 4096;
        CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE places (
            id INTEGER PRIMARY KEY, obid TEXT NOT NULL UNIQUE, name TEXT NOT NULL, modern TEXT NOT NULL,
            kind TEXT NOT NULL, type TEXT NOT NULL, lon REAL NOT NULL, lat REAL NOT NULL,
            confidence INTEGER NOT NULL, precision TEXT NOT NULL, alternatives INTEGER NOT NULL,
            mentions INTEGER NOT NULL, osm INTEGER NOT NULL);
        CREATE TABLE place_verses (verse INTEGER NOT NULL, place INTEGER NOT NULL,
            PRIMARY KEY (verse, place)) WITHOUT ROWID;
        CREATE INDEX place_verses_by_place ON place_verses (place, verse);
        CREATE TABLE eras (id TEXT PRIMARY KEY, ord INTEGER NOT NULL, name TEXT NOT NULL, short TEXT NOT NULL,
            start INTEGER, end INTEGER, dates TEXT NOT NULL, color TEXT NOT NULL, summary TEXT NOT NULL,
            debate TEXT NOT NULL);
        CREATE TABLE chapter_eras (book INTEGER NOT NULL, chapter INTEGER NOT NULL, ord INTEGER NOT NULL,
            era TEXT NOT NULL, year INTEGER, basis TEXT NOT NULL, note TEXT,
            PRIMARY KEY (book, chapter, ord)) WITHOUT ROWID;
        CREATE TABLE events (id INTEGER PRIMARY KEY, era TEXT NOT NULL, ord INTEGER NOT NULL, name TEXT NOT NULL,
            year INTEGER, date TEXT, debated INTEGER NOT NULL, start_key INTEGER, end_key INTEGER);
        CREATE TABLE charts (id TEXT PRIMARY KEY, ord INTEGER NOT NULL, kind TEXT NOT NULL, title TEXT NOT NULL,
            subtitle TEXT NOT NULL, sources TEXT NOT NULL, scope TEXT NOT NULL, body TEXT NOT NULL);
        CREATE TABLE labels (text TEXT NOT NULL, sub TEXT, lon REAL NOT NULL, lat REAL NOT NULL,
            min_scale REAL NOT NULL, kind TEXT NOT NULL, angle REAL NOT NULL);
        CREATE TABLE translations (lang TEXT NOT NULL, kind TEXT NOT NULL, source TEXT NOT NULL,
            text TEXT NOT NULL, PRIMARY KEY (lang, kind, source)) WITHOUT ROWID;
    """)
    meta = {
        "version": "1",
        "places_source": f"OpenBible.info Bible Geocoding Data @ {OPENBIBLE_COMMIT[:7]} (CC BY 4.0)",
        "basemap_source": f"Natural Earth 1:10m physical vectors @ {NATURAL_EARTH_COMMIT[:7]} (public domain)",
        "bbox": ",".join(str(v) for v in BBOX),
    }
    db.executemany("INSERT INTO meta VALUES (?, ?)", meta.items())
    db.executemany("INSERT INTO places VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)", [
        (p["id"], p["obid"], p["name"], p["modern"], p["kind"], p["type"], p["lon"], p["lat"],
         p["confidence"], p["precision"], p["alternatives"], p["mentions"], p["osm"]) for p in places])
    db.executemany("INSERT INTO place_verses VALUES (?, ?)", sorted(links))
    db.executemany("INSERT INTO eras VALUES (?,?,?,?,?,?,?,?,?,?)", [
        (e["id"], i, e["name"], e["short"], e["start"], e["end"], e["dates"], e["color"], e["summary"],
         e.get("debate", "")) for i, e in enumerate(eras)])
    db.executemany("INSERT INTO chapter_eras VALUES (?,?,?,?,?,?,?)", [
        (b, c, i, era, year, basis, note)
        for (b, c), (era_list, year, basis, note) in sorted(chapter_rows.items())
        for i, era in enumerate(era_list)])
    db.executemany("INSERT INTO events (era, ord, name, year, date, debated, start_key, end_key) VALUES (?,?,?,?,?,?,?,?)", [
        (e["era"], e["ord"], e["name"], e["year"], e["date"], e["debated"], e["start"], e["end"]) for e in events])
    db.executemany("INSERT INTO charts VALUES (?,?,?,?,?,?,?,?)", [
        (c["id"], c["ord"], c["kind"], c["title"], c["subtitle"], c["sources"], c["scope"], c["body"]) for c in charts])
    db.executemany("INSERT INTO labels VALUES (?,?,?,?,?,?,?)", [
        (l["text"], l.get("sub"), l["lon"], l["lat"], l["min"], l["kind"], l.get("angle", 0)) for l in labels])
    db.executemany("INSERT INTO translations VALUES (?,?,?,?)", translations)
    db.commit()
    db.execute("VACUUM")
    db.close()


def build():
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    counts = verse_counts()
    print("Building context data")
    places, links = build_places(counts)
    eras, events, chapter_rows = build_timeline(counts)
    charts = build_charts(counts, {p["obid"]: p for p in places})
    labels = load_json("map_labels.json")["labels"]
    db_path = os.path.join(OUTPUT_DIR, "Context.sqlite")
    with tempfile.TemporaryDirectory() as tmp:
        tmp_db = os.path.join(tmp, "Context.sqlite")
        write_database(tmp_db, places, links, eras, events, chapter_rows, charts, labels, load_translations())
        os.replace(tmp_db, db_path)
    basemap_path = os.path.join(OUTPUT_DIR, "Basemap.bin")
    with open(basemap_path, "wb") as handle:
        handle.write(build_basemap())
    for path in (db_path, basemap_path):
        print(f"  {os.path.relpath(path, ROOT)}: {os.path.getsize(path) / 1024:.0f} KB")
    return db_path, basemap_path


# MARK: - Checks

def check(db_path, basemap_path):
    db = sqlite3.connect(db_path)
    failures = []

    def expect(condition, message):
        if not condition:
            failures.append(message)

    def places_in(book, first, last):
        lo, hi = key(BOOK_ORD[book], first, 0), key(BOOK_ORD[book], last, 999)
        return {row[0] for row in db.execute(
            "SELECT DISTINCT p.name FROM place_verses v JOIN places p ON p.id = v.place WHERE v.verse BETWEEN ? AND ?",
            (lo, hi))}

    acts = places_in("ACT", 13, 14)
    for name in ("Antioch", "Cyprus", "Iconium", "Lystra", "Derbe", "Paphos", "Perga"):
        expect(name in acts, f"Acts 13–14 should mention {name}; got {sorted(acts)}")
    kings = places_in("1KI", 12, 12)
    for name in ("Shechem", "Bethel", "Dan", "Jerusalem"):
        expect(name in kings, f"1 Kings 12 should mention {name}; got {sorted(kings)}")

    lon, lat = db.execute("SELECT lon, lat FROM places WHERE obid = 'a15257a'").fetchone()
    expect(abs(lon - 35.23) < 0.05 and abs(lat - 31.78) < 0.05, f"Jerusalem at {lon},{lat}")
    lon, lat = db.execute("SELECT lon, lat FROM places WHERE name = 'Rome'").fetchone()
    expect(abs(lon - 12.48) < 0.1 and abs(lat - 41.89) < 0.1, f"Rome at {lon},{lat}")
    jerusalem_mentions = db.execute("SELECT mentions FROM places WHERE obid = 'a15257a'").fetchone()[0]
    expect(jerusalem_mentions > 700, f"Jerusalem mentioned in only {jerusalem_mentions} verses")
    expect(db.execute("SELECT count(*) FROM places WHERE lon < -20 OR lon > 60 OR lat < 10 OR lat > 45").fetchone()[0] == 0,
           "a place lies outside the map")

    def era(book, chapter):
        row = db.execute("SELECT era, year FROM chapter_eras WHERE book = ? AND chapter = ? AND ord = 0",
                         (BOOK_ORD[book], chapter)).fetchone()
        return row
    expect(era("GEN", 12)[0] == "patriarchs", f"Genesis 12 era {era('GEN', 12)}")
    expect(era("GEN", 3)[0] == "primeval", "Genesis 3 should be primeval")
    expect(era("1KI", 12) == ("divided", -931), f"1 Kings 12 era {era('1KI', 12)}")
    expect(era("ACT", 13)[0] == "church", "Acts 13 should be the early church")
    expect(era("MAT", 5)[0] == "christ", "Matthew 5 should be the life of Christ")
    total = sum(1 for _ in db.execute("SELECT DISTINCT book, chapter FROM chapter_eras"))
    expect(total == 1189, f"{total} chapters mapped to eras, expected 1189")
    starts = [row for row in db.execute("SELECT start, end FROM eras WHERE start IS NOT NULL ORDER BY ord")]
    expect(all(a[1] == b[0] for a, b in zip(starts, starts[1:])), f"eras are not contiguous: {starts}")

    kinds = {row[0] for row in db.execute("SELECT kind FROM charts")}
    expect({"kings", "journeys", "tribes", "feasts"} <= kinds, f"charts: {kinds}")
    kings_body = json.loads(db.execute("SELECT body FROM charts WHERE id = 'kings'").fetchone()[0])
    expect(len(kings_body["israel"]) == 19 and len(kings_body["judah"]) == 20,
           f"kings chart has {len(kings_body['israel'])} Israel / {len(kings_body['judah'])} Judah")
    journeys = json.loads(db.execute("SELECT body FROM charts WHERE id = 'journeys'").fetchone()[0])["journeys"]
    first = [s["name"] for s in journeys[0]["stops"]]
    expect(first[:4] == ["Antioch", "Seleucia", "Salamis", "Paphos"], f"first journey starts {first[:4]}")
    expect(journeys[-1]["stops"][-1]["name"] == "Rome", "the voyage should end at Rome")

    with open(basemap_path, "rb") as handle:
        data = handle.read()
    expect(data[:4] == b"SABM", "basemap magic")
    version, layer_count = struct.unpack_from("<HH", data, 4)
    expect(version == 1 and layer_count == 6, f"basemap version {version}, {layer_count} layers")
    expect(len(data) < 2_500_000, f"basemap is {len(data)} bytes")
    expect(os.path.getsize(db_path) < 3_000_000, f"database is {os.path.getsize(db_path)} bytes")

    db.close()
    if failures:
        print("CHECK FAILED")
        for failure in failures:
            print("  - " + failure)
        sys.exit(1)
    print("  checks passed")


def main():
    db_path, basemap_path = build()
    if "--check" in sys.argv:
        check(db_path, basemap_path)


if __name__ == "__main__":
    main()
