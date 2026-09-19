import SwiftUI
import PhotosUI
import UniformTypeIdentifiers
import ScriptureAloneCore
#if os(iOS)
import UIKit
#else
import AppKit
#endif

/// A photo of a slide, waiting to be read and reviewed.
struct ScannedSlide: Identifiable {
    let id = UUID()
    let image: CGImage
}

/// Gets a slide image from wherever the user has one — the camera, their photos, a file,
/// the clipboard, or (on Mac) an iPhone through Continuity Camera — and hands it to review.
/// Nothing is saved until the user confirms the review sheet.
@Observable
final class SlideCapture {
    var slide: ScannedSlide?
    var showCamera = false
    var showPhotoPicker = false
    var showFileImporter = false
    var photoItem: PhotosPickerItem?
    var failure: String?

    static var canTakePhoto: Bool {
        #if os(iOS)
        UIImagePickerController.isSourceTypeAvailable(.camera)
        #else
        false
        #endif
    }

    func accept(_ image: CGImage) {
        slide = ScannedSlide(image: image)
    }

    func accept(data: Data) async {
        let image = await Task.detached(priority: .userInitiated) { SlideImage.decode(data) }.value
        if let image { accept(image) } else { failure = String(localized: "That file doesn’t look like an image.") }
    }

    func loadPickedPhoto() async {
        guard let item = photoItem else { return }
        photoItem = nil
        do {
            if let data = try await item.loadTransferable(type: Data.self) {
                await accept(data: data)
            } else {
                failure = String(localized: "That photo couldn’t be opened.")
            }
        } catch {
            failure = error.localizedDescription
        }
    }

    func importFile(_ result: Result<URL, Error>) async {
        switch result {
        case .success(let url):
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url) else {
                failure = String(localized: "That file couldn’t be opened.")
                return
            }
            await accept(data: data)
        case .failure(let error):
            failure = error.localizedDescription
        }
    }

    /// Continuity Camera ("Import from iPhone"), drag and drop, and paste all arrive as item providers.
    func importProviders(_ providers: [NSItemProvider]) -> Bool {
        guard let provider = providers.first(where: { $0.hasItemConformingToTypeIdentifier(UTType.image.identifier) }) else {
            return false
        }
        _ = provider.loadDataRepresentation(for: .image) { [weak self] data, _ in
            Task { @MainActor in
                guard let self else { return }
                if let data { await self.accept(data: data) } else { self.failure = String(localized: "That image couldn’t be read.") }
            }
        }
        return true
    }

    #if os(macOS)
    static var pasteboardHasImage: Bool {
        NSPasteboard.general.canReadObject(forClasses: [NSImage.self], options: nil)
    }

    func pasteImage() {
        guard let image = NSPasteboard.general.readObjects(forClasses: [NSImage.self])?.first as? NSImage,
              let cgImage = image.cgImage(forProposedRect: nil, context: nil, hints: nil) else {
            failure = String(localized: "There’s no image on the clipboard.")
            return
        }
        accept(cgImage)
    }
    #endif
}

/// The toolbar control: "Scan Slide" in the Notes list, "Add from Camera" in a note.
struct SlideCaptureMenu: View {
    @Bindable var capture: SlideCapture
    var addingToNote = false

    private var title: LocalizedStringKey { addingToNote ? "Add from Camera" : "Scan Slide" }

    var body: some View {
        Menu {
            #if os(iOS)
            if SlideCapture.canTakePhoto {
                Button { capture.showCamera = true } label: { Label("Take Photo of Slide", systemImage: "camera") }
            }
            Button { capture.showPhotoPicker = true } label: { Label("Choose from Photos", systemImage: "photo.on.rectangle") }
            #else
            Button { capture.showFileImporter = true } label: { Label("Choose Image File…", systemImage: "doc") }
            Button { capture.showPhotoPicker = true } label: { Label("Choose from Photos…", systemImage: "photo.on.rectangle") }
            Button { capture.pasteImage() } label: { Label("Paste Image", systemImage: "doc.on.clipboard") }
                .disabled(!SlideCapture.pasteboardHasImage)
            Divider()
            Text("To use your iPhone’s camera, choose File › Import from iPhone.")
            #endif
        } label: {
            Label(title, systemImage: "camera.viewfinder")
        } primaryAction: {
            #if os(iOS)
            if SlideCapture.canTakePhoto { capture.showCamera = true } else { capture.showPhotoPicker = true }
            #else
            capture.showFileImporter = true
            #endif
        }
        .accessibilityLabel(title)
        .accessibilityHint(addingToNote
                           ? "Photograph a sermon slide and add its text and passages to this note."
                           : "Photograph a sermon slide to start a note titled and linked from it.")
        .help(addingToNote ? "Add a sermon slide’s text to this note" : "Start a note from a photo of a sermon slide")
    }
}

extension View {
    /// Hosts every way of getting a slide in, and the review sheet that follows.
    /// `appendTo` is the note a new slide adds to by default (nil starts a new note).
    func slideCapture(_ capture: SlideCapture, appendTo note: Note? = nil, onSaved: @escaping (Note) -> Void) -> some View {
        modifier(SlideCaptureHost(capture: capture, appendTo: note, onSaved: onSaved))
    }
}

private struct SlideCaptureHost: ViewModifier {
    @Bindable var capture: SlideCapture
    let appendTo: Note?
    let onSaved: (Note) -> Void

    func body(content: Content) -> some View {
        content
            .photosPicker(isPresented: $capture.showPhotoPicker, selection: $capture.photoItem, matching: .images,
                          preferredItemEncoding: .current)
            .onChange(of: capture.photoItem) {
                Task { await capture.loadPickedPhoto() }
            }
            .fileImporter(isPresented: $capture.showFileImporter, allowedContentTypes: [.image]) { result in
                Task { await capture.importFile(result) }
            }
            #if os(iOS)
            .fullScreenCover(isPresented: $capture.showCamera) {
                SlideCameraView { image in
                    capture.showCamera = false
                    capture.accept(image)
                }
            }
            #else
            .importsItemProviders([.image]) { providers in capture.importProviders(providers) }
            #endif
            .sheet(item: $capture.slide) { slide in
                SlideReviewView(slide: slide, appendTo: appendTo, onSaved: onSaved)
                    #if os(macOS)
                    .frame(minWidth: 520, idealWidth: 580, minHeight: 620, idealHeight: 760)
                    #endif
            }
            .alert("Couldn’t Use That Image", isPresented: Binding(
                get: { capture.failure != nil },
                set: { if !$0 { capture.failure = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(capture.failure ?? "")
            }
    }
}
