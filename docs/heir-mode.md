# Heir mode: Legacy Bibles

> "An heir mode that will transfer a copy of all the interactions done as a read-only
> snapshot family members can access after someone passes, so it will feel like a digital
> representation of 'Dad's' Bible."

Scripture Alone has no server, no account and no dead-man's switch, and heir mode doesn't add
any. Instead, the owner makes a **keepsake**: a file holding a copy of their highlights and
notes. They give it to family however they like (AirDrop, Messages, a USB drive, or with
their will). Family open it in Scripture Alone and read the owner's Bible the way they
marked it.

## What's built

| Piece | Where |
|---|---|
| File format, ZIP reader/writer, encryption, Markdown/text export (pure, unit-tested) | `ScriptureAloneCore/Sources/ScriptureAloneCore/Keepsake/` |
| Keepsake library, reading session, import sheet, banner, read-only notes, settings | `ScriptureAlone/Legacy/` |
| Notes export sheet (PDF, Markdown, Markdown folder, plain text) and PDF typesetter | `ScriptureAlone/Export/` |

**Creating** — Appearance → Legacy & Export → Create a Keepsake (also reachable from the
Notes panel's Export menu). The owner sets the name family knows them by ("Dad"), an optional
dedication, and the translation it opens in. Passphrase protection is optional and off by
default.

**Opening** — tapping a `.scripturelegacy` file in Messages, Mail, Files or Finder opens the
app (the type is exported in `Info.plist`), or use "Open a Keepsake File…" in Legacy &
Export. The app shows whose it is and the dedication, asks for the passphrase if there is
one, and adds it to the library.

**Reading** — "Reading Dad's Bible" swaps the reader's own marks for the keepsake's: their
highlights in their colors with a thin pen-line underneath, their notes as outlined bubbles
beside the verses, and a banner with the name, the dedication and "Read-only keepsake". The
Notes panel shows their notes read-only (text is selectable; Copy Note copies it) and can
export them as a PDF to print. Verse taps don't select while reading, so nothing can be
highlighted or annotated. "My Bible" returns to the reader's own marks and translation.

**Storage** — received keepsakes live as files in `Application Support/Keepsakes/`, one per
Bible (named by `bibleID`), stored unprotected (the passphrase guards the file in transit) and
with iOS data protection. They are **not** in SwiftData, so they never sync into or mix with
the reader's own highlights and notes. They are included in device backups. Several keepsakes
can be kept; removing one asks first.

## File format (version 1)

A `.scripturelegacy` file is an ordinary ZIP archive (entries stored uncompressed; readers
also accept DEFLATE). UTI `com.blainemiller.scripturealone.legacy`, conforming to
`public.data`; MIME `application/vnd.scripturealone.legacy+zip`.

```
manifest.json     who, when, which translation, format version
highlights.json   {"highlights": [{"verse": 43003016, "color": "yellow", "createdAt": "…", "reference": "John 3:16"}]}
notes.json        {"notes": [{"id", "title", "body", "passages": [{"start", "end", "reference"}], "createdAt", "updatedAt", "origin"}]}
README.txt        a plain-language explanation for someone who finds the file without the app
```

`manifest.json`:

| Key | Meaning |
|---|---|
| `format` | Always `com.blainemiller.scripturealone.legacy` (required) |
| `formatVersion` | Version of the writer (1) |
| `minimumReaderVersion` | Oldest reader that can open it (1). Bumped only for changes old readers would get wrong |
| `exportID` | Unique per export |
| `bibleID` | Stable per owner (kept in their iCloud key-value store), so a newer keepsake replaces the older one |
| `createdAt` | When the keepsake was made |
| `generator` | "Scripture Alone 1.0.0" |
| `ownerName`, `dedication`, `preferredTranslation` | Optional |
| `dateRange` `{start, end}`, `counts` `{highlights, notes}` | Summary |
| `encryption`, `passphraseHint` | Only in the outer manifest of a protected keepsake |

Verse numbers are `book × 1,000,000 + chapter × 1,000 + verse`, books numbered Genesis = 1 to
Revelation = 66. Dates are ISO 8601 with milliseconds. JSON is pretty-printed with sorted keys.
`reference` fields are for people reading the JSON and are ignored when reading.

**Compatibility rules.** Readers ignore keys and archive entries they don't know, so a newer
writer can add fields without breaking older apps. Every field except `format` may be missing.
A reader refuses a file only when `minimumReaderVersion` is newer than it supports, and then
says to update the app. Highlights pointing at a verse that doesn't exist are dropped.

### Passphrase protection

When a passphrase is set, the archive holds `manifest.json` (the outer manifest: format
fields, `encryption`, optional `passphraseHint`, and no name or dedication), `payload.sealed`
and `README.txt`.

- **KDF:** PBKDF2-HMAC-SHA256 (CommonCrypto) over the passphrase normalized to Unicode NFC
  and encoded as UTF-8, with a random 16-byte salt and 600,000 iterations (OWASP's 2023
  figure for PBKDF2-SHA256), producing a 256-bit key. The salt and iteration count are in
  `encryption`.
- **Cipher:** AES-256-GCM (CryptoKit), random 12-byte nonce. `payload.sealed` is
  nonce ‖ ciphertext ‖ 16-byte tag.
- **Associated data:** the exact bytes of the outer `manifest.json`, so the salt, iteration
  count or hint can't be changed without opening failing.
- **Plaintext:** a ZIP holding the full `manifest.json`, `highlights.json` and `notes.json`.

A wrong passphrase fails the GCM tag check and the app says so plainly; there is no recovery,
which the create screen explains. PBKDF2 was chosen over a memory-hard KDF (scrypt, Argon2)
because it ships in every Apple OS and has an implementation in every language — the point of
a keepsake is that it can still be opened in fifty years. Encryption uses only what the OS
provides, so `ITSAppUsesNonExemptEncryption` stays `NO`.

Tests: `ScriptureAloneCore/Tests/ScriptureAloneCoreTests/KeepsakeTests.swift` (round trip,
version fields, unknown fields ignored, missing fields, encryption round trip with NFC
normalization, wrong passphrase, tampered manifest, non-keepsake files, DEFLATE entries, and
that `/usr/bin/unzip` accepts the archive).

## Notes export

From the Notes panel's Export menu (all notes, or only the ones the current search/scope
shows), a note's More menu (just that note), Legacy & Export, and a keepsake's notes panel:

- **PDF** — typeset with Core Text (identical on iPhone, iPad and Mac): title block, then each
  note with its passages quoted in the chosen translation, the note's text, and when it was
  written; page numbers in the footer; Letter in the US, A4 elsewhere.
- **Markdown** — one file, or a folder with a file per note (`2026-09-18 Romans 8.md`).
- **Plain text.**

Verse text is optional. Very long passages (over 20 verses — a note on a whole chapter) are
shortened to their first five verses with an ellipsis. Files can be shared or saved to Files.

## Live family sharing (research)

The ideal would be live, read-only sharing: family see the owner's notes as they're written,
through CloudKit, with no server of ours.

**Finding: SwiftData can't do it.** In the iOS 27 / macOS 27 SDK (Xcode 27.0, checked in
`SwiftData.swiftmodule/*.swiftinterface`), `ModelConfiguration.CloudKitDatabase` still has only
`.automatic`, `.none` and `.private(_:)`. There is no shared-database scope, no `CKShare`
integration and no participant API. Our deployment target is iOS/macOS 26, whose SDK has the
same three options. So the app's SwiftData store can only ever sync to the owner's private
database.

**Options:**

1. **A second, Core Data store for sharing.** `NSPersistentCloudKitContainer` supports
   `CKShare` (iOS 15+): a store with `databaseScope = .shared` beside a private one,
   `share(_:to:)`, and `UICloudSharingController` / `NSSharingService` for invitations. We'd
   mirror highlights and notes from SwiftData into a Core Data "shared Bible" zone and share
   that zone read-only with named family members. It works without a server, but it's a second
   persistence stack with its own model, a mirror that must stay in step with SwiftData, share
   acceptance (`CKShare.Metadata` in the scene delegate) and permissions UI. Opening a SwiftData
   store's underlying Core Data store directly to share it is unsupported and fragile.
2. **Raw CloudKit.** Write a `CKRecordZone` in the private database, share the zone with
   `CKShare(recordZoneID:)` and read it from recipients' shared databases. Full control, but we'd
   own sync, conflict handling and change tokens — the work SwiftData exists to spare us.
3. **Periodic keepsake refresh (built).** Keepsakes carry a stable `bibleID`; making a new one
   and sending it replaces the older copy on a family member's device. No accounts, no invites,
   nothing to break when an iCloud account closes after someone dies — which is the moment
   heir mode is for. A CloudKit share would likely lapse when the owner's Apple Account is
   closed; a file in a drawer doesn't.

**Recommendation:** keep keepsakes as the heir-mode mechanism, and nudge owners to refresh
them (for example, a gentle reminder in Legacy & Export when their notes have grown since the
last keepsake). Revisit live sharing if SwiftData gains a shared `CloudKitDatabase` option;
until then, option 1 is the only clean path, and it isn't worth a second persistence stack
for a feature whose most important moment happens after the account may be gone. We haven't
prototyped it.
