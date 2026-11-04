import Foundation

/// The coach layer injected into Crossway's own pages while the reader gets their ESV key.
///
/// It dims the page, cuts a glowing spotlight around the one thing to do next, blocks taps
/// everywhere else, and explains the step on a card. It fills in the facts about this app, and
/// nothing else: the reader's name, email, password, the terms checkbox and the reCAPTCHA stay
/// theirs. When the key appears on the result page it hands it to the app.
///
/// It reads the page by field name and label rather than by position, and when it cannot
/// recognise a page it shows a card with a single "Continue" button instead of guessing.
enum ESVKeyGuideScript {
    static let createApplicationURL = "https://api.esv.org/account/create-application/"
    static let registerURL = "https://www.esv.org/account/register/"

    /// Words for the card, localised on the native side and handed to the page as JSON.
    static func strings() -> [String: String] {
        [
            "skip": String(localized: "Skip guide", comment: "Button on the ESV key setup guide that turns the highlighting off."),
            "emailTitle": String(localized: "Your email", comment: "ESV key setup guide step title."),
            "emailBody": String(localized: "Type the email address of your free ESV.org account.", comment: "ESV key setup guide."),
            "passTitle": String(localized: "Your password", comment: "ESV key setup guide step title."),
            "passBody": String(localized: "Type your ESV.org password. Your password manager can fill it in.", comment: "ESV key setup guide."),
            "signInTitle": String(localized: "Sign in", comment: "ESV key setup guide step title."),
            "tapThis": String(localized: "Tap the glowing button.", comment: "ESV key setup guide."),
            "needAccount": String(localized: "I need an account", comment: "ESV key setup guide button."),
            "fillTitle": String(localized: "Fill this in", comment: "ESV key setup guide step title."),
            "fillBody": String(localized: "Complete “%@”.", comment: "ESV key setup guide. %@ is a form field name."),
            "tosTitle": String(localized: "Agree to the terms", comment: "ESV key setup guide step title."),
            "tosBody": String(localized: "Read Crossway's terms, then tick the box. That choice is yours.", comment: "ESV key setup guide."),
            "captchaTitle": String(localized: "Prove you're human", comment: "ESV key setup guide step title."),
            "captchaBody": String(localized: "Crossway asks for this check. Solve it, then carry on.", comment: "ESV key setup guide."),
            "submitTitle": String(localized: "Almost there", comment: "ESV key setup guide step title."),
            "submitBody": String(localized: "Everything is filled in. Tap the glowing button.", comment: "ESV key setup guide."),
            "verifyTitle": String(localized: "Next: the developer site", comment: "ESV key setup guide step title."),
            "verifyBody": String(localized: "Your ESV.org account is ready. Tap Continue and sign in again on Crossway's developer site, where keys are made. If Crossway emailed you a confirmation link, open it first.", comment: "ESV key setup guide."),
            "continue": String(localized: "Continue", comment: "ESV key setup guide button."),
            "signingTitle": String(localized: "Signing you in", comment: "ESV key setup guide step title."),
            "signingBody": String(localized: "Using the email and password you just created. Nothing is saved.", comment: "ESV key setup guide."),
            "keyTitle": String(localized: "Found your key", comment: "ESV key setup guide step title."),
            "keyBody": String(localized: "Saving it to this device…", comment: "ESV key setup guide."),
            "badKeyTitle": String(localized: "That key didn't work", comment: "ESV key setup guide step title."),
            "badKeyBody": String(localized: "Crossway didn't accept it. Tap Try again, or copy the key by hand from this page.", comment: "ESV key setup guide."),
            "retry": String(localized: "Try again", comment: "ESV key setup guide button."),
            // These three go to Crossway as the application's own details, so they stay in English.
            "appName": "Scripture Alone (personal use)",
            "appURL": "https://wemiller.com/apps/scripture-alone/",
            "appDescription": "Personal Bible reading in Scripture Alone, a free, non-commercial, open-source Bible app. The key is mine alone and stays on my devices.",
            "createURL": createApplicationURL,
            "registerURL": registerURL,
        ]
    }

    static func source() -> String {
        let json = (try? JSONSerialization.data(withJSONObject: strings()))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        return "window.__saT = \(json);\n" + body
    }

    /// The script itself is `Shared/ESVKeyGuide.js`, shared with Android.
    private static let body: String = {
        guard let url = Bundle.main.url(forResource: "ESVKeyGuide", withExtension: "js"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return "" }
        return text
    }()
}
