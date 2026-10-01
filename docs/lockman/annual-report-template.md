<!--
  The yearly usage report owed to The Lockman Foundation under both NASB Distribution Permission
  Agreements (NASB 2020 and NASB 1995, effective October 1, 2026):

    "Within 60 days after each anniversary of the effective date … REQUESTER shall provide LOCKMAN
     with a report stating the number of copies of THE LICENSED WORKS containing THE UNDERLYING
     WORKS distributed during the preceding year. For electronic distribution, the report must also
     include the number of downloads, views, streams, users, or other applicable usage measures."

  Due: between October 1 and November 30 each year, for the twelve months ending September 30.

  scripts/lockman-report.mjs fills every {{PLACEHOLDER}} it can from App Store Connect and Google
  Play, and leaves the rest as "—" for you to fill in. The lockman-report workflow runs it every
  October 10 and attaches the filled-in copy to the run. To change the wording, edit this file;
  the script only substitutes placeholders and never rewrites the text around them.
-->

**To:** Margarita, The Lockman Foundation
**Subject:** Scripture Alone Bible: annual NASB distribution report, {{PERIOD_LABEL}}

Hello Margarita,

Here is the annual distribution report for Scripture Alone Bible, under our Distribution Permission
Agreements for the NASB 2020 and the NASB 1995 (effective October 1, 2026).

**Reporting period:** {{PERIOD_START}} to {{PERIOD_END}}
**NASB first available in the app:** {{NASB_AVAILABLE_FROM}}
**Counted from:** {{COUNTED_FROM}} to {{PERIOD_END}}

| Measure | Apple App Store (iPhone, iPad, Mac, Apple Watch) | Google Play (Android) | Total |
|---|---:|---:|---:|
| New downloads (first-time installs) | {{APPLE_DOWNLOADS}} | {{PLAY_DOWNLOADS}} | {{TOTAL_DOWNLOADS}} |
| Re-downloads by existing users | {{APPLE_REDOWNLOADS}} | n/a | {{APPLE_REDOWNLOADS}} |
| Updates installed | {{APPLE_UPDATES}} | {{PLAY_UPDATES}} | {{TOTAL_UPDATES}} |
| Active installs at period end | {{APPLE_ACTIVE}} | {{PLAY_ACTIVE}} | {{TOTAL_ACTIVE}} |

**Copies of the licensed works distributed during the period:** {{TOTAL_DOWNLOADS}} new downloads.
Re-downloads and updates are listed separately above.

**Website** (https://wemiller.com/apps/scripture-alone/): {{WEB_USAGE}}

**Notes on the figures**
- The figures come from the app stores' own reporting (App Store Connect Sales and Trends, and the
  Google Play Console installs report). Scripture Alone has no analytics, accounts or tracking of
  its own, so this report contains no reader data and no figures by verse, book or feature.
- The NASB is included in every copy of the app distributed during the counted period, and is
  available free of charge with no access fee or membership.
{{EXTRA_NOTES}}

Thank you again for permitting the NASB in Scripture Alone. If you would like these figures broken
down any differently, I'm happy to provide that.

Sincerely,
Blaine Miller
Scripture Alone Bible
