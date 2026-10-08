// soren.config.mjs — QA suites for Scripture Alone.
//
// Run locally:   node ../_shared/soren/soren.mjs run "Scripture Alone"
//                node ../_shared/soren/soren.mjs run "Scripture Alone" core ios
//                node ../_shared/soren/soren.mjs migrate "Scripture Alone"
//                node ../_shared/soren/soren.mjs doctor "Scripture Alone"
//
// Soren lives in _shared/soren and is pluggable per project via this file; every field is in
// _shared/soren/docs/config.md. `root` defaults to this file's directory (the repository), so the
// `cwd` paths below are relative to the repository root.
//
// SHAPE OF THIS REPO
//   • ScriptureAloneCore/ — the Swift package every Apple target shares (iPhone, iPad, Mac, Watch,
//     widgets): stores, import, sealed packages, keepsakes, family-mirror diff, settings-sync rules.
//   • ScriptureAlone.xcodeproj (XcodeGen, project.yml) — the app (iOS + macOS) with its widgets and
//     Background Assets extension, the ScriptureAloneTests bundle (Swift Testing, hosted in the app,
//     run with -inMemoryStore), and the watch app + complications.
//     The project compiles ScriptureAlone/Generated/ContentKeySeed.swift, which is not in git:
//     `python3 ci_scripts/write_content_key_seed.py` writes it (an empty seed without
//     SA_CONTENT_KEY_SEED) and must have run once before the Apple suites — Xcode Cloud's post-clone
//     and Tools/prepare_licensed_local.sh both do.
//   • android/ — :app (phone/tablet), :wear (Wear OS) and :shared (plain Kotlin JVM).
//   • Tools/ — the Python build and packaging tools.
//
// Not suites on purpose: the screenshot rig (Tools/capture_screenshots.sh — captures aren't a
// gate), the Play/ASC scripts. The instrumented Android tests (connectedDebugAndroidTest, incl.
// SystemBarsInsetsTest) run inside `android-release-smoke`'s emulator session.
export default {
  name: 'Scripture Alone',
  suites: {
    // ── The shared core: ~520 Swift Testing cases. Its intentional gates stay as they are —
    //    ContentKeyVaultTests (Secure Enclave parts withKnownIssue), ShippedPackageTests (known issue
    //    when ASV.sabible isn't built), DemoPackageTests (only with ~/.scripture-alone-demo),
    //    RealFileProbeTests (only with SA_IMPORT_PROBE).
    //    scripts/swift-coverage.mjs runs `swift test --enable-code-coverage` and prints the
    //    package's own line coverage (Sources/ only) as a `soren-coverage:` line.
    core: {
      type: 'cmd',
      cmd: 'node',
      args: ['scripts/swift-coverage.mjs'],
      description: 'ScriptureAloneCore package tests (shared by iOS, iPadOS, macOS, watchOS) + line coverage',
      tags: ['regression'],
    },

    // ── The app layer on iOS: ScriptureAloneTests hosted in the app — SwiftData models and their
    //    CloudKit rules, settings sync, imported-Bible sync guards, asset packs vs Tools/asset-packs,
    //    the sealed ASV, reader model, App Intents, keepsake library, user guide and catalogue
    //    downloads (stubbed URLProtocol), links, notes PDF. Building the scheme also compiles the
    //    widgets and the Background Assets extension.
    ios: {
      type: 'xcodebuild-test',
      project: 'ScriptureAlone.xcodeproj',
      scheme: 'ScriptureAlone',
      destination: 'platform=iOS Simulator,name=SA UI iPhone 17 Pro,OS=27.0',
      shutdownSimulator: true,
      platform: 'ios',
      xcodegen: true,
      description: 'App unit tests on the iOS simulator (+ widgets, assets extension compile)',
      tags: ['regression'],
    },

    // ── The same bundle on the native macOS destination (kept buildable, and the Mac widgets).
    macos: {
      type: 'xcodebuild-test',
      project: 'ScriptureAlone.xcodeproj',
      scheme: 'ScriptureAlone',
      destination: 'platform=macOS',
      platform: 'macos',
      xcodegen: true,
      description: 'App unit tests on macOS (+ Mac widgets compile)',
    },

    // ── UI tests (XCUITest): every screen and key flow on iPhone and iPad — the iPad run also stands
    //    in for the Mac, which runs the iPad app. The ScriptureAloneUITests scheme launches the app
    //    with -UITestMode (ScriptureAlone/App/UITestMode.swift, DEBUG only): an in-memory store with
    //    the demo library, a fresh install's settings, no iCloud, no network, a silent Listen, and
    //    always the BSB — never a licensed text. Unsigned is fine: nothing here needs an entitlement.
    //    DEDICATED simulators (iOS/watchOS 27.0), booted by the suite and shut down after it: on the
    //    shared "iPhone 17 Pro" / "iPad Pro 13-inch (M5)" other projects' UI suites launch their apps
    //    mid-run and steal the foreground. Create them once with
    //      xcrun simctl create "SA UI iPhone 17 Pro" "iPhone 17 Pro" com.apple.CoreSimulator.SimRuntime.iOS-27-0
    //      xcrun simctl create "SA UI iPad Pro 13-inch (M5)" "iPad Pro 13-inch (M5)" com.apple.CoreSimulator.SimRuntime.iOS-27-0
    //      xcrun simctl create "SA UI Watch S11 46mm" "Apple Watch Series 11 (46mm)" com.apple.CoreSimulator.SimRuntime.watchOS-27-0
    'ui-iphone': {
      type: 'xcodebuild-test',
      project: 'ScriptureAlone.xcodeproj',
      scheme: 'ScriptureAloneUITests',
      destination: 'platform=iOS Simulator,name=SA UI iPhone 17 Pro,OS=27.0',
      shutdownSimulator: true,
      platform: 'ios',
      xcodegen: true,
      derivedDataPath: '/tmp/soren-dd-scripture-alone-ui-iphone',
      extraArgs: ['-collect-test-diagnostics', 'never'],
      description: 'UI tests on iPhone (XCUITest, -UITestMode)',
      tags: ['regression', 'ui'],
    },
    'ui-ipad': {
      type: 'xcodebuild-test',
      project: 'ScriptureAlone.xcodeproj',
      scheme: 'ScriptureAloneUITests',
      destination: 'platform=iOS Simulator,name=SA UI iPad Pro 13-inch (M5),OS=27.0',
      shutdownSimulator: true,
      platform: 'ios',
      xcodegen: true,
      derivedDataPath: '/tmp/soren-dd-scripture-alone-ui-ipad',
      extraArgs: ['-collect-test-diagnostics', 'never'],
      description: 'UI tests on iPad — and so the Mac (Designed for iPad)',
      tags: ['regression', 'ui'],
    },

    // ── The watch app's UI tests: home, favorites, notes, highlights, books → chapter → verse, the
    //    translation picker. -UITestMode installs the BSB's watch edition (Debug builds only).
    'ui-watch': {
      type: 'xcodebuild-test',
      project: 'ScriptureAlone.xcodeproj',
      scheme: 'ScriptureAloneWatch',
      destination: 'platform=watchOS Simulator,name=SA UI Watch S11 46mm,OS=27.0',
      shutdownSimulator: true,
      platform: 'ios',
      xcodegen: true,
      derivedDataPath: '/tmp/soren-dd-scripture-alone-ui-watch',
      extraArgs: ['-collect-test-diagnostics', 'never'],
      description: 'watchOS UI tests (XCUITest, -UITestMode)',
      tags: ['regression', 'ui'],
    },

    // ── Apple Watch app + complications: a compile gate. The watch's logic that can be tested
    //    lives in ScriptureAloneCore (WatchEdition, CompanionData) and in WatchLinkKeys (tested in
    //    ScriptureAloneTests); nothing else ever compiles the watch target.
    watch: {
      type: 'cmd',
      cmd: 'sh',
      args: ['-c', 'xcodegen generate --quiet && xcodebuild build -project ScriptureAlone.xcodeproj '
        + '-scheme ScriptureAloneWatch -destination "generic/platform=watchOS Simulator" '
        + '-derivedDataPath /tmp/sa-soren-dd-watch CODE_SIGNING_ALLOWED=NO -quiet'],
      description: 'watchOS app + complications build (compile gate)',
    },

    // ── Android phone/tablet (:app) and Wear OS (:wear) JVM unit tests.
    //    With the NASB packs in place (any local licensed build), :app and :wear refuse to
    //    configure without SA_CONTENT_KEY_SEED — and Gradle configures every module, so
    //    android-shared needs it too. Includes ListenSpeechTest — the Listen ANR (a blocking
    //    TextToSpeech.stop must never hold up a skip) — and ImageSizingTest. Soren takes it from env, ~/.soren/credentials.json, or the
    //    login keychain (service SA_CONTENT_KEY_SEED, docs/lockman/README.md) and masks it.
    //    Line coverage: JaCoCo (android/build.gradle.kts › coverageSummary) prints each module's figure.
    android: {
      type: 'gradle',
      cwd: 'android',
      unit: ['--no-daemon', '--max-workers=4', 'testDebugUnitTest', ':shared:test',
        ':app:coverageSummary', ':wear:coverageSummary', ':shared:coverageSummary'],
      secrets: ['SA_CONTENT_KEY_SEED'],
      javaHome: '/opt/homebrew/opt/openjdk@17',
      description: 'Android :app + :wear unit tests (JVM)',
    },

    // ── :shared is a plain Kotlin JVM module: it has `test`, not `testDebugUnitTest`.
    'android-shared': {
      type: 'cmd',
      cmd: './gradlew',
      args: ['--no-daemon', ':shared:test', ':shared:coverageSummary'],
      cwd: 'android',
      secrets: ['SA_CONTENT_KEY_SEED'],
      env: { JAVA_HOME: '/opt/homebrew/opt/openjdk@17' },
      description: 'Android :shared unit tests (canon, snapshot, Wear link, sealed packages, SpeechThread)',
    },

    // ── Android release, as shipped: the R8-minified (and resource-shrunk) phone APK, debug-signed so it
    //    installs on the emulator, with every Bible pack in its own assets (-PsideloadApk). The script
    //    checks R8 renamed the app's classes (mapping.txt), installs it on its own `sa_guide_phone` AVD (API 35)
    //    — reused when running, else booted headless on port 5556 and shut down after; any other
    //    emulator (Haven's haven_phone) is never touched — and drives
    //    it through uiautomator: launch, open a chapter, Listen, Previous/Next tapped as fast as adb
    //    can (the 1.1.0-rc.5 ANR), the share card, and a large photo imported as a slide. Any crash,
    //    ANR or R8-stripped class fails it. One Gradle at a time on this Mac: it builds first, alone.
    'android-release-smoke': {
      type: 'cmd',
      cmd: 'node',
      args: ['scripts/android-release-smoke.mjs'],
      secrets: ['SA_CONTENT_KEY_SEED'],
      env: {
        JAVA_HOME: '/opt/homebrew/opt/openjdk@17',
        ANDROID_HOME: '/opt/homebrew/share/android-commandlinetools',
        SA_AVD: 'sa_guide_phone',
        SA_EMULATOR_PORT: '5556',
      },
      description: 'Android R8 release APK: build + emulator smoke (Listen skips, share card, image import)',
      tags: ['regression'],
    },

    // ── Wear OS: the R8-minified release build (debug-signed). Nothing else builds the watch's release.
    'android-wear': {
      type: 'cmd',
      cmd: './gradlew',
      args: ['--no-daemon', ':wear:assembleRelease', '-PdebugSignedRelease'],
      cwd: 'android',
      secrets: ['SA_CONTENT_KEY_SEED'],
      env: { JAVA_HOME: '/opt/homebrew/opt/openjdk@17' },
      description: 'Wear OS release build (R8)',
    },

    // ── The Python build and packaging tools, offline: Bible stores rebuilt and checked against the
    //    committed ones, the widget list, the .sabible packager and the shipped ASV package.
    tools: {
      type: 'cmd',
      cmd: 'python3',
      args: ['-W', 'ignore::ResourceWarning', '-m', 'unittest', 'discover', '-s', 'Tools/tests'],
      description: 'Tools/tests (lockman parser, build_bibles, companion data, package_translation)',
    },
  },

  // The suites `soren migrate` runs: the SwiftData schema and the keepsake format live here.
  migration: ['core', 'ios'],

  release: {
    // Documentation of the release gate.
    requireGreen: ['core', 'ios', 'macos', 'watch', 'ui-iphone', 'ui-ipad', 'ui-watch', 'android', 'android-shared',
      'android-release-smoke', 'android-wear', 'tools'],
  },
};
