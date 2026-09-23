import SwiftUI

/// Where each commentator stands, so a reader knows whose tradition is speaking before reading
/// his take on a baptism or church-order passage. The same doctrine of salvation runs through all
/// three; baptism and church government are where they part.
///
/// English only, like the commentaries themselves (they are hidden outside English — see
/// docs/localization.md), so the text is verbatim rather than in the string catalog.
struct CommentatorTraditionsView: View {
    @Environment(\.dismiss) private var dismiss

    private struct Row: Identifiable {
        let id: String
        let name: String
        let tradition: String
        let salvation: String
        let baptism: String
        let church: String
        let note: String
    }

    private let rows = [
        Row(id: "calvin", name: "John Calvin (1509–1564)", tradition: "Reformed",
            salvation: "Reformed", baptism: "Infant baptism", church: "Presbyterian",
            note: "The fountainhead of the Reformed tradition. Reformed Baptists share his doctrine of salvation, and the 1689 Confession draws heavily on the confessions that follow him; they part with him on baptism."),
        Row(id: "gill", name: "John Gill (1697–1771)", tradition: "Particular Baptist",
            salvation: "Reformed", baptism: "Believer’s baptism", church: "Congregational (Baptist)",
            note: "From the same tradition as the 1689 London Baptist Confession. A “high” Calvinist: wary of speaking of a free offer of the gospel to all, and taught eternal justification — both minority views among Reformed Baptists today."),
        Row(id: "jfb", name: "Jamieson, Fausset & Brown (1871)", tradition: "Presbyterian & Anglican",
            salvation: "Reformed-leaning evangelical", baptism: "Infant baptism", church: "Presbyterian / Anglican",
            note: "Jamieson and Brown were Scottish Presbyterians, Fausset an evangelical Anglican. Conservative, with a high view of Scripture. Brown was postmillennial, which colours some prophetic passages."),
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text(verbatim: "All three hold the doctrines of grace; they differ chiefly on baptism and church government. Read a commentator on those passages knowing where he stands.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                    ForEach(rows) { row in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(verbatim: row.name).font(.headline)
                            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 4) {
                                fact("Tradition", row.tradition)
                                fact("Salvation", row.salvation)
                                fact("Baptism", row.baptism)
                                fact("Church government", row.church)
                            }
                            Text(verbatim: row.note)
                                .font(.callout)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .padding()
            }
            .navigationTitle(Text(verbatim: "The commentators"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Text(verbatim: "Done") }
                }
            }
        }
        .frame(minWidth: 340, idealWidth: 440, minHeight: 420, idealHeight: 620)
    }

    private func fact(_ label: String, _ value: String) -> some View {
        GridRow {
            Text(verbatim: label).font(.caption).foregroundStyle(.secondary)
            Text(verbatim: value).font(.caption.weight(.medium))
        }
    }
}
