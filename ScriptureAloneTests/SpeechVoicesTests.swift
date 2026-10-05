import AVFoundation
import Foundation
import Testing
@testable import Scripture_Alone

/// Which voice Listen offers first, and how the speed slider maps onto the synthesizer.
/// (Mirrors Android's VoiceCatalogTest.)
struct SpeechVoicesTests {
    func voice(_ name: String, _ language: String, _ kind: VoiceOption.Kind) -> VoiceOption {
        VoiceOption(id: "\(language).\(name)", name: name, language: language, kind: kind)
    }

    @Test func homeRegionFirstThenQualityThenLanguageAndName() {
        let voices = [
            voice("Zoe", "en-US", .premium),
            voice("Daniel", "en-GB", .standard),
            voice("Ava", "en-US", .standard),
            voice("Karen", "en-AU", .enhanced),
            voice("Me", "en-US", .personal),
            voice("Arthur", "en-GB", .enhanced),
        ]
        let ordered = voices.ordered(preferring: "en-GB").map(\.name)
        #expect(ordered == ["Arthur", "Daniel", "Me", "Zoe", "Karen", "Ava"])

        let noRegion = voices.ordered(preferring: nil).map(\.name)
        #expect(noRegion == ["Me", "Zoe", "Karen", "Arthur", "Daniel", "Ava"])
    }

    @Test func voiceLanguageIsTheTextsBaseLanguage() {
        #expect(SpeechVoices.voiceLanguage(for: "zh-Hans") == "zh")
        #expect(SpeechVoices.voiceLanguage(for: "pt-BR") == "pt")
        #expect(SpeechVoices.voiceLanguage(for: "fr") == "fr")
        #expect(SpeechVoices.voiceLanguage(for: "") == "en")
    }

    /// Mainland Mandarin for the simplified-script 和合本, whatever region the device is in.
    @Test func homeVoiceLanguage() {
        #expect(SpeechVoices.homeVoiceLanguage(for: "zh", region: "TW") == "zh-CN")
        #expect(SpeechVoices.homeVoiceLanguage(for: "zh", region: nil) == "zh-CN")
        #expect(SpeechVoices.homeVoiceLanguage(for: "en", region: "GB") == "en-GB")
        #expect(SpeechVoices.homeVoiceLanguage(for: "fr", region: nil) == nil)
    }

    @Test func speedMapsMonotonicallyOntoTheSynthesizersRange() {
        let base = AVSpeechUtteranceDefaultSpeechRate
        #expect(SpeechVoices.utteranceRate(forSpeed: 1) == base)
        #expect(abs(SpeechVoices.utteranceRate(forSpeed: 0.5) - base * 0.5) < 0.0001)
        let speeds = stride(from: 0.5, through: 2.0, by: 0.25).map { SpeechVoices.utteranceRate(forSpeed: $0) }
        #expect(speeds == speeds.sorted(), "faster is never slower")
        #expect(Set(speeds).count == speeds.count)
        for rate in speeds {
            #expect(rate >= AVSpeechUtteranceMinimumSpeechRate && rate <= AVSpeechUtteranceMaximumSpeechRate)
        }
        // The top half is compressed: 2× is half-way from the default to the maximum.
        let expected = base + (AVSpeechUtteranceMaximumSpeechRate - base) * 0.5
        #expect(abs(SpeechVoices.utteranceRate(forSpeed: 2) - expected) < 0.0001)
        #expect(SpeechVoices.utteranceRate(forSpeed: 10) <= AVSpeechUtteranceMaximumSpeechRate)
        #expect(SpeechVoices.utteranceRate(forSpeed: 0) >= AVSpeechUtteranceMinimumSpeechRate)
    }

    @Test func sleepTimerDurations() {
        #expect(SleepTimer.minutes15.duration == .seconds(900))
        #expect(SleepTimer.minutes30.duration == .seconds(1_800))
        #expect(SleepTimer.minutes60.duration == .seconds(3_600))
        #expect(SleepTimer.off.duration == nil)
        #expect(SleepTimer.endOfChapter.duration == nil, "end of chapter is an event, not a clock")
    }

    @Test func qualityBadges() {
        #expect(voice("A", "en-US", .standard).badge == nil)
        #expect(voice("A", "en-US", .premium).badge != nil)
        #expect(voice("A", "en-US", .personal).badge != nil)
    }
}
