# Protecting a licensed translation in an open-source Bible app

**A proposal to publishers, with working code**

Scripture Alone is a free Bible app for iPhone, iPad, Mac and Apple Watch. It has no ads, no
subscriptions, no accounts and no analytics, and its source is public under the AGPL. It ships the
American Standard Version, the Berean Standard Bible and the King James Version, all public domain.

We have asked several publishers to licence a modern translation. The reasonable question that
follows is: *your source code is public — how can our text be safe in it?*

This paper answers that question with a format, an implementation and a set of tests, not with
assurances. It also states plainly what the design cannot do, because a proposal that overclaims is
worth less than one a publisher's own engineers can check.

---

## 1. The claim, stated precisely

A licensed translation ships as a **signed, encrypted package**. The app can read it. A person
holding the file cannot read it without the key. The publisher decides — in data the app enforces —
what may be done with the text once it is open: whether it may be copied, shared as an image,
exported into a file, handed to another app, or kept offline at all, and how many verses may leave
in a quotation.

Three properties matter to a publisher:

1. **The plaintext never exists as a file.** Chapters are decrypted one at a time, in memory, on
   demand. A whole Bible in the clear never lands on disk, and the decryption interface is shaped so
   that it cannot.
2. **The terms travel with the text.** The permission policy sits in a header signed by the
   publisher's own key. Editing the policy invalidates the signature and the package stops opening.
   Terms are not a PDF in a drawer; they are enforced by the code that draws the page.
3. **The publisher holds the keys.** The packaging tool runs on the publisher's machine, with the
   publisher's content key and signing key. We never receive the plaintext and never hold the key
   that decrypts it.

---

## 2. Why open source is the stronger position, not the weaker one

The instinct that public source makes text less safe is understandable, and it is the opposite of
how cryptography is assessed.

**Kerckhoffs's principle**, the foundation of modern cryptography since 1883, holds that a system
must remain secure even when everything about it except the key is public knowledge. Every cipher
any publisher already relies on — the TLS protecting their own storefront, the disk encryption on
their staff's laptops, Apple's own Secure Enclave — is public in exactly this sense. Their security
rests on the key, never on the design being unknown. A scheme that needs its design kept quiet is
one that has not been examined.

The practical consequence for this proposal:

- **A closed-source Bible app is in the identical position.** Any app that reads a licensed
  translation offline must place a key on the reader's device. That is true of every major Bible
  app on the App Store. The difference between them and us is not whether a key is on the device —
  it is whether an outside party can confirm what the app does with it.
- **Our source being public means the enforcement is verifiable.** A publisher's engineer can read
  the code that refuses an export, confirm that no analytics exist, and confirm the plaintext is
  never written to disk. With a closed app that confirmation is impossible, and the publisher is
  trusting a promise.
- **A fork gets the code, not the text.** The repository contains no content key and no signing key.
  Anyone may clone Scripture Alone; nobody who does can decrypt a licensed package, because the
  secret was never in the thing they cloned.
- **A fork cannot be handed a key it was never given.** The repository holds no content key and no
  signing key, and the packaging tool refuses to run against a key inside the working tree. Whether a
  fork can be refused a key *at delivery* depends on which delivery option you choose — section 4
  sets both out honestly, including the one that needs no infrastructure and the one that is
  stronger.

The security of this design rests on the key and the signature. That is the correct place for it to
rest.

---

## 3. The format

One file, `.sabible`:

```
offset  0   magic            "SABIBLE\0"                      8 bytes
offset  8   version          u16, big-endian                   2 bytes
offset 10   header length    u32, big-endian                   4 bytes
offset 14   header           JSON, plaintext          header length bytes
            signature length u16, big-endian                   2 bytes
            signature        Ed25519 over the header bytes     64 bytes
            body             per-chapter AEAD-sealed blobs    to end of file
```

**The header is plaintext on purpose.** Anyone may see what a package claims to be, who signed it
and under what terms, without holding any key. Secrecy of the terms was never the goal; integrity of
them is. This is a real package's header, printed with no key at all by
`Tools/package_translation.py inspect`:

```json
{
  "format": 1,
  "packageID": "AB690BA9-3BB0-426D-8AE0-EAD03990BDA1",
  "createdAt": "2026-09-20T03:50:47Z",
  "translation": { "id": "ASV", "name": "American Standard Version", "abbreviation": "ASV",
                   "publisher": "…", "copyright": "…", "license": "…" },
  "policy": { "allowCopy": true, "allowShare": true, "allowVerseImages": true,
              "allowNotesExport": true, "allowExternalHandoff": false,
              "allowOfflineStorage": true, "maxQuotationVerses": 500 },
  "crypto": { "cipher": "AES-256-GCM", "signature": "Ed25519", "aad": "sabible-chapter-v1",
              "keyID": "bff08c0f7b47a1736a8069cad22c3340",
              "publisherKeyID": "fc66a5c61dff15908baf587144d8b5c9" },
  "chapters": [ { "book": 1, "chapter": 1, "verses": 31, "offset": 0, "length": 10318 },
                { "book": 1, "chapter": 2, "verses": 25, "offset": 10318, "length": 7671 }, … ]
}
```

(The two key identifiers and the package id are from one run of the demonstration; every key and
every build produces its own.)

`keyID` and `publisherKeyID` are the first 16 bytes of SHA-256 over a domain string and the key.
They name a key without revealing it, so the app can say "wrong key" rather than "damaged file", and
so a package states which pinned signing key must verify it.

**Signature** — Ed25519 over the exact header bytes as they sit in the file. The publisher holds the
private key; the app pins public keys by identifier and derives those identifiers itself rather than
believing the file. A header altered by one byte fails verification and the package does not open. A
header re-signed by a key the app does not pin is refused before its terms are read.

**Body** — each chapter is sealed separately with AES-256-GCM, the same primitive already shipping in
the app for its keepsake files, in CryptoKit's combined form (12-byte nonce ‖ ciphertext ‖ 16-byte
tag). Each chapter's authenticated associated data is:

```
sabible-chapter-v1 \n <packageID> \n <translation id> \n <book×1000+chapter> \n <sha256(header) hex>
```

Four attacks therefore fail loudly instead of silently:

| Attack | Result |
|---|---|
| Move a chapter into a different package | AEAD authentication fails — the package id is bound in |
| Pass one chapter off as another | AEAD fails — the chapter reference is bound in |
| Replay an old chapter after the policy tightened | AEAD fails — the header's hash is bound in |
| Strip or edit the policy | Signature fails |

There is one subtlety both implementations must get right, and it is worth stating because it is the
sort of thing a reviewer should check: each chapter is bound to a hash of the header, and the header
carries each chapter's offset and length — so neither can be written first. It resolves because a
sealed blob is always its plaintext plus 28 bytes: the writer computes every offset arithmetically,
writes and hashes the header, and only then seals. If a sealed blob ever came out a different size,
the writer refuses rather than emit a package whose index is a lie.

**Policy** — expressed as data, enforced by the same code path that already governs the app's
public-domain texts:

```
allowCopy            allowShare           allowVerseImages
allowNotesExport     allowExternalHandoff allowOfflineStorage
maxQuotationVerses   expires
```

A publisher who permits reading and quotation but no export, no image sharing and no hand-off to
other software expresses that in the package, and the app obeys it. A publisher who wants the licence
to lapse on a date sets `expires`, and the package stops opening. Two details are deliberate: a
permission **missing** from a policy reads as *no*, so a policy written by an older tool or truncated
in transit can never grant something by omission; and an `expires` the app cannot parse **refuses the
package**, rather than quietly becoming "no expiry" because of a typo.

The policy becomes a `TranslationRights` value — the same type the bundled ASV gets from its licence
line. Everything downstream (the share sheet, the verse image, the notes export, the Mi Speaks
hand-off) asks that one value, and does not know which kind of translation it is looking at.

Where the code is:

| Path | What |
|---|---|
| `ScriptureAloneCore/Sources/ScriptureAloneCore/Package/TranslationPackage.swift` | Reader: parse, verify, decrypt one chapter |
| `…/Package/PackagePolicy.swift` | The policy, and its mapping into `TranslationRights` |
| `…/Package/TranslationPackageWriter.swift` | Writer — the format in Swift, beside the Python tool |
| `…/Package/ChapterTextSource.swift` | The seam: a store and a package, read through one protocol |
| `…/TranslationRights.swift` | The single gate, for packaged and public-domain texts alike |
| `Tools/package_translation.py` | What a publisher runs, with their own keys, on their own machine |

One capability is knowingly given up: **a packaged translation cannot be searched**. The app's
full-text search is an FTS5 index over plaintext; building one for a package would mean decrypting all
of it, which is the thing the format exists to prevent. A packaged translation is searchable only over
what the reader has already opened, or not at all. We would rather tell you that than quietly keep a
searchable copy.

---

## 4. What this does not do

A key that reaches a device can, in principle, be recovered by a determined owner of that device. No
client-side scheme changes this — not ours, and not any closed-source app's. Anyone claiming
otherwise is selling something.

It is worth being exact about where the line falls.

**Holds against anyone without the key.** The file is AES-256-GCM. Someone who obtains a package — off
a backup, out of a support ticket, from a stolen laptop — has ciphertext and a header. No amount of
reading our source code helps them: the repository contains no content key and no signing key, and
never will. This part is not a mitigation; it is arithmetic.

**Holds against tampering, with or without the key.** The terms cannot be loosened, a chapter cannot
be moved or relabelled, and an old chapter cannot be replayed under new terms. An attacker who holds
the content key still cannot make *our app* ignore your policy, because the policy is signed by you
and each chapter is bound to it.

**Does not hold against the owner of a device that has the key.** To get plaintext, an attacker must:

1. Obtain the content key from the device. That means defeating the platform's key storage — the
   keychain, and on hardware that supports it a Secure Enclave-protected item — on a device they
   control, or attaching a debugger to the running app and reading the key out of memory. On a
   jailbroken device this is work, not a wall.
2. Drive the reader once per chapter, or reimplement it. 1,189 calls, each one a legitimate
   operation, is a script — the format does not stop bulk extraction by someone holding the key. It
   only stops it happening *without* the key, and stops it happening *by accident* through the app's
   own features.

The format makes step 1 the whole cost of the attack, which is the intent: there is no plaintext file
to copy, no cache to scrape, no export path to abuse, and nothing in a backup. It does not make step 2
expensive, and we will not claim it does.

**The key has to get to the device, and that is a separate problem that cryptography does not solve.**
The reader takes the content key as a parameter and has no opinion about where it came from, so the
delivery decision is yours to make with us rather than ours to present. There are two honest options,
and the difference between them is operational, not cryptographic.

**A key shipped in the app.** Recoverable: the App Store's binary encryption is removed at runtime, a
jailbroken device can dump the decrypted binary, and the key is then in hand. Apple provides no
countermeasure to this, and it is worth being exact about why the usual candidates do not apply. The
keychain and the Secure Enclave protect secrets *generated on the device*; a key that arrives inside
the binary has already been exposed before either can hold it. App Attest proves an app's integrity
*to a server*, so with no server there is nobody for it to convince. Obfuscation raises effort and
changes nothing else.

We state this plainly because it is also true of every closed-source app that reads your text
offline. They ship keys too. The only difference is that their readers cannot see how, and by
Kerckhoffs's principle that difference is not security — it is the absence of review.

**A key fetched once and kept in the keychain.** Stronger, because the fetch can be gated: App Attest
lets a server refuse any client that is not a genuine build of this app on genuine Apple hardware,
and a fork is a different app identity and can simply be refused. That is the same anchor a
closed-source app has and no better, and it raises the cost of step 1 without removing it. It costs
one small endpoint — a Cloudflare Worker is sufficient, with no servers to run — and we will build it
if you want it.

Our default is the first, because this app has no backend and we would rather not acquire one. If
your terms need the second, say so and it exists. What does *not* change between them is everything
in section 3: your signature, your policy, and the binding of every chapter to both.

**What we do with a shipped key, given that it can be recovered.** The seed is injected at build
time by Xcode Cloud from a secret that is not in the repository, and it is never used directly: the
content key is derived from it with HKDF, so the bytes in the binary are not the key and one seed
can serve several publishers without any of them sharing a key. On first launch the derived key is
sealed to a Secure Enclave key generated on that device, marked "this device only", which never
syncs and cannot leave the chip; what sits in the keychain afterwards is ciphertext.

This does not hide the seed from someone disassembling the binary, and we do not claim it does. It
closes every other route, which are the ones that actually happen: the key is never written to disk
in the clear, a copied keychain or a device backup or a file-system dump yields ciphertext nobody
can open, and a key recovered on one device is of no use on another. It raises the floor. It is the
same floor a closed-source app stands on, and we would rather describe it accurately than imply a
ceiling that does not exist.

**And the analogue hole is always open.** Any reader can screenshot a page, and a patient one can
script scrolling and run OCR. That is true of every Bible app, every e-reader, and every printed
book with a photocopier next to it.

The honest summary for a publisher is this: **this design gives you the same protection a
closed-source app gives you, plus the ability to verify it, plus enforcement of your specific terms in
code rather than in correspondence.** It does not give you protection against a determined adversary,
because nothing on a general-purpose computer does.

---

## 5. The demonstration

The mechanism is proven with the public-domain texts the app already ships. The American Standard
Version and the Berean Standard Bible are packaged with a demonstration key and read by the app
through exactly the path a licensed translation would take — signature verified, chapters decrypted on
demand, policy enforced. Three commands, no licence required:

```bash
python3 Tools/package_translation.py demo
# → keys and packages in ~/.scripture-alone-demo (never in the repository)
# → ASV.sabible 10,896,180 bytes, 1,189 chapters; BSB.sabible 11,405,401 bytes, 1,189 chapters

python3 Tools/package_translation.py inspect --package ~/.scripture-alone-demo/ASV.sabible \
    --publisher-key ~/.scripture-alone-demo/signing.pub
# → the header above, and "signature: verifies"

cd ScriptureAloneCore && swift test
# → the app's reader opens those packages and reads them back verse for verse
```

The packaging tool refuses to run if either key is inside the repository working tree. That refusal is
part of the demonstration: it is not possible to follow our instructions and end up with a key in a
public git repository by accident.

The test suite states the security properties as executable assertions. By name, in
`ScriptureAloneCore/Tests/ScriptureAloneCoreTests/`:

| Test | Claim |
|---|---|
| `aPackageOpensAndReadsAChapter` | Round trip: layout, verses, red letters, selections |
| `theHeaderIsReadableWithoutAnyKey` | The terms are public; the text in the body is not |
| `aWrongContentKeyIsRefused` | A wrong key gets nothing |
| `aWrongContentKeyIsRefusedByTheCipherNotOnlyByTheKeyIdentifier` | …and it is the cipher refusing, not a convenience check |
| `aSingleFlippedByteInTheHeaderFailsTheSignature` | One byte, including one inside a value that keeps the JSON valid |
| `anEditedPolicyFailsTheSignature` | Loosened terms, both as a same-length edit and as a re-encoded header |
| `anEditedPolicyResignedWithAnotherKeyIsRefusedByPinning` | An attacker's signature is refused; and even if their key were pinned, the chapters still will not open |
| `aChapterTransplantedFromAnotherPackageFailsItsSeal` | A chapter moved between packages |
| `aChapterReplayedAcrossAPolicyChangeFailsItsSeal` | The same chapter replayed after the terms tightened |
| `everyFieldBoundIntoAChapterIsNecessary` | Each of the four bound fields, one at a time |
| `aPackagePastItsExpiryRefusesToOpen` | An expired licence, and an already-open translation once the date passes |
| `anUnreadableExpiryRefusesToOpen` | A date the app cannot parse stops the package |
| `aPolicyThatForbidsExportIsRefusedByTheAppsOwnGate` | The app's own gating code refuses what the policy forbids |
| `packagedAndPublicDomainTextsAreJudgedByOneCodePath` | One permission system, not two |
| `aMissingFieldInAPolicyGrantsNothing` | A policy fails closed |
| `noApiHandsOutMoreThanASelection` | No call returns more than a selection |
| `chaptersAreDecryptedOneAtATimeFromTheirOwnByteRanges` | A package with one corrupt chapter still opens and still reads the rest |
| `thereIsExactlyOneDecryptingEntryPoint` | Asserted against the source: one sealed box is opened, in one place, per chapter |
| `aBundledTranslationPackagesAndReadsBackIdentically` | A whole Bible, packaged and read back verse for verse |
| `aPackageAndAStoreAreReadThroughOneInterface` | A package and a SQLite store, read through one protocol |
| `theToolsDemoPackagesOpenInTheApp` | Packages built by the Python tool, opened by the Swift reader |
| `theToolsDemoPackagesRefuseAWrongKey` | …and refusing a wrong key, against the real artefact |

That means a publisher can watch the whole mechanism work, end to end, without granting anything, and
can run the packaging tool against their own text on their own machine before deciding.

---

## 6. What a reviewer should look at, and what is not built yet

For an engineer checking this rather than reading about it, four files carry the whole claim:
`TranslationPackage.swift` — where the part worth reading is the sequence of checks performed when a
package is opened, and the one private function that opens a sealed box — together with
`PackagePolicy.swift`, `TranslationRights.swift`, and `Tools/package_translation.py`. The
format is implemented twice, in different languages, so you can build packages with the tool and
confirm the app reads them; a divergence between the two shows up as a failing test rather than as a
package that will not open on your desk.

What is deliberately still open, so nobody discovers it later:

- **Key delivery is not implemented.** The reader takes a key; nothing yet decides how a real device
  gets one. See section 4 — this is the part we want to design with you, and it is the part that
  actually bounds the security of the whole scheme.
- **The reader's own screens are not yet wired to packages.** The package reader and the SQLite store
  satisfy one protocol and are tested through it, but the app's translation list and reader view still
  open stores only. That is plumbing, not design.
- **No search over packaged text**, as described above.
- **No watermarking.** We can add a per-installation mark to a shared quotation if you want one; it is
  a deterrent and an audit trail, not a protection, and we would describe it to you as such.

---

## 7. What we are asking for

A licence to include a translation in a free, non-commercial, ad-free app, distributed through the
App Store, with the text protected as described and the publisher's terms enforced in code.

We are not asking for the plaintext. The packaging tool runs on your machine, with your keys. What
you send us is a package we cannot decrypt without the key you control, and which stops working if
we alter your terms.

---

*Scripture Alone is free and will remain free. It exists to put scripture in front of people without
anything in the way — no cost, no account, no tracking, and nothing between the reader and the text.*
