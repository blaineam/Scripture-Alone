# In the car: CarPlay and Android Auto

Scripture Alone reads aloud in the car — Apple's **CarPlay Audio** and Android's **Android Auto** media-app
surfaces, the same four screens on both. Only the platforms' own templates are used (lists and Now Playing): nothing
custom, and nothing to read while driving.

| Screen | What it holds |
|---|---|
| **Listen** | *Today's Reading* — the day's reading in the reader's plan (Spurgeon's meditation included: heading, the verse in the reader's translation, then the meditation) — and *Continue*, the chapter the reader left off, read on into the next. |
| **Books** | Old Testament and New Testament → books → chapters. Every list is kept to the host's row limit: 150 psalms become runs ("1 – 12"), then runs of runs ("1 – 144"). A single-chapter book plays at once. |
| **Favorites** | The reader's favorite passages, newest first. |
| **Now Playing** | The platform's own screen: play/pause, previous/next verse. On CarPlay a speed button cycles 0.75 → 2×. |

## How it is built

- **One speaker.** A row starts the same `ListenController` as the Listen button: the same voices (the system voice, and on
  iPhone Mi Speaks Studio voices), the same speed, the same verse-by-verse reading, the same lock-screen and Now Playing
  entry. `playTodaysReading` takes no UI, which is what made this small.
- **iOS:** `ScriptureAlone/CarPlay/` — `CarPlayCatalog` (the lists, as plain tested values), `CarPlayPlayer` (a reader of its own,
  since CarPlay can connect with no phone window open: the bundled translation the reader chose, and where they left off),
  `CarPlaySceneDelegate` (turns the lists into `CPTabBarTemplate`/`CPListTemplate`). The app delegate hands the car's scene to
  it (`FamilyShareAppDelegate.sceneDelegateClass`); `Info.plist` declares the CarPlay scene; the entitlement is
  `com.apple.developer.carplay-audio`. In a car the Silent switch does not mute Listen (`CarAudio`).
- **Android:** `ListenPlaybackService` is a `MediaLibraryService`; `CarLibrary` serves the tree (`data/listen/CarCatalog.kt`,
  the same shape as the iOS lists, tested on the JVM) and `ListenPlayer.handleSetMediaItems` starts what the car chose.
  `res/xml/automotive_app_desc.xml` declares a media app. `CarLibraryServiceTest` (instrumented) connects a `MediaBrowser`,
  browses and chooses a chapter, as Android Auto does.

## Favorites play as a playlist

Choosing a favorite reads it and then goes on through the rest of the list — the ones after it, then the ones before it — each
named by its reference before it is read (`CarPlayEntry.Action.playFavorites`, `CarAction.Favorites`). The speed button is an
image the app draws (the system's rate button reads a media player's rate, and speech has none); its labels are fractions
(¾× 1× 1¼× 1½× 2×) on one canvas so they stay large.

## Limits, on purpose

- CarPlay reads the translation the reader chose when it is on the phone — bundled or **imported** (the car's reader is given the
  reader's imports, as every window is) — else the app's default. A translation fetched online with an API key (ESV, API.Bible)
  is not read in the car: it needs the network and a loader. Android reads any translation the app lists as installed, imports included.
- The Favorites list is read when CarPlay connects; a favorite added while the car is connected shows on the next connection.
- No search and no text on screen beyond titles: that is what the audio templates allow. Voice is Siri's (below).

## Siri: "Play John 3:16–21 in Scripture Alone"

CarPlay's Siri asks an audio app to play through SiriKit's media request (`INPlayMediaIntent`). The app handles it itself —
`application(_:handlerFor:)` in `FamilyShareAppDelegate` returns `PlayPassageHandler` (`Intents/`), no Intents extension — and what
Siri heard goes through the search box's reference parser, in the app's language, into the same player the CarPlay rows use
(`CarPlayPlayer.play`). A verse, a range, a chapter or a list all work; words that aren't a reference are refused. Needs the
Siri capability on the App ID (entitlement `com.apple.developer.siri`) and `INPlayMediaIntent` / `INSupportedMediaCategories` (Audiobooks only; App Store Connect refuses `INMediaCategoryOther`)
in Info.plist. Not yet tried in a car: say "Play Psalm 23 in Scripture Alone", "Read Romans 8:28 through 30 with Scripture Alone".
Android Auto's Google Assistant is the same idea: a voice request reaches `ListenPlaybackService.onAddMediaItems` with the words as
the item's search query, becomes a `play:spoken:<words>` id (`CarAction.Spoken`) and is read through `ReferenceParser` into
`CarLibrary.play`. Not yet tried in a car: "Hey Google, play Psalm 23 on Scripture Alone".

## Testing in a car

**iPhone.** The entitlement must be on the App ID (Certificates, Identifiers & Profiles → the app's identifier → CarPlay
Audio) or a device build is refused with *"Provisioning profile doesn't include the com.apple.developer.carplay-audio
entitlement"* — the Xcode Cloud archive too. With it: build to the phone, connect to the car (cable or wireless), and the app
appears on the CarPlay home screen. Check: the three tabs; *Today's Reading* with a plan started and without one (it says
what to do); a chapter, a book with many chapters (Psalms), a favorite; Now Playing's speed button; pausing from the
steering wheel; the phone's Silent switch on (it must not matter); switching the car off (reading pauses).

**Android.** Plug the phone into the car (or use the Desktop Head Unit): Scripture Alone appears among the media apps.
Developer mode on the phone → Android Auto settings → *Unknown sources* is needed for a build that isn't from Play.
