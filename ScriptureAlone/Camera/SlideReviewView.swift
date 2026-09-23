import SwiftUI
import SwiftData
import ScriptureAloneCore

/// Shows what was read from the slide before anything is saved: an editable title, the passages
/// as removable chips, and the other lines with checkboxes. The photo itself is discarded unless
/// the user chooses to keep it.
struct SlideReviewView: View {
    let slide: ScannedSlide
    let appendTo: Note?
    let onSaved: (Note) -> Void

    @Environment(ReaderModel.self) private var model
    @Environment(\.modelContext) private var context
    @Environment(\.dismiss) private var dismiss
    @Query(sort: \Note.updatedAt, order: .reverse) private var notes: [Note]

    @State private var phase = Phase.reading
    @State private var title = ""
    @State private var ranges: [VerseRange] = []
    @State private var lines: [ReviewLine] = []
    @State private var passageText = ""
    @State private var keepPhoto = false
    /// nil = a new note.
    @State private var destination: UUID?
    @State private var didSetDestination = false

    enum Phase: Equatable { case reading, ready, failed(String) }

    struct ReviewLine: Identifiable {
        let id = UUID()
        var text: String
        var included = true
    }

    private var targetNote: Note? { destination.flatMap { id in notes.first { $0.uuid == id } } }

    /// Notes worth offering as "add to": the one we came from, then the most recent few.
    private var destinationChoices: [Note] {
        var choices = Array(notes.prefix(8))
        if let appendTo, !choices.contains(where: { $0.uuid == appendTo.uuid }) { choices.insert(appendTo, at: 0) }
        return choices
    }

    var body: some View {
        NavigationStack {
            Form {
                photoSection
                switch phase {
                case .reading:
                    Section {
                        HStack(spacing: 12) {
                            ProgressView()
                            Text("Reading the slide…")
                        }
                        .accessibilityElement(children: .combine)
                    }
                case .failed(let message):
                    Section {
                        Label(message, systemImage: "exclamationmark.triangle")
                    }
                    fields
                case .ready:
                    fields
                }
            }
            .formStyle(.grouped)
            .navigationTitle(targetNote == nil ? "New Note" : "Add to Note")
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(saveTitle, action: save)
                        .disabled(phase == .reading || nothingToSave)
                }
            }
        }
        .task { await read() }
        .onChange(of: destination) { markLinesTheNoteHas() }
        .onAppear {
            guard !didSetDestination else { return }
            didSetDestination = true
            destination = appendTo?.uuid
        }
    }

    private var saveTitle: String {
        targetNote == nil ? String(localized: "Create Note") : String(localized: "Add to Note")
    }

    private var includedLines: [String] {
        lines.filter(\.included).map { $0.text.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    private var nothingToSave: Bool {
        title.trimmingCharacters(in: .whitespaces).isEmpty && ranges.isEmpty && includedLines.isEmpty
    }

    // MARK: Sections

    private var photoSection: some View {
        Section {
            Image(decorative: slide.image, scale: 1)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: 200)
                .clipShape(.rect(cornerRadius: 10))
                .accessibilityLabel("Photo of the slide")
                .accessibilityAddTraits(.isImage)
            Toggle("Keep this photo with the note", isOn: $keepPhoto)
        } footer: {
            Text(keepPhoto
                 ? "The photo is saved with the note and syncs through your iCloud."
                 : "The photo was read on this device and will be discarded — only the text below is kept.")
        }
    }

    @ViewBuilder
    private var fields: some View {
        Section {
            Picker("Save to", selection: $destination) {
                Text("A new note").tag(UUID?.none)
                ForEach(destinationChoices) { note in
                    Text(note.displayTitle).tag(UUID?.some(note.uuid))
                }
            }
        }

        Section {
            TextField(targetNote == nil ? "Title" : "Heading", text: $title, axis: .vertical)
                .font(.title3.weight(.semibold))
                .accessibilityLabel(targetNote == nil ? "Note title" : "Heading added to the note")
        } header: {
            Text(targetNote == nil ? "Title" : "Heading")
        } footer: {
            if targetNote != nil {
                Text("Added above this slide’s lines. Leave it empty to add just the lines.")
            } else if title.isEmpty, phase == .ready {
                Text("No title was found — type one, or the first passage will name the note.")
            }
        }

        Section {
            if !ranges.isEmpty {
                FlowLayout(spacing: 8) {
                    ForEach(ranges, id: \.self) { range in
                        PassageChip(range: range) { ranges.removeAll { $0 == range } }
                    }
                }
                .padding(.vertical, 4)
            }
            TextField("Add a passage, e.g. John 10:11", text: $passageText)
                .autocorrectionDisabled()
                .onSubmit(addTypedPassages)
                .accessibilityLabel("Add a passage")
        } header: {
            Text("Passages")
        } footer: {
            if ranges.isEmpty, phase == .ready { Text("No passages were found on the slide.") }
        }

        Section {
            if lines.isEmpty {
                Text(phase == .ready ? "Nothing else was on the slide." : "")
                    .foregroundStyle(.secondary)
            }
            ForEach($lines) { $line in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Button { line.included.toggle() } label: {
                        Image(systemName: line.included ? "checkmark.circle.fill" : "circle")
                            .font(.title3)
                            .foregroundStyle(line.included ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(line.text)
                    .accessibilityValue(line.included ? "Included" : "Left out")
                    .accessibilityHint("Double-tap to \(line.included ? "leave this line out" : "include this line")")
                    .accessibilityAddTraits(line.included ? .isSelected : [])
                    TextField("Line", text: $line.text, axis: .vertical)
                        .foregroundStyle(line.included ? .primary : .secondary)
                        .accessibilityLabel("Edit line")
                }
            }
        } header: {
            Text(targetNote == nil ? "Start the note with" : "Add these lines")
        }
    }

    // MARK: Actions

    private func read() async {
        guard phase == .reading else { return }
        do {
            let (_, reading) = try await SlideRecognizer.read(slide.image)
            apply(reading)
            phase = .ready
            let found = [reading.title.isEmpty ? nil : String(localized: "Title: \(reading.title)"),
                         ranges.isEmpty ? nil : String(localized: "\(ranges.count) passages"),
                         String(localized: "\(lines.count) other lines")].compactMap { $0 }
            AccessibilityNotification.Announcement(String(localized: "Slide read. \(found.joined(separator: ", "))")).post()
        } catch {
            phase = .failed(String(localized: "The text on this photo couldn’t be read. You can still type a title and passages."))
        }
    }

    private func apply(_ reading: SlideReading) {
        title = reading.title
        if let store = model.source {
            ranges = SlideParser.ranges(for: reading.passages) { store.verseCount($0) }
        }
        lines = reading.bodyLines.map { ReviewLine(text: $0) }
        // Adding to a note that already has this title: don't repeat it as a heading.
        if let appendTo, appendTo.title.caseInsensitiveCompare(title) == .orderedSame { title = "" }
        markLinesTheNoteHas()
    }

    /// Unchecks lines the destination note already has (a later slide's running header), and
    /// re-checks them when the destination goes back to a new note.
    private func markLinesTheNoteHas() {
        for index in lines.indices {
            if let targetNote {
                lines[index].included = !SlideParser.noteAlreadyHas(lines[index].text, title: targetNote.title, body: targetNote.body)
            } else {
                lines[index].included = true
            }
        }
    }

    private func addTypedPassages() {
        guard let store = model.source else { return }
        let added = SlideParser.ranges(for: ReferenceParser.parseList(passageText)) { store.verseCount($0) }
        guard !added.isEmpty else { return }
        for range in added where !ranges.contains(range) { ranges.append(range) }
        passageText = ""
    }

    private func save() {
        let heading = title.trimmingCharacters(in: .whitespacesAndNewlines)
        let photo = keepPhoto ? SlideImage.jpeg(slide.image) : nil
        // The slide cites the congregation's own numbering — the translation being read; notes
        // store KJV keys (`VerseNumbering`). `ranges` stays native so the review shows the slide's.
        let stored = ranges.map(model.numbering.kjvRange)
        let note: Note
        if let target = targetNote {
            note = target
            note.anchors = SlideParser.merging(note.anchors, stored)
            if note.title.trimmingCharacters(in: .whitespaces).isEmpty {
                note.title = heading
                note.body = SlideParser.append(heading: nil, lines: includedLines, to: note.body)
            } else {
                let sameTitle = note.title.caseInsensitiveCompare(heading) == .orderedSame
                note.body = SlideParser.append(heading: sameTitle ? nil : heading, lines: includedLines, to: note.body,
                                               title: note.title)
            }
            if let photo { note.slidePhoto = photo }
            note.updatedAt = .now
        } else {
            note = Note(title: heading, body: SlideParser.body(for: includedLines),
                        anchors: SlideParser.merging([], stored), origin: "camera")
            note.slidePhoto = photo
            context.insert(note)
        }
        dismiss()
        onSaved(note)
    }
}

/// A passage on the review sheet; tapping it removes it.
private struct PassageChip: View {
    let range: VerseRange
    let onRemove: () -> Void

    var body: some View {
        Button(action: onRemove) {
            HStack(spacing: 6) {
                Image(systemName: "book.closed").imageScale(.small)
                Text(range.display)
                Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
            }
            .font(.callout.weight(.medium))
            .padding(.horizontal, 10).padding(.vertical, 6)
            .background(.tint.opacity(0.14), in: .capsule)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(range.display)
        .accessibilityHint("Double-tap to remove this passage")
        .accessibilityAction(named: "Remove") { onRemove() }
    }
}

/// Lays chips out left to right, wrapping onto new rows.
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(width: proposal.width ?? .infinity, subviews: subviews).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let layout = arrange(width: bounds.width, subviews: subviews)
        for (subview, point) in zip(subviews, layout.points) {
            subview.place(at: CGPoint(x: bounds.minX + point.x, y: bounds.minY + point.y), proposal: .unspecified)
        }
    }

    private func arrange(width: CGFloat, subviews: Subviews) -> (points: [CGPoint], size: CGSize) {
        var points: [CGPoint] = []
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, maxX: CGFloat = 0
        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if x > 0, x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            points.append(CGPoint(x: x, y: y))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
            maxX = max(maxX, x - spacing)
        }
        return (points, CGSize(width: maxX, height: y + rowHeight))
    }
}

/// A photo kept with a camera note, shown in the note editor.
struct SlidePhotoSection: View {
    @Bindable var note: Note
    @State private var image: CGImage?

    var body: some View {
        if let data = note.slidePhoto {
            Section("Slide Photo") {
                Group {
                    if let image {
                        Image(decorative: image, scale: 1)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: .infinity, maxHeight: 260)
                            .clipShape(.rect(cornerRadius: 10))
                            .accessibilityLabel("Photo of the sermon slide")
                    } else {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
                .task(id: data) {
                    image = await Task.detached { SlideImage.decode(data, maxPixelSize: 1600) }.value
                }
                Button("Remove Photo", role: .destructive) {
                    note.slidePhoto = nil
                    note.updatedAt = .now
                }
            }
        }
    }
}
