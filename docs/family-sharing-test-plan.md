# Share with Family: two-device test plan

Real CloudKit sharing can't run on an unsigned simulator or without two iCloud accounts, so this
is a manual plan for two physical devices. Design: [heir-mode.md](heir-mode.md#live-family-sharing-share-with-family).

## Setup

| | Device | Apple Account |
|---|---|---|
| **Owner** ("O") | The developer's iPhone 17 Pro Max | The developer's account |
| **Family** ("F") | A family member's iPhone or iPad | A *different* Apple Account, signed in to iCloud |

1. Build and install a **signed** Debug build from Xcode on both devices (same build). Debug
   builds use CloudKit's *development* environment, where the four record types are created on
   first use. (A TestFlight/App Store build uses *production*: deploy the schema from the
   CloudKit Console first — see "Before shipping" in heir-mode.md.)
2. On both: Settings → [name] → iCloud → the app's iCloud access is on; iCloud Drive not
   required. Have both on Wi-Fi.
3. On O, have some library: a few highlights (at least John 3:16), two notes (one on John 3),
   two favorites. A note with a kept slide photo is useful for step 3.4.
4. Optional but helpful: CloudKit Console → container `iCloud.com.blainemiller.ScriptureAlone`
   → Development → Private Database (O's account, via "Act as iCloud account") to watch the
   `FamilyBible` zone.

## 1. Owner starts sharing

1. O: Appearance (Aa) → Legacy & Export → **Share with Family**.
   - Expect: intro, counts under "What Family Will See" matching the library, the read-only /
     not-shared copy, the "For Years to Come" heir note with **Make a Keepsake Too**.
2. Clear "From You" name. Expect **Start Sharing and Invite…** disabled with "Add your name
   above first". Enter "Dad" and a dedication.
3. Tap **Start Sharing and Invite…**. Expect a brief "Working with iCloud…", then the system
   sharing sheet titled "Dad's Bible".
   - In the sheet's Share Options: permission is **View only** and access is **Only invited
     people**; there is no "anyone with the link" or "can make changes" option.
4. Invite F by Messages (or copy the link and send it). Close the sheet.
5. Expect: Family section lists F as **Invited — hasn't opened it yet**; sync row goes
   "Sending changes…" → **Up to date · now**; the Legacy & Export row shows "On".
6. Console (optional): private DB has zone `FamilyBible` with `cloudkit.share`, `profile`, one
   `h-<verse>` per highlighted verse, `n-<uuid>` per note, `f-<uuid>` per favorite, each with an
   `fp` field.

## 2. Family accepts

1. F: tap the link in Messages. Confirm iCloud's prompt to open the shared item, if one appears.
2. Expect Scripture Alone to open (cold launch *and*, separately, already running — try both
   by force-quitting F first on a second run) and show **Shared with You**: Dad's name,
   dedication, a spinner "Fetching from iCloud…", then **Read Dad's Bible** enabled.
3. Tap **Read Dad's Bible**. Expect:
   - Banner "Reading Dad's Bible", the dedication, "Shared live · updated now", **My Bible**.
   - The reader switches to Dad's translation if F has it.
   - John 3:16 highlighted in Dad's color with the thin pen-line under it; an outlined note
     bubble at the end of the noted range. Tapping a verse does **not** select it; no
     highlight/note/favorite controls appear.
   - Notes panel shows Dad's notes read-only (Copy Note works); the **Favorites** tab lists
     Dad's favorites; pull down refreshes.
4. F: Legacy & Export → **Shared with You** lists "Dad's Bible — Shared live · updated … ·
   N highlights · N notes · N favorites". F's own highlights/notes are unchanged (check F's own
   Bible with **My Bible**), and F's Notes panel doesn't contain Dad's notes.
5. O: Share with Family → pull to refresh. F now shows **Can view**.

## 3. Live updates

For each change on O, wait ~5 s, then on F bring the app to the foreground (or pull to
refresh in Legacy & Export / the Notes panel). With the silent-push subscription working, an
open reader on F may update by itself within seconds.

1. O highlights John 3:17 green → F shows it green with the pen-line.
2. O recolors John 3:16 → F follows. O removes the John 3:17 highlight → F removes it.
3. O edits a note's text → F's popover and Notes panel show the new text. O deletes a note → it
   disappears on F. O adds a new note on Romans 8 → it appears on F.
4. O adds a favorite and removes another → F's Favorites tab follows. The slide photo of a
   camera note never appears on F.
5. O changes the dedication in Share with Family → F's banner updates.
6. Burst: O selects 20 verses and highlights them at once → F receives all 20 after one
   upload (O's sync row shows a single "Sending changes…").
7. O's second device (iPad on the same account, optional): open the app. Without visiting
   Share with Family, add a highlight there → it reaches F too (the iPad picked up sharing from
   the iCloud key-value flag and rebuilt its upload manifest from the zone).

## 4. Offline and resilience

1. F: airplane mode, force-quit, reopen, open Dad's Bible → it reads fully offline from the
   cache; "updated" shows the last successful fetch time.
2. O: airplane mode, add a highlight → sync row shows "No connection right now…". Turn the
   network back on and foreground the app → **Up to date**, and F receives it.
3. O: send the app to the background right after a change → the change still goes out
   (flush on background).

## 5. Removing a person, stopping, and keepsakes

1. O: **Invite or Remove People…** → remove F. On F, foreground the app → the Bible shows
   **Sharing ended … · Keep a Copy** in the banner and the list; the cached copy still reads.
2. F: **Keep a Copy** → sheet explains this is the last copy → **Keep This Copy**. Expect it to
   appear under **Keepsakes You've Been Given** as "Dad's Bible" (same `bibleID` as any keepsake
   Dad made — it replaces an older one, and the sheet says so), and the ended live entry to go.
3. O: re-invite F, F accepts again → live again.
4. O: **Stop Sharing…** → confirmation text → Stop. Expect the owner screen back to "Start
   Sharing…", the "On" badge gone, and (Console) the `FamilyBible` zone deleted. F: foreground →
   "Sharing ended"; keeping a copy works as in 5.2.
5. O: start sharing, then stop from the **system sharing sheet** ("Stop Sharing" inside it) →
   the app also deletes the zone and shows sharing off.
6. F: with a live share, Legacy & Export → swipe **Leave** → confirmation → the entry goes, and
   O's list no longer shows F as viewing after a refresh.

## 6. Accounts and errors

1. F signed out of iCloud, tap an invite → iCloud asks F to sign in, or the app's Shared with
   You sheet shows "Couldn't Open the Invitation" with a plain-language reason; no crash.
2. O signed out of iCloud → Start Sharing shows "Sign in to iCloud in Settings…".
3. O's iCloud full (if practical) → sync row shows the iCloud-storage message; freeing space and
   making a change recovers.
4. Someone who wasn't invited opens the link → iCloud refuses it (invite-only); the app doesn't
   add anything.

## 7. Regression checks

- Keepsakes still import, open, and show "Read-only keepsake" in the banner; removing a keepsake
  that's open still closes it; a live share and a keepsake of the same person can both be listed
  and each opens correctly.
- Widgets and the Apple Watch app are unaffected (they never see shared Bibles).
- A person who never uses Share with Family makes no CloudKit calls for it (Console shows no
  `FamilyBible` zone; no shared-database subscription).

## What the simulator covers

`-fakeSharedBible live|ended`, `-fakeFamilyOwner` and `-familyScene reader|library|owner|owner-bottom`
(DEBUG only, `ScriptureAlone/FamilyShare/FamilyDebug.swift`) render every screen above with
invented data run through the real record mapping, without CloudKit. Mapping, diffing,
batching, snapshot updates and the CKRecord bridge are covered by
`FamilyMirrorTests` (`cd ScriptureAloneCore && swift test`).
