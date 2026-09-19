# Contributing to Scripture Alone

Scripture Alone is a free, private, offline Bible for iPhone, iPad and Mac. Contributions
are welcome. The bar: every change keeps the app quiet, private, and focused on the text.

## License and what it means for you

Scripture Alone is licensed under the **GNU AGPL‑3.0‑or‑later** (see [LICENSE](LICENSE)). In short:

- You may use, study, modify, and redistribute it freely.
- If you distribute a modified version, you must make your complete corresponding source
  available under the same license. Taking this code closed — to add ads, tracking, or a
  paywall — is exactly what this license forbids.
- The software comes **without warranty of any kind** (AGPL §15–16).

By submitting a contribution you agree it is licensed under AGPL‑3.0‑or‑later **with the
additional permissions in [LICENSE-EXCEPTIONS.md](LICENSE-EXCEPTIONS.md)** (the app‑store
distribution exception) and certify the [Developer Certificate of Origin](https://developercertificate.org).

Official builds on the App Store are published by the copyright holder.

## Ground rules

1. **Open an issue first** for anything larger than a typo.
2. **No telemetry, ads, trackers, or accounts** — proposals that add them are rejected on
   principle. Data leaves the device only through the user's own iCloud.
3. **Never commit licensed Bible text.** Only public‑domain or openly licensed content
   belongs in this repository, with its source and license recorded in
   `Tools/build_bibles.py`.
4. **Accessibility is a feature.** Changes to the reader must keep VoiceOver, Dynamic Type,
   and keyboard navigation working.
5. Tests must pass: `python3 Tools/build_bibles.py --check` and `swift test` in
   `ScriptureAloneCore/`, plus iOS and macOS builds with zero warnings.

## AI / LLM‑assisted contributions

Welcome only when a human stands behind them: say in the PR what you ran and on which
device, and add tests covering the behavior you changed. Un‑run "the model said this
works" submissions will be closed.

## Code of conduct

See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
