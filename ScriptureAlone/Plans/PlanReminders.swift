import Foundation
import ScriptureAloneCore
import UserNotifications

/// Daily reminders to read. A repeating notification can't say what today's reading is, so the next
/// two weeks are scheduled one by one — each naming its passage — and topped up whenever the app
/// opens or progress changes. A day already read gets no reminder.
enum PlanReminders {
    private static let prefix = "plan."
    private static let horizon = 14

    /// Asks permission (once; the system remembers) and reports whether reminders can be delivered.
    static func requestAuthorization() async -> Bool {
        (try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])) ?? false
    }

    /// Replaces every pending plan reminder with the next `horizon` days' worth.
    @MainActor
    static func refresh(_ store: PlanStore, now: Date = Date(), center: UNUserNotificationCenter? = nil) {
        guard store.schedulesReminders || center != nil else { return }
        let center = center ?? UNUserNotificationCenter.current()
        let requests = requests(for: store, now: now)
        Task {
            let pending = await center.pendingNotificationRequests()
            center.removePendingNotificationRequests(withIdentifiers: pending.map(\.identifier).filter { $0.hasPrefix(prefix) })
            for request in requests { try? await center.add(request) }
        }
    }

    @MainActor
    static func requests(for store: PlanStore, now: Date, calendar: Calendar = .current) -> [UNNotificationRequest] {
        var result: [UNNotificationRequest] = []
        for progress in store.progress {
            guard let time = progress.reminder, let schedule = store.schedule(for: progress.planID) else { continue }
            for offset in 0..<horizon {
                guard let date = calendar.date(byAdding: .day, value: offset, to: calendar.startOfDay(for: now)),
                      let index = schedule.dayIndex(on: date), !progress.completed.contains(index),
                      let fire = calendar.date(bySettingHour: time.hour, minute: time.minute, second: 0, of: date),
                      fire > now else { continue }
                let day = schedule.plan.days[index]
                let content = UNMutableNotificationContent()
                content.title = schedule.plan.displayTitle
                content.body = String(localized: "Today’s reading: \(day.display)",
                                      comment: "Reading plan reminder. %@ is a passage like Romans 8.")
                content.sound = .default
                content.userInfo = ["plan": progress.planID]
                let parts = calendar.dateComponents([.year, .month, .day, .hour, .minute], from: fire)
                result.append(UNNotificationRequest(
                    identifier: "\(prefix)\(progress.planID).\(PlanSchedule.isoDay(date, calendar: calendar))",
                    content: content, trigger: UNCalendarNotificationTrigger(dateMatching: parts, repeats: false)))
            }
        }
        return result
    }
}
