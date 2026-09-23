import SwiftUI

/// Credits and licenses for the maps, places, timeline and charts.
struct ContextAttributionView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("Study context works entirely offline. Nothing about what you read is sent anywhere; the one exception is a link you choose to open.")
                        .font(.subheadline)
                }
                Section("Places") {
                    credit("OpenBible.info Bible Geocoding Data",
                           detail: String(localized: "Places named in the Bible, their most likely locations, confidence scores and the verses that mention them. By Stephen Smith, OpenBible.info. Licensed CC BY 4.0. Used unmodified except for choosing each place’s most confident identification.", comment: "Credit for a data source in Sources & Credits"),
                           links: [("openbible.info/geo", "https://www.openbible.info/geo/"),
                                   ("CC BY 4.0", "https://creativecommons.org/licenses/by/4.0/")])
                    credit("OpenStreetMap",
                           detail: String(localized: "A few site coordinates in the OpenBible data come from OpenStreetMap. © OpenStreetMap contributors, available under the Open Database License.", comment: "Credit for a data source in Sources & Credits"),
                           links: [("openstreetmap.org/copyright", "https://www.openstreetmap.org/copyright")])
                }
                Section("Base map") {
                    credit("Natural Earth",
                           detail: String(localized: "Coastlines, land, lakes and rivers at 1:10 million, clipped to the lands of the Bible and simplified. Public domain. Modern reservoirs and canals are left out.", comment: "Credit for a data source in Sources & Credits"),
                           links: [("naturalearthdata.com", "https://www.naturalearthdata.com")])
                }
                Section("Timeline and charts") {
                    credit(String(localized: "Written for Scripture Alone", comment: "Credit for a data source in Sources & Credits"),
                           detail: String(localized: "Eras, events, and the kings, journeys, tribes and feasts charts were compiled for this app from the biblical text and standard chronologies. Many dates are approximate and some are disputed; the app notes where.", comment: "Credit for a data source in Sources & Credits"),
                           links: [])
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Self.chronologies, id: \.self) { Text($0).font(.footnote) }
                    }
                    .padding(.vertical, 4)
                }
                Section {
                    Text("Scripture Alone is free software under the GNU AGPL. The full list of sources, versions and checksums is in docs/context-sources.md in the source code.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Sources & Credits")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
        }
        #if os(macOS)
        .frame(minWidth: 460, minHeight: 560)
        #endif
    }

    static let chronologies = [
        "Eugene H. Merrill, Kingdom of Priests: A History of Old Testament Israel, 2nd ed. (2008) — patriarchs to the return, early exodus date.",
        "Edwin R. Thiele, The Mysterious Numbers of the Hebrew Kings, 3rd ed. (1983) — reigns of the kings of Israel and Judah.",
        "Kenneth A. Kitchen, On the Reliability of the Old Testament (2003) — the case for a late exodus date.",
        "Jack Finegan, Handbook of Biblical Chronology, rev. ed. (1998).",
        "Harold W. Hoehner, Chronological Aspects of the Life of Christ (1977).",
        "F. F. Bruce, Paul: Apostle of the Heart Set Free (1977) — Paul’s journeys and letters.",
    ]

    private func credit(_ title: String, detail: String, links: [(String, String)]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.headline)
            Text(detail).font(.subheadline).foregroundStyle(.secondary)
            ForEach(links, id: \.1) { label, url in
                if let url = URL(string: url) { Link(label, destination: url).font(.subheadline) }
            }
        }
        .padding(.vertical, 4)
    }
}
