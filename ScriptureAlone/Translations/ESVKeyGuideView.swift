import SwiftUI
import WebKit

/// Walks the reader through getting their own free ESV API key, on Crossway's real pages.
///
/// The reader does every part that is theirs: signing in or creating the account, agreeing to
/// Crossway's terms, the reCAPTCHA. The app only dims the page, points at the next thing to do,
/// fills in the facts about this app, and — when the key appears — saves it. See
/// `ESVKeyGuideScript`.
///
/// The web view is non-persistent: no Crossway session or cookie outlives the sheet.
struct ESVKeyGuideView: View {
    /// Called with a key Crossway accepted.
    let onKey: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var controller = ESVGuideController()
    @State private var checking = false

    var body: some View {
        NavigationStack {
            ESVGuideWebView(controller: controller)
                .ignoresSafeArea(edges: .bottom)
                .overlay {
                    if checking {
                        ProgressView("Checking your key…")
                            .padding(24)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
                    }
                }
                .navigationTitle("Get Your ESV Key")
                #if os(iOS)
                .navigationBarTitleDisplayMode(.inline)
                #endif
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                }
        }
        #if os(macOS)
        .frame(minWidth: 560, minHeight: 680)
        #endif
        .task {
            controller.onKey = { key in
                Task { @MainActor in
                    checking = true
                    let verdict = await ESVKeyCheck.validate(key)
                    checking = false
                    if verdict == false {
                        controller.keyRejected()
                    } else {
                        onKey(key)
                        dismiss()
                    }
                }
            }
            controller.load()
        }
    }
}

/// Asks Crossway once whether a key works, so a mis-scraped string is never saved.
enum ESVKeyCheck {
    /// `true` accepted, `false` rejected, `nil` could not tell (offline, server trouble).
    static func validate(_ key: String, session: URLSession = .shared) async -> Bool? {
        var components = URLComponents(string: "https://api.esv.org/v3/passage/text/")!
        components.queryItems = [
            .init(name: "q", value: "John 1:1"),
            .init(name: "include-passage-references", value: "false"),
            .init(name: "include-footnotes", value: "false"),
            .init(name: "include-headings", value: "false"),
        ]
        var request = URLRequest(url: components.url!)
        request.timeoutInterval = 15
        request.setValue("Token \(key)", forHTTPHeaderField: "Authorization")
        guard let (_, response) = try? await session.data(for: request),
              let http = response as? HTTPURLResponse else { return nil }
        switch http.statusCode {
        case 200..<300: return true
        case 401, 403: return false
        default: return nil
        }
    }
}

@MainActor
@Observable
final class ESVGuideController: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
    let webView: WKWebView
    @ObservationIgnored var onKey: (String) -> Void = { _ in }

    /// The email and password the reader chose for their new Crossway account, held only until the
    /// developer-site login has been filled in once. Never persisted, logged, or sent anywhere but
    /// back to Crossway's own sign-in.
    private var pendingLogin: (email: String, password: String)?

    /// Only Crossway's own sites may load as the page; anything else opens outside the sheet.
    private static func allowed(_ host: String?) -> Bool {
        guard let host else { return false }
        return host == "esv.org" || host.hasSuffix(".esv.org")
    }

    override init() {
        let configuration = WKWebViewConfiguration()
        configuration.websiteDataStore = .nonPersistent()
        let script = WKUserScript(source: ESVKeyGuideScript.source(),
                                  injectionTime: .atDocumentEnd, forMainFrameOnly: true)
        configuration.userContentController.addUserScript(script)
        webView = WKWebView(frame: .zero, configuration: configuration)
        super.init()
        configuration.userContentController.add(WeakHandler(self), name: "guide")
        webView.navigationDelegate = self
    }

    func load() {
        guard webView.url == nil else { return }
        webView.load(URLRequest(url: URL(string: ESVKeyGuideScript.createApplicationURL)!))
    }

    func keyRejected() {
        webView.evaluateJavaScript("window.__saGuide && window.__saGuide.badKey()")
    }

    func userContentController(_ controller: WKUserContentController,
                               didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let event = body["event"] as? String else { return }
        switch event {
        case "key":
            if let key = body["value"] as? String { onKey(key) }
        case "creds":
            if let email = body["email"] as? String, let password = body["password"] as? String,
               !email.isEmpty, !password.isEmpty { pendingLogin = (email, password) }
        case "skip":
            pendingLogin = nil
            // Stop injecting on later pages too.
            webView.configuration.userContentController.removeAllUserScripts()
        default:
            break
        }
    }

    /// After the reader registers, carry them to the developer site and sign them in, so the one
    /// account works in both places without typing it twice.
    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        guard let creds = pendingLogin, let url = webView.url, let host = url.host else { return }
        let path = url.path
        if (host == "www.esv.org" || host == "esv.org"),
           path != "/account/register/", path != "/account/register" {
            // A registration that failed validation re-shows the form; leaving it means it worked.
            webView.load(URLRequest(url: URL(string: "https://api.esv.org/login/?next=/account/create-application/")!))
        } else if host == "api.esv.org", path.hasPrefix("/login") {
            // One attempt only: a wrong password must not loop.
            pendingLogin = nil
            let arguments = (try? JSONSerialization.data(withJSONObject: [creds.email, creds.password]))
                .flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
            webView.evaluateJavaScript("window.__saGuide && window.__saGuide.fillLogin(...\(arguments))")
        }
    }

    func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction) async -> WKNavigationActionPolicy {
        guard action.targetFrame?.isMainFrame ?? true, let url = action.request.url else { return .allow }
        if url.scheme == "about" || Self.allowed(url.host) { return .allow }
        if url.scheme == "https" || url.scheme == "mailto" {
            #if os(iOS)
            await UIApplication.shared.open(url)
            #else
            NSWorkspace.shared.open(url)
            #endif
        }
        return .cancel
    }
}

/// `WKUserContentController` retains its handlers, so a direct one would keep the controller —
/// and through it the web view — alive for the life of the app.
private final class WeakHandler: NSObject, WKScriptMessageHandler {
    private weak var target: (any WKScriptMessageHandler)?
    init(_ target: any WKScriptMessageHandler) { self.target = target }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}

#if os(macOS)
private struct ESVGuideWebView: NSViewRepresentable {
    let controller: ESVGuideController
    func makeNSView(context: Context) -> WKWebView { controller.webView }
    func updateNSView(_ view: WKWebView, context: Context) {}
}
#else
private struct ESVGuideWebView: UIViewRepresentable {
    let controller: ESVGuideController
    func makeUIView(context: Context) -> WKWebView { controller.webView }
    func updateUIView(_ view: WKWebView, context: Context) {}
}
#endif
