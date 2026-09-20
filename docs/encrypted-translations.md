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
4. **Searching does not undo any of it.** A packaged translation is fully searchable, phrases
   included, and the index is sealed exactly like the text: a search opens one or two of 256
   encrypted buckets and decrypts only the chapters its hits are in. There is no plaintext index, and
   a hashed-token index in the clear — which for a Bible could be aligned against a public one until
   every hash was identified — is precisely what we did not build.

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
  "packageID": "3AABF641-8B15-417A-A2F0-BEA8815FD949",
  "createdAt": "2026-09-20T06:43:35Z",
  "translation": { "id": "ASV", "name": "American Standard Version", "abbreviation": "ASV",
                   "publisher": "Public domain", "license": "Public domain",
                   "copyright": "American Standard Version (1901). Public domain. …" },
  "policy": { "allowCopy": true, "allowShare": true, "allowVerseImages": true,
              "allowNotesExport": true, "allowExternalHandoff": true,
              "allowOfflineStorage": true, "maxQuotationVerses": 9223372036854775807 },
  "crypto": { "cipher": "AES-256-GCM", "signature": "Ed25519", "aad": "sabible-chapter-v1",
              "keyID": "00b63e297da6049dce75d3e984b74b72",
              "publisherKeyID": "9f8568f961afc58850ba30326f20f8c2" },
  "index":  { "aad": "sabible-index-v1", "buckets": 256, "entries": [ … ] },
  "chapters": [ { "book": 1, "chapter": 1, "verses": 31, "offset": 0, "length": … }, … ]
}
```

This is the package the app ships, so every permission in it is `true` and the quotation cap is the
format's "no limit" sentinel: the American Standard Version is public domain, and sealing it must
not cost its readers anything. A licensed package is where those fields earn their keep — section 5
explains why the two claims are deliberately proved on different artefacts.

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

### The search index

A packaged translation is searchable, and the index is sealed exactly like the text is.

The obvious design is HMAC(word) → postings, left in the clear, and for this corpus it is wrong. The
attacker knows the file is a Bible. Token frequencies, and the positions those tokens sit at, can be
aligned against any public Bible until every hash is identified — and identifying the hashes
reconstructs this translation's wording, which is the entire asset. Searchable-encryption schemes
assume the plaintext distribution is unknown; ours is the most published text in history. So the
postings are encrypted too:

- **Postings.** Each token carries `(verse, word position)` pairs, so a phrase is an adjacency check
  on positions rather than a scan over text. Prefixes of three to ten characters get their own
  postings, verse-level and without positions, because the app's search prefix-matches only the last
  word of a query. Ten rather than six: a prefix longer than the longest indexed one has to be
  verified against the text, which means decrypting chapters that turn out not to match. Measured on
  the ASV, six to ten costs 189,134 more (prefix, verse) pairs out of 1.2 million — about 0.3 MB — and
  in exchange chapters decrypted equals chapters with hits.
- **Sharding.** A token's bucket is `HMAC(indexKey, token) mod 256`, and each bucket is sealed on its
  own with AES-256-GCM, bound to the package id, the translation id, the bucket number and the header
  hash — the same discipline as a chapter, with a different domain string, so a chapter blob can never
  be opened as a bucket or the reverse. `indexKey` is HKDF-SHA256 of the content key *and the
  translation id*: a publisher who packages three translations under one content key would otherwise
  get the same token → bucket mapping in all three, and bucket sizes could be correlated across files.
- **Why 256.** A whole Bible's postings are about 5.8 MB. At 256 buckets each holds about 170 tokens
  and 17 KB, so a search decrypts tens of kilobytes. Fewer buckets leak less per bucket and cost more
  per search — at 64 the median bucket is 76 KB; at 512 it is 8 KB — and 256 is the point where a
  search is cheap and a bucket still holds enough tokens that its size is not one word's frequency.
- **The parameters are signed.** Bucket count, prefix lengths, padding and tokeniser name sit in the
  header, inside the signature, so none of them can be altered under the app. The policy gains nothing
  from any of this: searching is reading, and reading is what a package is for.

What a search costs, asserted in the tests rather than asserted here: hash the query's tokens, open
only the buckets they name, intersect the postings, settle a phrase by comparing positions, and only
then decrypt the chapters the surviving verses are in — because a hit has to carry its text. Searching
the commonest word in scripture, "the", opens **1 bucket of 256** and decrypts **at most 30 chapters
of 1,189**, those being the chapters the first page of results is in. A search that matches nothing
decrypts no chapters at all. The reader counts what it opened (`accessCounts`), so this is measured,
not asserted.

Where the code is:

| Path | What |
|---|---|
| `ScriptureAloneCore/Sources/ScriptureAloneCore/Package/TranslationPackage.swift` | Reader: parse, verify, decrypt one chapter |
| `…/Package/PackagePolicy.swift` | The policy, and its mapping into `TranslationRights` |
| `…/Package/TranslationPackageWriter.swift` | Writer — the format in Swift, beside the Python tool |
| `…/Package/ChapterTextSource.swift` | The seam: a store and a package, read through one protocol |
| `…/TranslationRights.swift` | The single gate, for packaged and public-domain texts alike |
| `…/Package/PackageSearchIndex.swift` | The sealed search index: tokeniser, query, postings, buckets |
| `Tools/package_translation.py` | What a publisher runs, with their own keys, on their own machine |

One behaviour differs from a store, and it is small: the index holds no postings for one- and
two-character prefixes, so while a reader is typing, results narrow one keystroke later than they do
for a bundled text. Nothing wrong ever appears — the last word is matched whole until it reaches three
characters. Every other query, including phrases, returns exactly what the app's own FTS5 index
returns; the test suite runs a dozen queries against both and compares them verse for verse.

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

**What the sealed index still leaks, measured.** Someone without the key holds ciphertext, the header,
and the sizes of 256 buckets. Sizes are all that is left, and here is what they are worth. Each bucket
is the sum of about 170 posting lists under a keyed assignment the attacker cannot compute, so
recovering per-word frequencies from them is a 256-way subset-sum over 43,639 unknown items — not a
computation anyone finishes, and one whose answer would be "bucket 37 probably holds *the*", which
decrypts nothing. Sealed sizes are padded to 4 KiB, so exact byte counts are gone: the ASV's 256
buckets fall into 16 size classes. Two things do survive. The aggregate — total index size — tracks
the corpus's word count and vocabulary, which the header already implies by naming the translation and
listing 1,189 chapter lengths. And the tail: the largest bucket is 86% one token's postings, so its
size is effectively "how often the commonest word occurs", a figure published for every Bible in
print. Neither tells you a single word of the text.

Padding further is a poor trade, and we measured it rather than guessing: padding every bucket to the
largest costs 37.8 MB against a 5.8 MB index — 6.5× — and turns a 17 MB package into a 49 MB one. A 16
KiB quantum instead of 4 KiB costs 1.4 MB and cuts the size classes from 16 to 8; `padding` is a signed
header parameter, so a publisher who wants that can have it by changing one number. The one leak
padding does not fix is the dominant bucket, because a single token's postings cannot be padded
smaller; fixing that means splitting one token's postings across several buckets, which we will build
if a publisher asks, at the cost of extra bucket reads for the commonest words.

**A search also leaks its own pattern to anyone watching the device.** Which buckets a query opens is
visible to something observing file access on a device it controls — that is inherent to any encrypted
index that is not read in full, and reading it in full would mean decrypting 5.8 MB per keystroke. It
tells a watcher that two searches were for the same word, not what the word was.

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
| `aPackageFindsExactlyWhatTheStoreFinds` | Twelve queries, sealed index against FTS5, verse for verse |
| `aPhraseIsAnAdjacencyCheckOnPositions` | "Jesus wept" matches; "wept Jesus" does not |
| `aPrefixNarrowsWhileTyping` | Prefix search matches the store's, keystroke by keystroke |
| `aSearchOpensOnlyTheBucketsAndChaptersItNeeds` | One bucket of 256; chapters decrypted = chapters with hits |
| `thePlainestPossibleSearchStillDecryptsAlmostNothing` | "the": 1 bucket, ≤30 chapters of 1,189 |
| `aSearchWithNoMatchesDecryptsNoChapters` | No hits, no plaintext |
| `anIndexBucketTransplantedFromAnotherPackageFailsItsSeal` | A bucket is bound to its package, like a chapter |
| `editingTheIndexParametersFailsTheSignature` | The tokeniser and bucket count are inside the signature |
| `aPackageWithoutAnIndexRefusesToSearch` | "Cannot be searched" is not "no matches" |
| `theToolsDemoIndexIsSearchedByTheApp` | The Python tool's index, searched by the Swift reader |
| `shippedPackageOpensAndReads` | The default translation, opened from the bytes the app ships |
| `shippedPackageSearches` | Phrase and prefix, through the sealed index, on that same package |
| `wrongContentKeyIsRefused` | A wrong key is refused at open, not at the first chapter |
| `unpinnedPublisherKeyIsRefused` | A signature from a key the app does not pin |
| `shippedTranslationIsUnrestricted` | A public-domain text sealed costs its readers nothing |

### The translation that ships sealed

The commands above prove the format on a desk. The app goes further: it does not ship a sealed
*sample* beside its real translations — it ships one of its real translations sealed.

`ScriptureAlone/Resources/Packages/ASV.sabible` is the American Standard Version, packaged by the
tool above, signed by a key the app pins, and it is the translation the app opens by default. There
is no `ASV.sqlite` in the app to fall back on. Every reader, on every launch, derives the content
key, unwraps it from the Secure Enclave, verifies an Ed25519 signature over the header, and reads
chapters decrypted one at a time out of an authenticated package. Searching it searches a sealed
index. If any of that broke, the app would not open on a fresh install.

That is a stronger claim than a demonstration can make, and it is the reason to prefer it. A sample
translation nobody selects proves that the code compiles. A default translation proves the format
survives contact with real use — real size, every chapter, every search, on every device the app
runs on, for as long as the app is in the store.

The terms in that package permit everything, and deliberately so. The American Standard Version is
public domain; sealing it protects nobody's rights and is not meant to. A reader must lose nothing
by the choice — no disabled buttons, no quotation cap — or the mechanism would be buying its proof
with someone else's inconvenience. **Enforcement is proved separately**, in `PackagePolicyTests`,
against packages built with terms that forbid things: a policy that refuses notes export, refuses
hand-off to other apps and caps quotation at 25 verses, and an app that obeys each refusal at the
control the reader actually touches. The two claims are kept apart on purpose — one says the format
carries a real Bible, the other says the app honours real terms — because proving them with the
same artefact would let a weakness in either hide behind the other.

The seed and the signing key for this package are published, in `Tools/package_translation.py` and
in `SealedTranslations.swift`. That is deliberate: they protect a public-domain text, so keeping
them secret would be theatre, and theatre is what this document exists to avoid. A licensed package
uses a key its publisher generates and holds, delivered as described in section 4.

`ShippedPackageTests` asserts against that exact artefact rather than a package built for the test:
it opens the shipped bytes with the published seed and the pinned key, reads Psalm 23 back as poetry
with its Hebrew superscription intact, finds John 3:16 by phrase and Psalm 23:1 by prefix through
the sealed index, refuses a wrong content key at open, refuses an unpinned publisher key outright,
and checks that a reader of it is bound by nothing. If the Python tool and the Swift reader ever
drift apart, that suite fails before a reader ever sees it.

That means a publisher can watch the whole mechanism work, end to end, without granting anything, and
can run the packaging tool against their own text on their own machine before deciding.

---

## 6. What a reviewer should look at, and what is not built yet

For an engineer checking this rather than reading about it, four files carry the whole claim:
`TranslationPackage.swift` — where the part worth reading is the sequence of checks performed when a
package is opened, and the two private functions that open a sealed box, one per chapter and one per
index bucket — together with `PackagePolicy.swift`, `PackageSearchIndex.swift`,
`TranslationRights.swift`, and `Tools/package_translation.py`. The
format is implemented twice, in different languages, so you can build packages with the tool and
confirm the app reads them; a divergence between the two shows up as a failing test rather than as a
package that will not open on your desk.

Two things this document previously listed as unbuilt are now built, and are described above rather
than promised: key delivery by the shipped-seed route (section 4), and the reader's own screens,
which open a package through the same protocol they open a SQLite store through
(`ChapterTextSource`). The translation list, the reader, selection, quotation, listening, sharing
and search all run against a sealed package today — and against the default translation, so they
run that way for everyone.

What is deliberately still open, so nobody discovers it later:

- **The attested key-delivery route is designed but not built.** The default — a seed injected at
  build time, derived with HKDF, sealed to the Secure Enclave — is implemented and shipping. The
  second option in section 4, where a Cloudflare Worker releases a key only to a build that passes
  App Attest, is specified but not written, because it is only worth building if your terms require
  it. It is a few hundred lines and no servers to run, and we will build it if you ask.
- **No watermarking.** We can add a per-installation mark to a shared quotation if you want one; it is
  a deterrent and an audit trail, not a protection, and we would describe it to you as such.
- **One key, one publisher, so far.** The vault is namespaced per translation and the derivation takes
  the translation's identifier, so several publishers' keys coexist by construction; nothing has yet
  had to hold two at once, and we would rather say that than imply it has been exercised.

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
