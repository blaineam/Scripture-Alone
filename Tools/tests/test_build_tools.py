"""Tests for the offline build and packaging tools, run without the network.

    python3 -m unittest discover -s Tools/tests

Everything is built into temporary directories: nothing in the repository is written. Where the
repository holds a tool's output (the Bible stores, the widget list, the shipped ASV package), the
test also holds that output to what the tool makes now, so a committed file that has drifted from
its source fails here instead of shipping.

Not covered: build_study.py, build_context.py, build_interlinear.py and build_topics.py, which fetch
their sources over the network.
"""

import io
import json
import os
import sqlite3
import sys
import tempfile
import unittest
import warnings
from contextlib import redirect_stdout, redirect_stderr
from pathlib import Path
from unittest import mock

TOOLS = Path(__file__).resolve().parent.parent
ROOT = TOOLS.parent
sys.path.insert(0, str(TOOLS))

import build_bibles  # noqa: E402
import build_companion_data  # noqa: E402
import package_translation as pt  # noqa: E402
from cryptography.exceptions import InvalidSignature, InvalidTag  # noqa: E402
from cryptography.hazmat.primitives.asymmetric import ed25519  # noqa: E402

# The build tools leave SQLite connections for the interpreter to close; that is theirs, not a test failure.
warnings.simplefilter("ignore", ResourceWarning)

BIBLES = ROOT / "ScriptureAlone" / "Resources" / "Bibles"
PACKAGES = ROOT / "ScriptureAlone" / "Resources" / "Packages"


def quietly(function, *args, **kwargs):
    with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
        return function(*args, **kwargs)


def translation(tid):
    return next(t for t in build_bibles.TRANSLATIONS if t["id"] == tid)


def layout_rows(path):
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return connection.execute("SELECT book, chapter, layout FROM chapters ORDER BY book, chapter").fetchall()
    finally:
        connection.close()


def verse_rows(path):
    connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    try:
        return connection.execute("SELECT id, text, red FROM verses ORDER BY id").fetchall()
    finally:
        connection.close()


class BuildBiblesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        # Every translation: the tool's own check reads all of them (numbering maps against the KJV).
        cls.paths = {t["id"]: quietly(build_bibles.build, t, cls.directory.name) for t in build_bibles.TRANSLATIONS}

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_known_verses_pass_the_tools_own_check(self):
        quietly(build_bibles.check, self.paths)

    def test_built_stores_match_the_shipped_ones(self):
        for tid, built in self.paths.items():
            with self.subTest(tid):
                shipped = BIBLES / f"{tid}.sqlite"
                self.assertEqual(verse_rows(built), verse_rows(shipped),
                                 f"{tid}.sqlite's verses differ from what build_bibles.py makes")
                if tid != "KJV":
                    self.assertEqual(layout_rows(built), layout_rows(shipped),
                                     f"{tid}.sqlite's chapter layouts differ from what build_bibles.py makes")

    # Known drift, left as found: the committed KJV.sqlite was built before the tool merged italic and
    # small-caps spans the way it does now (Exodus 33:9's "LORD" carries an extra italic span; 91
    # chapters differ, the verse text in none). The English stores are deliberately kept as committed
    # (see b73cac2) because a rebuilt store is a new asset-pack version. This starts passing — and
    # must then lose the decorator — once KJV.sqlite is rebuilt.
    @unittest.expectedFailure
    def test_kjv_layout_matches_the_tool(self):
        self.assertEqual(layout_rows(self.paths["KJV"]), layout_rows(BIBLES / "KJV.sqlite"))

    def test_schema_the_app_reads(self):
        connection = sqlite3.connect(self.paths["BSB"])
        tables = {name for (name,) in connection.execute("SELECT name FROM sqlite_master WHERE type IN ('table')")}
        self.assertTrue({"meta", "books", "chapters", "verses", "verses_fts"} <= tables, tables)
        meta = dict(connection.execute("SELECT key, value FROM meta"))
        self.assertEqual(meta["id"], "BSB")
        self.assertTrue(meta.get("copyright"))
        self.assertEqual(connection.execute("SELECT count(*) FROM books").fetchone()[0], 66)
        self.assertEqual(connection.execute("SELECT sum(chapters) FROM books").fetchone()[0], 1189)
        # Every chapter's layout is the compact JSON the reader decodes.
        for (layout,) in connection.execute("SELECT layout FROM chapters LIMIT 50"):
            self.assertIn("b", json.loads(layout))
        # Full-text search finds a verse by its words.
        hit = connection.execute("SELECT rowid FROM verses_fts WHERE verses_fts MATCH 'shepherd' LIMIT 1").fetchone()
        self.assertIsNotNone(hit)


class CompanionDataTest(unittest.TestCase):
    """The widget and watch data, rebuilt into a temporary directory."""

    def test_rebuild_matches_the_shipped_list_and_passes_its_check(self):
        with tempfile.TemporaryDirectory() as out:
            with mock.patch.multiple(build_companion_data,
                                     JSON_OUT=os.path.join(out, "DailyVerses.json"),
                                     DOC_OUT=os.path.join(out, "daily-verses.md"),
                                     WATCH_DIR=out):
                names = sqlite3.connect(BIBLES / "BSB.sqlite").execute("SELECT code, name FROM books")
                build_companion_data.NAMES.update(dict(names))
                entries = build_companion_data.parse_list()
                catalog = quietly(build_companion_data.build_json, entries)
                quietly(build_companion_data.build_doc, entries)
                quietly(build_companion_data.build_watch_dbs)
                quietly(build_companion_data.check, catalog)
                built = json.loads(Path(out, "DailyVerses.json").read_text(encoding="utf-8"))
        shipped = json.loads((ROOT / "ScriptureAlone" / "Shared" / "DailyVerses.json").read_text(encoding="utf-8"))
        self.assertEqual(built, shipped, "ScriptureAlone/Shared/DailyVerses.json differs from what the tool makes")


class PackageTranslationTest(unittest.TestCase):
    """The publisher's packaging tool — the Python half of the .sabible format (the Swift half reads)."""

    @staticmethod
    def tiny_store(directory):
        path = Path(directory) / "TINY.sqlite"
        connection = sqlite3.connect(path)
        connection.executescript("""
            CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT);
            CREATE TABLE chapters (book INTEGER, chapter INTEGER, verses INTEGER, layout TEXT);
            CREATE TABLE verses (id INTEGER PRIMARY KEY, text TEXT, red TEXT);
        """)
        connection.executemany("INSERT INTO meta VALUES (?, ?)", [
            ("id", "TINY"), ("name", "Tiny Test Bible"), ("abbreviation", "TTB"),
            ("copyright", "Public domain"), ("license", "Public domain")])
        connection.executemany("INSERT INTO chapters VALUES (?, ?, ?, ?)", [
            (43, 3, 2, '{"b":[{"k":"p","f":[{"v":16,"n":1,"t":"Zorp blag."}]}]}'),
            (43, 4, 1, '{"b":[{"k":"p","f":[{"v":1,"n":1,"t":"Quux."}]}]}')])
        connection.executemany("INSERT INTO verses VALUES (?, ?, ?)", [
            (43003016, "Zorp blag.", "[[0, 4]]"), (43003017, "Flim flam.", None), (43004001, "Quux.", "[]")])
        connection.commit()
        connection.close()
        return path

    def build(self, chapters, policy=None, **identity):
        self.content_key = os.urandom(32)
        signing = ed25519.Ed25519PrivateKey.generate()
        self.public = signing.public_key().public_bytes_raw()
        policy = policy or {"allowCopy": True, "allowShare": False, "allowVerseImages": True,
                            "allowNotesExport": True, "allowExternalHandoff": False,
                            "allowOfflineStorage": True, "maxQuotationVerses": 25}
        identity = {"id": "TINY", "name": "Tiny Test Bible", "abbreviation": "TTB", "publisher": "Test",
                    "copyright": "Public domain", "license": "Public domain", **identity}
        return quietly(pt.build_package, identity=identity, policy=policy, chapters=chapters,
                       content_key=self.content_key, signing_key=signing.private_bytes_raw())

    def test_round_trip_text_and_terms(self):
        with tempfile.TemporaryDirectory() as directory:
            meta, chapters = pt.read_store(self.tiny_store(directory))
        self.assertEqual(meta["abbreviation"], "TTB")
        # Red-letter spans are carried; an empty span list is dropped, not stored as [].
        self.assertEqual(chapters[0]["rows"], [{"i": 43003016, "t": "Zorp blag.", "r": [[0, 4]]},
                                               {"i": 43003017, "t": "Flim flam."}])
        self.assertEqual(chapters[1]["rows"], [{"i": 43004001, "t": "Quux."}])

        data = self.build(chapters)
        header, header_bytes, signature, _ = pt.parse_header(data)
        self.assertEqual(header["translation"]["id"], "TINY")
        self.assertEqual(header["policy"]["maxQuotationVerses"], 25)
        self.assertFalse(header["policy"]["allowShare"])
        self.assertEqual(header["crypto"]["keyID"], pt.content_key_id(self.content_key))
        self.assertEqual(header["crypto"]["publisherKeyID"], pt.publisher_key_id(self.public))
        ed25519.Ed25519PublicKey.from_public_bytes(self.public).verify(signature, header_bytes)

        _, back = pt.read_package_chapters(data, self.content_key)
        self.assertEqual([(c["book"], c["chapter"], c["layout"], c["rows"]) for c in back],
                         [(c["book"], c["chapter"], c["layout"], c["rows"]) for c in chapters])
        # The text is sealed: no verse appears in the file in the clear.
        self.assertNotIn(b"Zorp", data)
        self.assertNotIn(b"Flim flam", data)

    def test_the_wrong_key_opens_nothing(self):
        with tempfile.TemporaryDirectory() as directory:
            _, chapters = pt.read_store(self.tiny_store(directory))
        data = self.build(chapters)
        with self.assertRaises(InvalidTag):
            pt.read_package_chapters(data, os.urandom(32))

    def test_edited_terms_break_the_signature(self):
        with tempfile.TemporaryDirectory() as directory:
            _, chapters = pt.read_store(self.tiny_store(directory))
        data = self.build(chapters)
        header, header_bytes, signature, _ = pt.parse_header(data)
        edited = header_bytes.replace(b'"allowShare":false', b'"allowShare":true ')
        self.assertNotEqual(edited, header_bytes, "the policy text was not where the test expected it")
        with self.assertRaises(InvalidSignature):
            ed25519.Ed25519PublicKey.from_public_bytes(self.public).verify(signature, edited)

    def test_not_a_package(self):
        with self.assertRaises(SystemExit):
            pt.parse_header(b"PK\x03\x04 not a package at all")

    def test_the_wearables_term_is_set_by_flag_and_signed(self):
        """`--no-wearables` writes `wearables: prohibited` into the signed policy; unset writes nothing."""
        with tempfile.TemporaryDirectory() as outside:
            store = self.tiny_store(outside)
            keys = Path(outside) / "keys"
            quietly(pt.main, ["keygen", "--out-dir", str(keys)])
            common = ["build", "--store", str(store), "--content-key", str(keys / "content.key"),
                      "--signing-key", str(keys / "signing.key"), "--publisher", "Test", "--no-index"]
            plain, restricted = Path(outside) / "plain.sabible", Path(outside) / "restricted.sabible"
            quietly(pt.main, common + ["--out", str(plain)])
            quietly(pt.main, common + ["--out", str(restricted), "--no-wearables"])

            self.assertNotIn("wearables", pt.parse_header(plain.read_bytes())[0]["policy"])
            header, header_bytes, signature, _ = pt.parse_header(restricted.read_bytes())
            self.assertEqual(header["policy"]["wearables"], "prohibited")
            for package, term in ((plain, "allowed"), (restricted, "prohibited")):
                out = io.StringIO()
                with redirect_stdout(out):
                    pt.main(["wearables", "--package", str(package)])
                self.assertEqual(out.getvalue().strip(), term)

            # Flipped to the same length — JSON whitespace pads "allowed" — the signature fails.
            public = ed25519.Ed25519PublicKey.from_public_bytes(pt.read_key_bytes(keys / "signing.pub", "k"))
            public.verify(signature, header_bytes)
            flipped = header_bytes.replace(b'"wearables":"prohibited"', b'"wearables":"allowed"   ')
            self.assertNotEqual(flipped, header_bytes)
            self.assertEqual(json.loads(flipped)["policy"]["wearables"], "allowed")
            with self.assertRaises(InvalidSignature):
                public.verify(signature, flipped)

            # --wearables allowed states it; anything else is refused before a package is written.
            stated = Path(outside) / "stated.sabible"
            quietly(pt.main, common + ["--out", str(stated), "--wearables", "allowed"])
            self.assertEqual(pt.parse_header(stated.read_bytes())[0]["policy"]["wearables"], "allowed")
            with self.assertRaises(SystemExit):
                quietly(pt.main, common + ["--out", str(stated), "--wearables", "sometimes"])

    def test_a_store_built_with_no_wearables_carries_it_and_it_only_tightens(self):
        policy = {"allowCopy": True, "wearables": "allowed"}
        self.assertEqual(pt.restrict_wearables(policy, None)["wearables"], "allowed")
        self.assertEqual(pt.restrict_wearables(policy, "prohibited")["wearables"], "prohibited")
        self.assertEqual(pt.restrict_wearables({"wearables": "prohibited"}, "allowed")["wearables"], "prohibited")
        self.assertNotIn("wearables", pt.restrict_wearables({"allowCopy": True}, None))
        with self.assertRaises(SystemExit):
            quietly(pt.check_wearables, {"wearables": "Prohibited"})
        # The apps' reading: absent or "allowed" is allowed; anything else is not.
        self.assertEqual(pt.wearables_term({}), "allowed")
        self.assertEqual(pt.wearables_term({"wearables": "allowed"}), "allowed")
        self.assertEqual(pt.wearables_term({"wearables": "prohibited"}), "prohibited")
        self.assertEqual(pt.wearables_term({"wearables": "maybe"}), "prohibited")
        # The licence file carries no term today, so the NASB packages keep today's behaviour.
        for edition in ("NASB2020", "NASB1995"):
            self.assertEqual(pt.wearables_term(pt.licensed_edition(edition)[1]), "allowed")

    def test_keys_inside_the_repository_are_refused(self):
        with self.assertRaises(SystemExit):
            quietly(pt.refuse_if_inside_repository, ROOT / "keys" / "content.key", "a content key")
        with self.assertRaises(SystemExit):
            quietly(pt.refuse_if_inside_repository, TOOLS, "a key directory")
        with tempfile.TemporaryDirectory() as outside:
            self.assertEqual(pt.refuse_if_inside_repository(Path(outside) / "new" / "dir", "x"),
                             Path(outside).resolve() / "new" / "dir")

    def test_key_files_are_hex_or_raw_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as outside:
            raw = os.urandom(32)
            pt.write_key_bytes(Path(outside) / "hex.key", raw)
            self.assertEqual(oct(os.stat(Path(outside) / "hex.key").st_mode & 0o777), "0o600")
            self.assertEqual(pt.read_key_bytes(Path(outside) / "hex.key", "k"), raw)
            Path(outside, "raw.key").write_bytes(raw)
            os.chmod(Path(outside, "raw.key"), 0o600)
            self.assertEqual(pt.read_key_bytes(Path(outside) / "raw.key", "k"), raw)
            Path(outside, "short.key").write_bytes(b"abc")
            with self.assertRaises(SystemExit):
                quietly(pt.read_key_bytes, Path(outside) / "short.key", "k")
            with self.assertRaises(SystemExit):
                quietly(pt.read_key_bytes, Path(outside) / "missing.key", "k")


class ShippedASVPackageTest(unittest.TestCase):
    """The ASV the app downloads as a sealed package: signed by the key the app pins, opened with the
    published seed, and word for word the store it was made from."""

    @classmethod
    def setUpClass(cls):
        cls.data = (PACKAGES / "ASV.sabible").read_bytes()

    def test_signed_by_the_pinned_key(self):
        header, header_bytes, signature, _ = pt.parse_header(self.data)
        public = (PACKAGES / "bundled-signing.pub").read_bytes()
        ed25519.Ed25519PublicKey.from_public_bytes(public).verify(signature, header_bytes)
        self.assertEqual(header["crypto"]["publisherKeyID"], pt.publisher_key_id(public))
        self.assertEqual(header["crypto"]["keyID"], pt.content_key_id(pt.bundled_content_key("ASV")))

    def test_every_chapter_opens_and_matches_the_store(self):
        header, chapters = pt.read_package_chapters(self.data, pt.bundled_content_key("ASV"))
        self.assertEqual(len(chapters), 1189)
        _, store = pt.read_store(BIBLES / "ASV.sqlite")
        self.assertEqual([(c["book"], c["chapter"], c["rows"]) for c in chapters],
                         [(c["book"], c["chapter"], c["rows"]) for c in store],
                         "ASV.sabible's text differs from ASV.sqlite")
        john = next(c for c in chapters if (c["book"], c["chapter"]) == (43, 3))
        self.assertTrue(next(r["t"] for r in john["rows"] if r["i"] == 43003016).startswith("For God so loved the world"))


if __name__ == "__main__":
    unittest.main()
