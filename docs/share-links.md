# Share links

Scripture Alone makes verse images on device. A **share link** carries the verse itself, so any
browser can rebuild the same card from the link alone: no server stores or even sees the passage.

This page is the contract between the app (`ScriptureAloneCore/Sources/ScriptureAloneCore/ShareLinkPayload.swift`,
tested in `ShareLinkPayloadTests.swift`) and the website (`wemiller.com/apps/scripture-alone/`).

## URL

```
https://wemiller.com/apps/scripture-alone/#s=<payload>
```

The payload lives in the **fragment** (`#…`), which browsers never send to the server, so the
site's logs never contain the verse. The page must read it from `location.hash` in JavaScript.
Other fragment parameters may appear later, separated by `&` (`#s=…&x=…`); read `s` and ignore
the rest.

## Payload

`payload` = **base64url, no padding** (RFC 4648 §5: `+`→`-`, `/`→`_`, strip `=`) of **UTF-8 JSON**:

```json
{
  "v": 1,
  "ref": "John 3:16–17",
  "k": "43003016-43003017",
  "tr": "ASV",
  "t": "16 For God so loved the world, … 17 For God sent not the Son …",
  "red": [[3, 134], [141, 107]],
  "tp": "parchment",
  "f": "serif",
  "a": "square"
}
```

| Key | Required | Meaning |
|---|---|---|
| `v` | yes | Version. Always `1`. Anything else: show "This link needs a newer version of the page." |
| `ref` | yes | Human reference, exactly as displayed ("John 3:16–17", en dash). Several ranges are joined with `", "`. |
| `k` | yes | Verse keys `book*1_000_000 + chapter*1_000 + verse`. A range is `start-end`; several ranges are joined with `,` (`"45008001-45008004,45008028-45008028"`); a lone key (`"43003016"`) is one verse. Books are canonical ordinals 1–66 (Genesis = 1, John = 43). |
| `tr` | yes | Translation abbreviation (`ASV`, `BSB`, `KJV`). Shown after the reference. |
| `t` | yes | The verse text. One verse: the text as-is. More than one: each verse prefixed by its number and a space, verses joined by a single space — `"16 For God… 17 For God…"`. A verse that starts a new chapter mid-passage is numbered `chapter:verse` (`"31 It was very good. 2:1 Thus the heavens…"`). |
| `red` | no | Words of Christ as `[start, length]` pairs of **UTF-16 code-unit offsets into `t`** (the same units as JavaScript string indices). Omitted when there are none or the sender turned red letters off. Ignore pairs that are negative, zero-length or out of bounds; clamp lengths that run past the end. |
| `tp` | no | Template name (below). Unknown or missing → `parchment`. |
| `f` | no | Typeface token (below). Unknown or missing → `serif`. |
| `a` | no | Aspect: `square` (1:1), `story` (9:16), `wide` (16:9). Unknown or missing → `square`. |

JSON is written with sorted keys and raw UTF-8 (curly quotes, em dashes and Hebrew letters such
as Psalm 119's `א` are not `\u`-escaped). Decoders must not depend on key order.

**Limits.** The app only makes a link when `t` is at most **1,500 characters**; longer passages
are offered as an image or plain text instead. Decoders should refuse `t` over 6,000 characters.
Links are only offered for public-domain translations for now (see *Licensed translations*).

### Decoding in the browser

```js
function readShare() {
  const params = new URLSearchParams(location.hash.slice(1));
  const s = params.get("s");
  if (!s) return null;
  const b64 = s.replace(/-/g, "+").replace(/_/g, "/").padEnd(Math.ceil(s.length / 4) * 4, "=");
  const bytes = Uint8Array.from(atob(b64), c => c.charCodeAt(0));
  const data = JSON.parse(new TextDecoder().decode(bytes));
  if (data.v !== 1) throw new Error("unsupported");
  return data;
}
```

(`URLSearchParams` is safe here: base64url never contains `+`, `&` or `=`.)

Set text with `textContent` / DOM nodes only, never `innerHTML` — the payload is untrusted input.

**Styling verse numbers.** `t` carries numbers as plain text. To style them, derive the expected
numbers from `k` in order and, walking `t` from the start, match each one as a token at the start
of `t` or right after a space, followed by a space. Anything that doesn't match stays plain text.

## Opening links in the app

- **Universal links**: when the app is installed, `https://wemiller.com/apps/scripture-alone/#s=…`
  opens the app (entitlement `applinks:wemiller.com`). The app goes to the passage, selects those
  verses and opens the image designer with the link's template, typeface and aspect. It rebuilds
  the card from its own copy of the translation named by `tr` (falling back to the reader's
  current translation), not from `t`.
- **Custom scheme** (internal and deep links):
  - `scripturealone://open?ref=43003016-43003017` — go to the passage and select it. `ref` takes
    the same format as `k`.
  - `scripturealone://open#s=<payload>` — same as a share link.

### apple-app-site-association

Serve at `https://wemiller.com/.well-known/apple-app-site-association` (HTTPS, no redirects,
`Content-Type: application/json`, no `.json` extension). If the file already exists, add this
object to its `applinks.details` array:

```json
{
  "applinks": {
    "details": [
      {
        "appIDs": ["8ZVSPZYSVF.com.blainemiller.ScriptureAlone"],
        "components": [
          {
            "/": "/apps/scripture-alone",
            "#": "s=*",
            "comment": "Share link without a trailing slash"
          },
          {
            "/": "/apps/scripture-alone/*",
            "#": "s=*",
            "comment": "Share link: the verse rides in the fragment"
          }
        ]
      }
    ]
  }
}
```

The `"#": "s=*"` matchers mean only **share links** open the app; the ordinary product page
(`/apps/scripture-alone/` with no `#s=`) keeps opening in Safari, so people can still read about
and download the app. Drop the two `"#"` lines if every page under the path should open the app.

## Reference web renderer

The card is laid out on a canvas of **1080 units on the long side** and exported at 2× (2160 px).

| Aspect | Canvas (units) | Export (px) |
|---|---|---|
| `square` | 1080 × 1080 | 2160 × 2160 |
| `story` | 608 × 1080 | 1216 × 2160 |
| `wide` | 1080 × 608 | 2160 × 1216 |

### Layout

With `W`, `H` the canvas size and `S = min(W, H)`:

- Padding: horizontal `0.09·W`, vertical `0.09·H`.
- Reference size `R = max(18, 0.034·S)`; wordmark size `M = max(14, 0.024·S)`.
- One column, vertically centered as a group: **passage**, then a **rule**, then the **reference**.
  The wordmark sits at the bottom inside the padding.
- Passage: the template's `ink` color; line height = font size × 1.28 (0.28 extra leading);
  centered (the app also offers left-aligned). **Auto-fit**: the largest size between
  `max(20, 0.022·S)` and `0.066·√(W·H)` whose wrapped height fits in
  `H − 2·0.09·H − 3.6·R − 2.4·M`, using ~95% of that height as the target; binary search is fine.
  If even the minimum doesn't fit, the app trims whole verses from the end (never more than 12
  verses on a card); a web page may instead scroll or shrink further.
- Verse numbers: 0.55 × the passage size, raised 0.32 × the passage size, `accent` color.
- Words of Christ: the template's `red` color.
- Rule: `1.6·R` wide, `max(2, 0.08·R)` thick, rounded, `accent` at 70% opacity, `1.2·R` below the
  passage and `0.9·R` above the reference.
- Reference: `"<REF IN UPPERCASE>  ·  <tr>"` (two spaces either side of a middle dot), bold, size
  `R`, letter-spacing `0.12·R`, `accent` color.
- Wordmark: "Scripture Alone", italic, size `M`, `accent` at 60% opacity. It can be turned off in
  the app; the link doesn't say, so the web may always show it.
- Frame (Parchment and Linen only): a 1.5-unit hairline inset `0.035·S` from the edge, `accent`
  at 35% opacity.
- Background: flat, or a top-to-bottom linear gradient through the listed stops.

### Templates

| `tp` | Background (top → bottom) | Ink | Accent | Red | Frame |
|---|---|---|---|---|---|
| `parchment` | `#F6EDD9` → `#EBDDBF` | `#3B2F20` | `#8A5A2B` | `#A12A1C` | yes |
| `ink` | `#14161A` | `#EDE8DF` | `#C9A45C` | `#FF7A6B` | |
| `dawn` | `#F7D9C4` → `#EFB4A8` → `#A893CC` | `#2E2236` | `#6B4A6E` | `#9E1B32` | |
| `night` | `#0B1026` → `#1D2A57` | `#E9EDF8` | `#A9B8F0` | `#FF8A80` | |
| `linen` | `#F8F5EF` | `#2E2A25` | `#9C7A4E` | `#B0261B` | yes |
| `stone` | `#DEDCD7` → `#C3C0B9` | `#26262A` | `#5A5A62` | `#9B2226` | |
| `olive` | `#46512F` → `#2D3520` | `#F2EFDD` | `#D6C58C` | `#FFA48A` | |
| `minimal` | `#FFFFFF` | `#111111` | `#6E6E6E` | `#C0392B` | |

### Typefaces

| `f` | In the app | Web font stack |
|---|---|---|
| `serif` | New York | `ui-serif, "New York", "Iowan Old Style", Georgia, serif` |
| `sans` | San Francisco | `system-ui, -apple-system, "Segoe UI", "Helvetica Neue", Arial, sans-serif` |
| `charter` | Charter | `Charter, "Bitstream Charter", "Sitka Text", Cambria, serif` |
| `iowan` | Iowan Old Style | `"Iowan Old Style", "Palatino Linotype", Palatino, "URW Palladio L", serif` |
| `georgia` | Georgia | `Georgia, "Times New Roman", serif` |
| `palatino` | Palatino | `Palatino, "Palatino Linotype", "Book Antiqua", "URW Palladio L", serif` |
| `avenir` | Avenir Next | `"Avenir Next", Avenir, "Segoe UI", "Helvetica Neue", Arial, sans-serif` |

Use system fonts only; don't load web fonts from a third-party CDN (it would leak the visit).

The source of truth for all of the above is `ScriptureAlone/Share/ShareStyle.swift` (templates,
aspects, typeface tokens) and `ShareCard.swift` (metrics and layout); keep this table in step.

## Licensed translations

Only public-domain translations (ASV, BSB, KJV) ship today. The share code reads each
translation's `abbreviation`, `copyright` and `license` from the Bible database's `meta` table
(`BibleStore.info`):

- Public-domain (`license` contains "public domain"): no notice on the card; links allowed.
- Anything else: the card prints the translation's `copyright` text under the reference, and the
  app **does not make links** until that publisher's terms (quotation limits, required notice,
  whether web display is allowed) are reviewed. When a licence allows links, add the notice to
  the payload as a new optional key (and bump nothing: unknown keys are ignored by v1 decoders).
