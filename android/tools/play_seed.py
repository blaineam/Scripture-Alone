#!/usr/bin/env python3
"""Builds a synthetic userdata.sqlite for Play Store screenshots.

Same schema as UserDataStore (user_version 2). All content is invented demo data — never a real
reader's library. Usage: play_seed.py <out.sqlite> [slide.jpg]
"""
import sqlite3, sys, os, uuid, datetime

out = sys.argv[1]
slide = sys.argv[2] if len(sys.argv) > 2 else None
if os.path.exists(out):
    os.remove(out)
db = sqlite3.connect(out)
db.executescript("""
CREATE TABLE highlights (verse_key INTEGER NOT NULL, color TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX highlights_verse ON highlights(verse_key);
CREATE TABLE notes (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '',
  anchors TEXT NOT NULL DEFAULT '', first_verse_key INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL, origin TEXT NOT NULL DEFAULT 'manual');
CREATE TABLE favorites (id TEXT PRIMARY KEY NOT NULL, range TEXT NOT NULL, start_key INTEGER NOT NULL,
  end_key INTEGER NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE slide_photos (note_id TEXT PRIMARY KEY NOT NULL, jpeg BLOB NOT NULL);
PRAGMA user_version = 2;
""")

def ms(days_ago, hour=9):
    t = datetime.datetime(2026, 9, 20, hour, tzinfo=datetime.timezone.utc) - datetime.timedelta(days=days_ago)
    return int(t.timestamp() * 1000)

def k(b, c, v): return b * 1_000_000 + c * 1000 + v

hl = [((43, 10, 11), "yellow"), ((43, 10, 14), "green"), ((43, 10, 27), "blue"), ((43, 10, 28), "blue"),
      ((43, 3, 16), "yellow"), ((43, 3, 17), "yellow"), ((19, 23, 1), "green"), ((45, 8, 28), "pink"),
      ((43, 14, 6), "purple"), ((50, 4, 13), "blue"), ((23, 40, 31), "yellow")]
for i, ((b, c, v), col) in enumerate(hl):
    db.execute("INSERT INTO highlights VALUES (?,?,?)", (k(b, c, v), col, ms(10 - i % 7)))

notes = [
    ("The Good Shepherd",
     "• He knows his own by name (v. 14)\n• He lays down his life for the sheep (v. 11, 15)\n"
     "• One flock, one shepherd (v. 16)\n\nPsalm 23 answers John 10: the LORD who shepherds David is the shepherd who gives his life.\n\n"
     "Sunday Morning Series · Week 4",
     [(k(43, 10, 11), k(43, 10, 15)), (k(19, 23, 1), k(19, 23, 6))], ms(0, 17), "camera"),
    ("No condemnation",
     "Life in the Spirit. Verse 1 is the hinge — everything after it flows from “no condemnation.”\n\n"
     "Adoption (v. 15): we cry “Abba, Father.”",
     [(k(45, 8, 1), k(45, 8, 17))], ms(6, 18), "manual"),
    ("Born of the Spirit",
     "• Nicodemus comes by night (v. 2)\n• “You must be born anew” — the Spirit’s work, not ours\n"
     "• The serpent in the wilderness points to the cross (Numbers 21:8–9)",
     [(k(43, 3, 1), k(43, 3, 21)), (k(4, 21, 4), k(4, 21, 9))], ms(13, 18), "manual"),
    ("Contentment",
     "Paul learned it — it didn't come naturally. Strength for plenty and for want.",
     [(k(50, 4, 10), k(50, 4, 13))], ms(20, 7), "manual"),
]
good_shepherd_id = None
for title, body, anchors, t, origin in notes:
    nid = str(uuid.uuid4())
    if good_shepherd_id is None:
        good_shepherd_id = nid
    anchors = sorted(anchors)
    db.execute("INSERT INTO notes VALUES (?,?,?,?,?,?,?,?)",
               (nid, title, body, ",".join(f"{a}-{b}" for a, b in anchors), anchors[0][0], t, t, origin))
if slide:
    db.execute("INSERT INTO slide_photos VALUES (?,?)", (good_shepherd_id, open(slide, "rb").read()))

favs = [(k(45, 8, 38), k(45, 8, 39)), (k(19, 23, 1), k(19, 23, 1)), (k(43, 14, 6), k(43, 14, 6)),
        (k(23, 40, 31), k(23, 40, 31)), (k(43, 10, 11), k(43, 10, 11))]
for i, (a, b) in enumerate(favs):
    db.execute("INSERT INTO favorites VALUES (?,?,?,?,?)", (str(uuid.uuid4()), f"{a}-{b}", a, b, ms(i + 1)))
db.commit()
db.execute("PRAGMA journal_mode=DELETE")
db.close()
print("wrote", out)
