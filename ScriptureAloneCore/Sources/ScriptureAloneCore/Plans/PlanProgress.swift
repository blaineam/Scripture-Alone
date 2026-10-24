import Foundation

/// A reader's place in one plan: when they started, and which days they have done.
public struct PlanProgress: Codable, Hashable, Sendable {
    public var planID: String
    /// The first day of the plan, "yyyy-MM-dd".
    public var startDate: String
    /// Follow the dates the source gave the days (an imported plan) rather than counting from `startDate`.
    public var followsCalendar: Bool
    public var completed: Set<Int>
    /// A daily reminder at this local time, when set.
    public var reminder: ReminderTime?

    public struct ReminderTime: Codable, Hashable, Sendable {
        public var hour: Int
        public var minute: Int
        public init(hour: Int, minute: Int) {
            self.hour = hour
            self.minute = minute
        }
    }

    public init(planID: String, startDate: String, followsCalendar: Bool = false, completed: Set<Int> = [],
                reminder: ReminderTime? = nil) {
        self.planID = planID
        self.startDate = startDate
        self.followsCalendar = followsCalendar
        self.completed = completed
        self.reminder = reminder
    }
}

/// What a plan has for a given day.
public enum PlanToday: Equatable, Sendable {
    /// Not started: the first day is on or after this date.
    case notStarted(starts: String)
    /// A day to read.
    case reading(PlanDay)
    /// No reading on this date (a weekend, a gap in the source's calendar). `next` is the day coming.
    case rest(next: PlanDay?)
    /// Every day has been scheduled and today is past the last.
    case finished
}

/// Dates and tallies for a plan and a reader's progress in it. Pure: the clock and the calendar are
/// arguments, so every rule is testable on a fixed date.
public struct PlanSchedule: Sendable {
    public let plan: ReadingPlan
    public let progress: PlanProgress
    private let calendar: Calendar

    public init(plan: ReadingPlan, progress: PlanProgress, calendar: Calendar = .current) {
        self.plan = plan
        self.progress = progress
        var calendar = calendar
        calendar.locale = Locale(identifier: "en_US_POSIX")
        self.calendar = calendar
    }

    // MARK: Dates

    public static func isoDay(_ date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    private func date(iso: String) -> Date? {
        let parts = iso.prefix(10).split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3 else { return nil }
        return calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))
    }

    private func isoDay(_ date: Date) -> String { Self.isoDay(date, calendar: calendar) }

    private var follows: Bool { progress.followsCalendar && plan.isDated }

    private var start: Date? { date(iso: progress.startDate) }

    // An annual plan's days are "MM-DD" in the year of the start date.
    private func monthDay(_ date: Date) -> String {
        let c = calendar.dateComponents([.month, .day], from: date)
        return String(format: "%02d-%02d", c.month ?? 0, c.day ?? 0)
    }

    private func annualDate(_ monthDay: String) -> Date? {
        let parts = monthDay.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 2, let year = start.map({ calendar.component(.year, from: $0) }),
              let date = calendar.date(from: DateComponents(year: year, month: parts[0], day: parts[1])),
              calendar.component(.month, from: date) == parts[0] else { return nil } // February 29 in a common year
        return date
    }

    // MARK: Which day falls when

    /// The calendar date of `day`, counting from the start date and skipping rest weekdays; or, when
    /// following the source's calendar, the date the source gave it.
    public func date(forDay index: Int) -> Date? {
        guard plan.days.indices.contains(index) else { return nil }
        if plan.annual { return plan.days[index].date.flatMap(annualDate) }
        if follows { return plan.days[index].date.flatMap(date(iso:)) }
        guard var cursor = start else { return nil }
        var remaining = index
        for _ in 0..<(plan.days.count * 7 + 14) {
            if !plan.skipWeekdays.contains(calendar.component(.weekday, from: cursor)) {
                if remaining == 0 { return cursor }
                remaining -= 1
            }
            guard let next = calendar.date(byAdding: .day, value: 1, to: cursor) else { return nil }
            cursor = next
        }
        return nil
    }

    /// The day scheduled for `date`, or nil on a rest day, before the start or after the end.
    public func dayIndex(on date: Date) -> Int? {
        let day = calendar.startOfDay(for: date)
        guard let start = self.start, day >= start else { return nil }
        if plan.annual {
            let key = monthDay(day)
            return plan.days.first { $0.date == key }?.index
        }
        if follows {
            let iso = isoDay(day)
            return plan.days.first { $0.date == iso }?.index
        }
        guard !plan.skipWeekdays.contains(calendar.component(.weekday, from: day)) else { return nil }
        var count = 0
        var cursor = start
        while cursor < day {
            if !plan.skipWeekdays.contains(calendar.component(.weekday, from: cursor)) { count += 1 }
            guard let next = calendar.date(byAdding: .day, value: 1, to: cursor) else { return nil }
            cursor = next
            if count >= plan.days.count { return nil }
        }
        return count < plan.days.count ? count : nil
    }

    public func today(now: Date = Date()) -> PlanToday {
        let day = calendar.startOfDay(for: now)
        guard let start = self.start, !plan.days.isEmpty else { return .finished }
        if day < start { return .notStarted(starts: progress.startDate) }
        if let index = dayIndex(on: day) { return .reading(plan.days[index]) }
        if let last = date(forDay: plan.days.count - 1), day > last { return .finished }
        // A rest day: the next day is the first one scheduled after today.
        let next = plan.days.first { date(forDay: $0.index).map { $0 > day } ?? false }
        return .rest(next: next)
    }

    // MARK: Tallies

    /// Days whose date has passed without being read, oldest first.
    public func missedDays(now: Date = Date()) -> [PlanDay] {
        // A devotional for each date of the year is read on its day or not at all: nothing to catch up on.
        if plan.annual { return [] }
        let day = calendar.startOfDay(for: now)
        return plan.days.filter { d in
            guard !progress.completed.contains(d.index), let when = date(forDay: d.index) else { return false }
            return when < day && when >= (start ?? when)
        }
    }

    public var completedCount: Int { progress.completed.filter { plan.days.indices.contains($0) }.count }

    public var fraction: Double {
        plan.days.isEmpty ? 0 : Double(completedCount) / Double(plan.days.count)
    }

    /// Consecutive scheduled days read, ending with today's — or yesterday's, while today's is still
    /// open, so a streak survives until a day is actually missed.
    public func streak(now: Date = Date()) -> Int {
        let day = calendar.startOfDay(for: now)
        var scheduled: [Int] = []
        for d in plan.days {
            guard let when = date(forDay: d.index), when <= day else { continue }
            scheduled.append(d.index)
        }
        var count = 0
        var rest = scheduled[...]
        if let last = rest.last, !progress.completed.contains(last), date(forDay: last).map({ $0 == day }) == true {
            rest = rest.dropLast()
        }
        for index in rest.reversed() {
            guard progress.completed.contains(index) else { break }
            count += 1
        }
        return count
    }

    /// What "play today's reading" should read: today's day when there is one, else the oldest day
    /// missed, else the next day to come. Nil only for a plan with nothing left.
    public func reading(now: Date = Date()) -> PlanDay? {
        switch today(now: now) {
        case .reading(let day): return day
        case .rest(let next): return missedDays(now: now).first ?? next
        case .notStarted: return plan.days.first
        case .finished: return missedDays(now: now).first
        }
    }
}

extension PlanProgress {
    /// An annual plan starts over each January: the same progress begun on January 1 of `now`'s year with
    /// nothing read yet, when it was begun in an earlier year. Nil when there is nothing to roll over.
    public func rolledOver(for plan: ReadingPlan, now: Date, calendar: Calendar = .current) -> PlanProgress? {
        guard plan.annual else { return nil }
        let year = calendar.component(.year, from: now)
        guard let started = Int(startDate.prefix(4)), started < year else { return nil }
        var next = self
        next.startDate = String(format: "%04d-01-01", year)
        next.completed = []
        return next
    }
}
