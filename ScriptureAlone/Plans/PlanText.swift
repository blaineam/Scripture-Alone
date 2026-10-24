import ScriptureAloneCore

/// What the reader sees a plan called. The core keeps English titles (they are stored, and a plan's id
/// is what identifies it); the screens, notifications and Siri show these, in the reader's language.
/// A book plan is named by its book, in the language the Bible is being read in.
extension ReadingPlan {
    var displayTitle: String {
        switch kind {
        case .canonical:
            String(localized: "Bible in a Year", comment: "Reading plan name")
        case .chronological:
            String(localized: "Bible in a Year, Chronological", comment: "Reading plan name: the whole Bible in the order the events happened")
        case .book:
            days.first?.passages.first?.book.name ?? title
        case .imported:
            title
        }
    }

    var displaySummary: String {
        switch kind {
        case .canonical:
            String(localized: "Genesis to Revelation in order, about three chapters a day.", comment: "Reading plan summary")
        case .chronological:
            String(localized: "The whole Bible in the order the events happened, about three chapters a day.", comment: "Reading plan summary")
        case .book:
            String(localized: "One chapter a day through \(displayTitle).", comment: "Reading plan summary. %@ is a book name.")
        case .imported:
            String(localized: "Schedule from \(source?.name ?? title). The text is read in your translation.",
                   comment: "Reading plan summary for an imported plan. %@ is the source's name, like a church.")
        }
    }
}
