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
// Not suites on purpose: the screenshot rig, the Play/ASC scripts, and the 3 instrumented Android
// tests (they run only if an emulator is already up — never booted for this).
export default {
  name: 'Scripture Alone',
  suites: {
    // ── The shared core: ~520 Swift Testing cases. Its intentional gates stay as they are —
    //    ContentKeyVaultTests (Secure Enclave parts withKnownIssue), ShippedPackageTests (known issue
    //    when ASV.sabible isn't built), DemoPackageTests (only with ~/.scripture-alone-demo),
    //    RealFileProbeTests (only with SA_IMPORT_PROBE).
    core: {
      type: 'cmd',
      cmd: 'swift',
      args: ['test', '--scratch-path', '/tmp/sa-soren-core'],
      cwd: 'ScriptureAloneCore',
      description: 'ScriptureAloneCore package tests (shared by iOS, iPadOS, macOS, watchOS)',
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
      destination: 'platform=iOS Simulator,name=iPhone 17 Pro',
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
    //    android-shared needs it too. Soren takes it from env, ~/.soren/credentials.json, or the
    //    login keychain (service SA_CONTENT_KEY_SEED, docs/lockman/README.md) and masks it.
    android: {
      type: 'gradle',
      cwd: 'android',
      unit: 'testDebugUnitTest',
      secrets: ['SA_CONTENT_KEY_SEED'],
      javaHome: '/opt/homebrew/opt/openjdk@17',
      description: 'Android :app + :wear unit tests (JVM)',
    },

    // ── :shared is a plain Kotlin JVM module: it has `test`, not `testDebugUnitTest`.
    'android-shared': {
      type: 'cmd',
      cmd: './gradlew',
      args: ['--no-daemon', ':shared:test'],
      cwd: 'android',
      secrets: ['SA_CONTENT_KEY_SEED'],
      env: { JAVA_HOME: '/opt/homebrew/opt/openjdk@17' },
      description: 'Android :shared unit tests (canon, snapshot, Wear link, sealed packages)',
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
    requireGreen: ['core', 'ios', 'macos', 'watch', 'android', 'android-shared', 'tools'],
  },
};
