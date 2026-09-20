#!/usr/bin/env python3
"""Build a signed, encrypted translation package (`.sabible`) from a Bible store.

This is the tool a publisher runs, on their own machine, with their own keys. It reads a SQLite
store in the shape `Tools/build_bibles.py` produces, seals each chapter with the publisher's content
key, and signs the header with the publisher's Ed25519 signing key. The plaintext never leaves the
machine it was built on, and the key that opens the result never enters this repository.

    # once, somewhere outside this repository
    ./Tools/package_translation.py keygen --out-dir ~/keys/scripture-alone

    # per translation
    ./Tools/package_translation.py build \
        --store ScriptureAlone/Resources/Bibles/ASV.sqlite \
        --out ~/packages/ASV.sabible \
        --content-key ~/keys/scripture-alone/content.key \
        --signing-key ~/keys/scripture-alone/signing.key \
        --publisher "Example Bible Publishers" \
        --no-allow-external-handoff --max-quotation-verses 500

    # anyone, with no key at all
    ./Tools/package_translation.py inspect --package ~/packages/ASV.sabible

    # the demonstration: the bundled public-domain texts, packaged with a demonstration key
    ./Tools/package_translation.py demo

**Keys must live outside the repository working tree, and this tool refuses to run if they do not.**
That refusal is part of the demonstration: it is not possible to follow these instructions and end up
with a key in a public git repository by accident.

The format is documented in `docs/encrypted-translations.md` and implemented for reading in
`ScriptureAloneCore/Sources/ScriptureAloneCore/Package/TranslationPackage.swift`. Two independent
implementations of one format is deliberate — a publisher can build packages with this tool and
confirm the app reads them, and a divergence shows up as a failing test rather than as a package that
will not open.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import sqlite3
import struct
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path

try:
    import unicodedata

    from cryptography.hazmat.primitives import hashes, hmac
    from cryptography.hazmat.primitives.kdf.hkdf import HKDF
    from cryptography.hazmat.primitives.asymmetric import ed25519
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # pragma: no cover - environment problem, not a code path
    sys.exit("This tool needs the `cryptography` package: python3 -m pip install cryptography")

MAGIC = b"SABIBLE\0"
FORMAT_VERSION = 1
CIPHER = "AES-256-GCM"
SIGNATURE_ALGORITHM = "Ed25519"
AAD_VERSION = "sabible-chapter-v1"
NONCE_BYTES = 12
TAG_BYTES = 16
SEALED_OVERHEAD = NONCE_BYTES + TAG_BYTES

DEMO_DIRECTORY = Path.home() / ".scripture-alone-demo"

# ------------------------------------------------------------------------- search index
#
# A packaged translation is searchable, and the index is sealed exactly like the text is.
#
# The obvious design — HMAC(word) → postings, left in the clear — is wrong for this corpus. An
# attacker knows the file is a Bible. Token frequencies and positions can be aligned against any
# public Bible to work out which hash is which word, and that reconstructs the wording, which is the
# asset. Searchable-encryption schemes assume the plaintext distribution is unknown; ours is the most
# published text in history. So the postings are encrypted, bucket by bucket, and a search opens only
# the buckets its query needs.
INDEX_TOKENIZER = "sabible-tokens-v1"
INDEX_AAD = "sabible-index-v1"
INDEX_BUCKETS = 256
INDEX_PREFIX_MIN = 3
# Prefixes run to ten characters, not six. Six was the first choice, but a prefix longer than the
# longest indexed one has to be verified against the text — which means decrypting chapters that turn
# out not to match, exactly what a search is supposed to avoid. Measured on the ASV, going from six to
# ten costs 189,134 more (prefix, verse) pairs out of 1.2 million, about 0.3 MB, and makes every
# realistic prefix exact: chapters decrypted then equals chapters with hits.
INDEX_PREFIX_MAX = 10
# Sealed buckets are padded to a multiple of this, so their sizes say as little as possible about
# what is in them. See `docs/encrypted-translations.md` for what a size still reveals.
INDEX_PADDING = 4096

WORD_ENTRY = 0
PREFIX_ENTRY = 1


def fold(text: str) -> str:
    """Tokeniser v1, and the Swift reader does exactly this: NFD, drop combining marks, lowercase."""
    decomposed = unicodedata.normalize("NFD", text)
    return "".join(c for c in decomposed if unicodedata.category(c) != "Mn").lower()


def tokenize(text: str) -> list[str]:
    """Runs of letters and digits, like SQLite's unicode61 — apostrophes separate, as they do there."""
    tokens: list[str] = []
    current: list[str] = []
    for character in fold(text):
        if character.isalnum():
            current.append(character)
        elif current:
            tokens.append("".join(current))
            current = []
    if current:
        tokens.append("".join(current))
    return tokens


def index_key(content_key: bytes, translation_id: str) -> bytes:
    """A separate key for the index, so a token tag can never be confused with content key material.

    The translation id goes into the derivation as well. A publisher who packages three translations
    under one content key would otherwise get the same token → bucket mapping in all three, and bucket
    sizes could be correlated across the files. Per-translation derivation costs nothing and removes
    that.
    """
    return HKDF(algorithm=hashes.SHA256(), length=32, salt=None,
                info=b"SABIBLE index v1\0" + translation_id.encode("utf-8")).derive(content_key)


def token_tag(key: bytes, kind: str, token: str) -> bytes:
    mac = hmac.HMAC(key, hashes.SHA256())
    mac.update(f"{kind}:{token}".encode("utf-8"))
    return mac.finalize()


def varint(value: int) -> bytes:
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def build_index_buckets(chapters: list[dict], content_key: bytes, translation_id: str) -> list[bytes]:
    """Postings for every token and every 3–6 character prefix, bucketed and encoded.

    Word postings carry (verse, word position) so a phrase is an adjacency check rather than a
    substring scan. Prefix postings are verse-level only: the app's own search prefix-matches just the
    last word of a query, so positions there would be weight with no use.
    """
    key = index_key(content_key, translation_id)
    words: dict[bytes, dict[int, list[int]]] = {}
    prefixes: dict[bytes, set[int]] = {}
    for chapter in chapters:
        for row in chapter["rows"]:
            verse = row["i"]
            for position, token in enumerate(tokenize(row["t"])):
                words.setdefault(token_tag(key, "w", token), {}).setdefault(verse, []).append(position)
                for length in range(INDEX_PREFIX_MIN, INDEX_PREFIX_MAX + 1):
                    if len(token) < length:
                        break
                    prefixes.setdefault(token_tag(key, "p", token[:length]), set()).add(verse)

    buckets: list[list[tuple[bytes, int, object]]] = [[] for _ in range(INDEX_BUCKETS)]
    for tag, postings in words.items():
        buckets[int.from_bytes(tag[:4], "big") % INDEX_BUCKETS].append((tag[:8], WORD_ENTRY, postings))
    for tag, verses in prefixes.items():
        buckets[int.from_bytes(tag[:4], "big") % INDEX_BUCKETS].append((tag[:8], PREFIX_ENTRY, verses))

    encoded = []
    for entries in buckets:
        payload = bytearray(varint(len(entries)))
        for token_id, kind, postings in sorted(entries, key=lambda entry: entry[0]):
            payload += token_id
            payload.append(kind)
            if kind == WORD_ENTRY:
                payload += varint(len(postings))
                previous = 0
                for verse in sorted(postings):
                    payload += varint(verse - previous)
                    previous = verse
                    positions = postings[verse]
                    payload += varint(len(positions))
                    last = 0
                    for position in positions:
                        payload += varint(position - last)
                        last = position
            else:
                payload += varint(len(postings))
                previous = 0
                for verse in sorted(postings):
                    payload += varint(verse - previous)
                    previous = verse
        body = varint(len(payload)) + bytes(payload)
        padded = body + bytes((-len(body)) % INDEX_PADDING)
        encoded.append(padded)
    return encoded


def index_associated_data(package_id: str, translation_id: str, bucket: int,
                          header_digest: bytes) -> bytes:
    """Same discipline as a chapter, and a different domain string, so the two can never be swapped."""
    return "\n".join([INDEX_AAD, package_id, translation_id, str(bucket),
                       header_digest.hex()]).encode("utf-8")


# --------------------------------------------------------------------------------------- keys


def repo_root() -> Path:
    """The working tree this tool lives in — the place a key must never be."""
    here = Path(__file__).resolve().parent
    try:
        top = subprocess.run(["git", "-C", str(here), "rev-parse", "--show-toplevel"],
                             capture_output=True, text=True, check=True).stdout.strip()
        if top:
            return Path(top).resolve()
    except (subprocess.CalledProcessError, FileNotFoundError):
        pass
    return here.parent.resolve()


REPO_ROOT = repo_root()


def refuse_if_inside_repository(path: Path, what: str) -> Path:
    """A content key or signing key inside the working tree is one `git add .` from being public.

    Checked against the *resolved* path, so a symlink in the repository pointing at a key outside it
    is allowed and a symlink outside pointing in is not. Directories that do not exist yet are checked
    by their nearest existing parent, so `keygen` is covered before it writes anything.
    """
    resolved = Path(os.path.abspath(path.expanduser()))
    probe = resolved
    while not probe.exists() and probe != probe.parent:
        probe = probe.parent
    if probe != resolved:
        resolved = probe.resolve() / resolved.relative_to(probe)
    else:
        resolved = resolved.resolve()
    root = repo_root()
    if resolved == root or root in resolved.parents:
        sys.exit(
            f"Refusing to use {what} at {resolved}: it is inside the repository working tree ({root}).\n"
            "Keys must live outside the repository — a key in a public repository is not a key.\n"
            "Put it somewhere like ~/keys/scripture-alone and pass that path instead."
        )
    return resolved


def read_key_bytes(path: Path, what: str) -> bytes:
    """A key file is 64 hex characters, or exactly 32 raw bytes."""
    resolved = refuse_if_inside_repository(path, what)
    if not resolved.exists():
        sys.exit(f"No {what} at {resolved}.")
    raw = resolved.read_bytes()
    mode = resolved.stat().st_mode & 0o777
    if mode & 0o077:
        print(f"warning: {resolved} is readable by other users (mode {mode:o}); chmod 600 it.",
              file=sys.stderr)
    text = raw.strip()
    try:
        decoded = bytes.fromhex(text.decode("ascii"))
        if len(decoded) == 32:
            return decoded
    except (UnicodeDecodeError, ValueError):
        pass
    if len(raw) == 32:
        return raw
    sys.exit(f"{resolved} is not a key: expected 64 hex characters or 32 raw bytes, found {len(raw)} bytes.")


def write_key_bytes(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="ascii") as handle:
        os.chmod(path, 0o600)
        handle.write(raw.hex() + "\n")


def content_key_id(raw: bytes) -> str:
    return hashlib.sha256(b"SABIBLE content key\0" + raw).hexdigest()[:32]


def publisher_key_id(public_raw: bytes) -> str:
    return hashlib.sha256(b"SABIBLE publisher key\0" + public_raw).hexdigest()[:32]


# --------------------------------------------------------------------------------------- store


def read_store(store: Path) -> tuple[dict[str, str], list[dict]]:
    """The meta row and every chapter, exactly as stored: layout JSON and verse rows, untouched.

    Packaging must not reinterpret the text. The layout string and the verse strings go into the
    package as they came out of the store, so a packaged translation renders identically to the store
    it was built from.
    """
    connection = sqlite3.connect(f"file:{store}?mode=ro", uri=True)
    try:
        meta = {key: value for key, value in connection.execute("SELECT key, value FROM meta")}
        chapters = []
        for book, chapter, verses, layout in connection.execute(
                "SELECT book, chapter, verses, layout FROM chapters ORDER BY book, chapter"):
            low = book * 1_000_000 + chapter * 1_000
            rows = []
            for verse_id, text, red in connection.execute(
                    "SELECT id, text, red FROM verses WHERE id BETWEEN ? AND ? ORDER BY id",
                    (low, low + 999)):
                row = {"i": verse_id, "t": text}
                if red:
                    pairs = json.loads(red)
                    if pairs:
                        row["r"] = pairs
                rows.append(row)
            chapters.append({"book": book, "chapter": chapter, "verses": verses,
                             "layout": layout, "rows": rows})
        return meta, chapters
    finally:
        connection.close()


# --------------------------------------------------------------------------------------- build


def associated_data(package_id: str, translation_id: str, book: int, chapter: int,
                    header_digest: bytes) -> bytes:
    """Binds a chapter to its package, its translation, its own reference, and this exact header.

    Move the blob to another package, relabel it as another chapter, or change one byte of the policy,
    and AES-GCM refuses it. Must match `TranslationPackage.associatedData` byte for byte.
    """
    fields = [AAD_VERSION, package_id, translation_id, str(book * 1_000 + chapter),
              header_digest.hex()]
    return "\n".join(fields).encode("utf-8")


def build_package(*, identity: dict, policy: dict, chapters: list[dict], content_key: bytes,
                  signing_key: bytes, package_id: str | None = None,
                  build_index: bool = True) -> bytes:
    if not chapters:
        sys.exit("That store has no chapters.")
    package_id = package_id or str(uuid.uuid4()).upper()

    # Pass one: each chapter's plaintext, and therefore its sealed length and its offset. AES-GCM's
    # combined form is always plaintext + 28 bytes, so the index can be written before anything is
    # sealed — which it must be, because each chapter is bound to a hash of the finished header.
    plaintexts: list[tuple[int, int, bytes]] = []
    entries: list[dict] = []
    offset = 0
    for chapter in chapters:
        payload = json.dumps({"layout": chapter["layout"], "verses": chapter["rows"]},
                             ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        length = len(payload) + SEALED_OVERHEAD
        entries.append({"book": chapter["book"], "chapter": chapter["chapter"],
                        "verses": chapter["verses"], "offset": offset, "length": length})
        plaintexts.append((chapter["book"], chapter["chapter"], payload))
        offset += length

    # The index sits after the chapters in the same body. Its buckets are sealed like chapters, and
    # its parameters live in the signed header, so neither the bucket count nor the tokeniser can be
    # changed under the app.
    index_plaintexts = build_index_buckets(chapters, content_key, identity["id"]) if build_index else []
    index_entries = []
    for bucket, padded in enumerate(index_plaintexts):
        length = len(padded) + SEALED_OVERHEAD
        index_entries.append({"bucket": bucket, "offset": offset, "length": length})
        offset += length

    public_raw = ed25519.Ed25519PrivateKey.from_private_bytes(signing_key) \
        .public_key().public_bytes_raw()
    header = {
        "format": FORMAT_VERSION,
        "packageID": package_id,
        "createdAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "translation": identity,
        "policy": policy,
        "crypto": {
            "cipher": CIPHER,
            "signature": SIGNATURE_ALGORITHM,
            "aad": AAD_VERSION,
            "keyID": content_key_id(content_key),
            "publisherKeyID": publisher_key_id(public_raw),
        },
        "chapters": entries,
    }
    if build_index:
        header["index"] = {
            "tokenizer": INDEX_TOKENIZER,
            "aad": INDEX_AAD,
            "buckets": INDEX_BUCKETS,
            "prefixMin": INDEX_PREFIX_MIN,
            "prefixMax": INDEX_PREFIX_MAX,
            "padding": INDEX_PADDING,
            "entries": index_entries,
        }
    header_bytes = json.dumps(header, ensure_ascii=False, separators=(",", ":"),
                              sort_keys=True).encode("utf-8")
    header_digest = hashlib.sha256(header_bytes).digest()
    signature = ed25519.Ed25519PrivateKey.from_private_bytes(signing_key).sign(header_bytes)

    # Pass two: seal each chapter against the header that now exists.
    cipher = AESGCM(content_key)
    body = bytearray()
    for (book, chapter, payload), entry in zip(plaintexts, entries):
        nonce = secrets.token_bytes(NONCE_BYTES)
        sealed = nonce + cipher.encrypt(nonce, payload, associated_data(
            package_id, identity["id"], book, chapter, header_digest))
        if len(sealed) != entry["length"]:
            sys.exit("Sealed size didn’t match the index; the package was not written.")
        body += sealed

    for bucket, padded in enumerate(index_plaintexts):
        nonce = secrets.token_bytes(NONCE_BYTES)
        sealed = nonce + cipher.encrypt(nonce, padded, index_associated_data(
            package_id, identity["id"], bucket, header_digest))
        if len(sealed) != index_entries[bucket]["length"]:
            sys.exit("Sealed size didn’t match the index; the package was not written.")
        body += sealed

    return (MAGIC
            + struct.pack(">H", FORMAT_VERSION)
            + struct.pack(">I", len(header_bytes))
            + header_bytes
            + struct.pack(">H", len(signature))
            + signature
            + bytes(body))


def policy_from_arguments(arguments: argparse.Namespace) -> dict:
    policy = {
        "allowCopy": arguments.allow_copy,
        "allowShare": arguments.allow_share,
        "allowVerseImages": arguments.allow_verse_images,
        "allowNotesExport": arguments.allow_notes_export,
        "allowExternalHandoff": arguments.allow_external_handoff,
        "allowOfflineStorage": arguments.allow_offline_storage,
        "maxQuotationVerses": arguments.max_quotation_verses,
    }
    if arguments.expires:
        policy["expires"] = arguments.expires
    if arguments.policy:
        supplied = json.loads(Path(arguments.policy).expanduser().read_text(encoding="utf-8"))
        unknown = set(supplied) - set(policy) - {"expires"}
        if unknown:
            sys.exit(f"Unknown policy keys: {', '.join(sorted(unknown))}")
        policy.update(supplied)
    return policy


# --------------------------------------------------------------------------------------- read back


def parse_header(data: bytes) -> tuple[dict, bytes, bytes, int]:
    if data[:len(MAGIC)] != MAGIC:
        sys.exit("That file is not a translation package.")
    version = struct.unpack(">H", data[len(MAGIC):len(MAGIC) + 2])[0]
    if version != FORMAT_VERSION:
        sys.exit(f"That package is version {version}; this tool writes and reads version {FORMAT_VERSION}.")
    header_length = struct.unpack(">I", data[len(MAGIC) + 2:len(MAGIC) + 6])[0]
    start = len(MAGIC) + 6
    header_bytes = data[start:start + header_length]
    signature_length = struct.unpack(">H", data[start + header_length:start + header_length + 2])[0]
    signature = data[start + header_length + 2:start + header_length + 2 + signature_length]
    body_offset = start + header_length + 2 + signature_length
    return json.loads(header_bytes), header_bytes, signature, body_offset


# --------------------------------------------------------------------------------------- commands


def command_keygen(arguments: argparse.Namespace) -> None:
    directory = refuse_if_inside_repository(Path(arguments.out_dir), "a key directory")
    content = secrets.token_bytes(32)
    signing = ed25519.Ed25519PrivateKey.generate()
    signing_raw = signing.private_bytes_raw()
    public_raw = signing.public_key().public_bytes_raw()
    write_key_bytes(directory / "content.key", content)
    write_key_bytes(directory / "signing.key", signing_raw)
    write_key_bytes(directory / "signing.pub", public_raw)
    print(f"Wrote three keys to {directory}")
    print(f"  content.key  AES-256, key id {content_key_id(content)}")
    print(f"  signing.key  Ed25519 private — never leaves this machine")
    print(f"  signing.pub  Ed25519 public, key id {publisher_key_id(public_raw)}")
    print("\nThe app must pin signing.pub to accept packages signed by signing.key.")


def command_build(arguments: argparse.Namespace) -> None:
    store = Path(arguments.store).expanduser()
    if not store.exists():
        sys.exit(f"No store at {store}.")
    content_key = read_key_bytes(Path(arguments.content_key), "a content key")
    signing_key = read_key_bytes(Path(arguments.signing_key), "a signing key")

    meta, chapters = read_store(store)
    identity = {
        "id": arguments.id or meta.get("id") or store.stem,
        "name": arguments.name or meta.get("name", ""),
        "abbreviation": arguments.abbreviation or meta.get("abbreviation", ""),
        "publisher": arguments.publisher,
        "copyright": arguments.copyright or meta.get("copyright", ""),
        "license": arguments.license or meta.get("license", ""),
    }
    if not identity["copyright"].strip():
        sys.exit("A package needs a copyright line: the reader prints it and the app's gates read it.")

    data = build_package(identity=identity, policy=policy_from_arguments(arguments),
                         chapters=chapters, content_key=content_key, signing_key=signing_key,
                         build_index=arguments.index)
    out = Path(arguments.out).expanduser()
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(data)
    header, _, _, body_offset = parse_header(data)
    print(f"Wrote {out} — {len(data):,} bytes, {len(header['chapters']):,} chapters, "
          f"header {body_offset:,} bytes in the clear")
    if "index" in header:
        lengths = [entry["length"] for entry in header["index"]["entries"]]
        print(f"  search index     {sum(lengths):,} bytes in {len(lengths)} buckets "
              f"(smallest {min(lengths):,}, median {sorted(lengths)[len(lengths)//2]:,}, largest {max(lengths):,})")
    print(f"  content key id   {header['crypto']['keyID']}")
    print(f"  publisher key id {header['crypto']['publisherKeyID']}")
    print(f"  policy           {json.dumps(header['policy'], sort_keys=True)}")


def command_inspect(arguments: argparse.Namespace) -> None:
    """What a package claims, read with no key at all — the point of a plaintext header."""
    data = Path(arguments.package).expanduser().read_bytes()
    header, header_bytes, signature, body_offset = parse_header(data)
    print(json.dumps({key: value for key, value in header.items() if key != "chapters"},
                     indent=2, ensure_ascii=False, sort_keys=True))
    print(f"chapters: {len(header['chapters'])}")
    print(f"header sha256: {hashlib.sha256(header_bytes).hexdigest()}")
    print(f"body: {len(data) - body_offset:,} bytes, sealed")
    if arguments.publisher_key:
        public_raw = read_key_bytes(Path(arguments.publisher_key), "a publisher public key")
        try:
            ed25519.Ed25519PublicKey.from_public_bytes(public_raw).verify(signature, header_bytes)
        except Exception:
            print("signature: DOES NOT VERIFY against that key", file=sys.stderr)
            raise SystemExit(1)
        expected = publisher_key_id(public_raw)
        match = "matches" if expected == header["crypto"]["publisherKeyID"] else "DOES NOT MATCH"
        print(f"signature: verifies; key id {expected} {match} the header")


def command_demo(arguments: argparse.Namespace) -> None:
    """The demonstration: the bundled public-domain texts, packaged with a demonstration key.

    Proves the mechanism end to end without needing anyone's licensed text. The keys live in
    `~/.scripture-alone-demo` — outside the repository, like any other key — and the Swift test suite
    reads these packages from there if they are present.
    """
    directory = refuse_if_inside_repository(Path(arguments.out_dir), "the demonstration key directory")
    if not (directory / "content.key").exists():
        command_keygen(argparse.Namespace(out_dir=str(directory)))
        print()
    content_key = read_key_bytes(directory / "content.key", "a content key")
    signing_key = read_key_bytes(directory / "signing.key", "a signing key")

    bibles = repo_root() / "ScriptureAlone/Resources/Bibles"
    for abbreviation in arguments.translations:
        store = bibles / f"{abbreviation}.sqlite"
        if not store.exists():
            sys.exit(f"No bundled store at {store}.")
        meta, chapters = read_store(store)
        identity = {
            "id": meta.get("id", abbreviation),
            "name": meta.get("name", abbreviation),
            "abbreviation": meta.get("abbreviation", abbreviation),
            "publisher": "Scripture Alone demonstration",
            "copyright": meta.get("copyright", ""),
            "license": meta.get("license", ""),
        }
        # The demonstration carries the publishers' own standard permissions — quotation with
        # attribution, no hand-off to other software — so the app is exercised under real terms
        # rather than under terms that permit everything.
        policy = {"allowCopy": True, "allowShare": True, "allowVerseImages": True,
                  "allowNotesExport": True, "allowExternalHandoff": False,
                  "allowOfflineStorage": True, "maxQuotationVerses": 500}
        data = build_package(identity=identity, policy=policy, chapters=chapters,
                             content_key=content_key, signing_key=signing_key)
        out = directory / f"{abbreviation}.sabible"
        out.write_bytes(data)
        header, _, _, _ = parse_header(data)
        lengths = [entry["length"] for entry in header["index"]["entries"]]
        print(f"{out} — {len(data):,} bytes, {len(chapters):,} chapters, "
              f"index {sum(lengths):,} bytes in {len(lengths)} buckets "
              f"(store was {store.stat().st_size:,} bytes)")


# --------------------------------------------------------------------------------------- CLI


def add_policy_arguments(parser: argparse.ArgumentParser) -> None:
    group = parser.add_argument_group(
        "policy",
        "The publisher's terms. They go into the signed header and the app enforces them; editing "
        "them afterwards invalidates the signature and the package stops opening.")
    group.add_argument("--allow-copy", action=argparse.BooleanOptionalAction, default=True,
                       help="copy verses to the clipboard (default: allowed)")
    group.add_argument("--allow-share", action=argparse.BooleanOptionalAction, default=True,
                       help="share verses as text, including share links (default: allowed)")
    group.add_argument("--allow-verse-images", action=argparse.BooleanOptionalAction, default=True,
                       help="render verses into an image (default: allowed)")
    group.add_argument("--allow-notes-export", action=argparse.BooleanOptionalAction, default=True,
                       help="include verses in an exported notes file (default: allowed)")
    group.add_argument("--allow-external-handoff", action=argparse.BooleanOptionalAction, default=False,
                       help="hand verses to another app to render, e.g. for speech (default: refused)")
    group.add_argument("--allow-offline-storage", action=argparse.BooleanOptionalAction, default=True,
                       help="keep the text on the device at all (default: allowed)")
    group.add_argument("--max-quotation-verses", type=int, default=500,
                       help="most verses that may leave in one quotation (default: 500)")
    group.add_argument("--expires", default=None,
                       help="ISO 8601 date the licence lapses, e.g. 2028-01-01. The package then "
                            "refuses to open.")
    group.add_argument("--policy", default=None,
                       help="a JSON file of the same keys, applied over the flags above")





# The seed for the in-app demonstration is published on purpose. It protects a public-domain text,
# so secrecy would be theatre; what it demonstrates is the *mechanism* — signature, per-chapter
# AEAD, the sealed search index, policy enforcement — running in the shipping app on a translation
# nobody has to licence. A real package uses a key its publisher generates and keeps.
DEMO_SEED = bytes.fromhex("5343524950545552452d414c4f4e452d44454d4f2d534545442d7631212121")
DEMO_SIGNING_SEED = b"scripture-alone-demo-signing-v1"

# The seed and signing key for the translation the app ships sealed. Both are published on purpose:
# they protect a public-domain text, so keeping them secret would be theatre, and this document set
# is trying to be the opposite of theatre. A licensed package uses a key its publisher generates and
# holds, delivered as described in docs/encrypted-translations.md.
BUNDLED_SEED = b"SCRIPTURE-ALONE-BUNDLED-SEED-v1"
BUNDLED_SIGNING_SEED = b"scripture-alone-bundled-signing-v1"

# What `TranslationRights.unlimitedQuotation` is on the Swift side: Int.max, not a large number some
# future verse count could reach.
UNLIMITED_QUOTATION = 2 ** 63 - 1


def command_bundle(arguments: argparse.Namespace) -> None:
    """Packages one of the app's own translations into a sealed `.sabible` it ships instead of a store.

    This is not a demonstration bolted on beside the real thing: the American Standard Version is
    shipped this way and is the translation the app opens by default, so the encrypted path is the
    ordinary path, exercised by every reader on every launch. If it broke, the app would not start
    on a fresh install — which is a far stronger claim than a sample nobody opens.

    The text is public domain, so the package grants everything a public-domain text may do. What
    is being proved here is the mechanism, not a restriction; enforcement of a publisher's terms is
    proved separately, against packages built with terms that forbid things.
    """
    bibles = repo_root() / "ScriptureAlone/Resources/Bibles"
    store = bibles / f"{arguments.translation}.sqlite"
    if not store.exists():
        sys.exit(f"No bundled store at {store}.")

    # The same derivation the app performs: HKDF-SHA256 over the seed, keyed to the translation id.
    content_key = HKDF(algorithm=hashes.SHA256(), length=32,
                       salt=b"scripture-alone-content-key-v1",
                       info=arguments.translation.encode()).derive(BUNDLED_SEED)
    signing_seed = hashlib.sha256(BUNDLED_SIGNING_SEED).digest()

    meta, chapters = read_store(store)
    identity = {
        "id": arguments.translation,
        "name": meta.get("name", arguments.translation),
        "abbreviation": arguments.translation,
        "publisher": "Public domain",
        "copyright": meta.get("copyright", ""),
        "license": meta.get("license", "Public domain."),
    }
    # Everything a public-domain text may do. Sealing it protects nobody's rights and is not meant
    # to: the seal is here to prove the format carries a real translation, at real size, on every
    # launch. `maxQuotationVerses` is the format's "no limit" sentinel.
    policy = {"allowCopy": True, "allowShare": True, "allowVerseImages": True,
              "allowNotesExport": True, "allowExternalHandoff": True,
              "allowOfflineStorage": True, "maxQuotationVerses": UNLIMITED_QUOTATION}

    data = build_package(identity=identity, policy=policy, chapters=chapters,
                         content_key=content_key, signing_key=signing_seed)
    out_dir = repo_root() / "ScriptureAlone/Resources/Packages"
    out_dir.mkdir(parents=True, exist_ok=True)
    package = out_dir / f"{arguments.translation}.sabible"
    package.write_bytes(data)

    public_raw = ed25519.Ed25519PrivateKey.from_private_bytes(signing_seed) \
        .public_key().public_bytes_raw()
    (out_dir / "bundled-signing.pub").write_bytes(public_raw)

    header, _, _, _ = parse_header(data)
    lengths = [entry["length"] for entry in header["index"]["entries"]]
    print(f"{package} — {len(data):,} bytes, {len(chapters):,} chapters, "
          f"index {sum(lengths):,} bytes in {len(lengths)} buckets")
    print(f"publisher key id {publisher_key_id(public_raw)} — pinned in the app")


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0],
                                     formatter_class=argparse.RawDescriptionHelpFormatter,
                                     epilog="Keys must live outside this repository; the tool refuses otherwise.")
    commands = parser.add_subparsers(dest="command", required=True)

    keygen = commands.add_parser("keygen", help="generate a content key and a signing key")
    keygen.add_argument("--out-dir", required=True, help="a directory outside this repository")
    keygen.set_defaults(handler=command_keygen)

    build = commands.add_parser("build", help="build a package from a store")
    build.add_argument("--store", required=True)
    build.add_argument("--out", required=True)
    build.add_argument("--content-key", required=True)
    build.add_argument("--signing-key", required=True)
    build.add_argument("--publisher", required=True, help="the name shown to the reader")
    build.add_argument("--id", default=None, help="translation id (default: the store's)")
    build.add_argument("--name", default=None)
    build.add_argument("--abbreviation", default=None)
    build.add_argument("--copyright", default=None)
    build.add_argument("--license", default=None)
    build.add_argument("--index", action=argparse.BooleanOptionalAction, default=True,
                       help="build the encrypted search index (default: yes)")
    add_policy_arguments(build)
    build.set_defaults(handler=command_build)

    inspect = commands.add_parser("inspect", help="print a package's header — no key needed")
    inspect.add_argument("--package", required=True)
    inspect.add_argument("--publisher-key", default=None, help="verify the signature against this public key")
    inspect.set_defaults(handler=command_inspect)

    bundle = commands.add_parser(
        "bundle", help="seal one of the app's own translations into the resources it ships")
    bundle.add_argument("--translation", default="ASV")
    bundle.set_defaults(handler=command_bundle)

    demo = commands.add_parser("demo", help="package the bundled public-domain texts with a demonstration key")
    demo.add_argument("--out-dir", default=str(DEMO_DIRECTORY))
    demo.add_argument("--translations", nargs="+", default=["ASV", "BSB"])
    demo.set_defaults(handler=command_demo)

    arguments = parser.parse_args(argv)
    arguments.handler(arguments)


if __name__ == "__main__":
    main(sys.argv[1:])
