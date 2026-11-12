# LifeTrack 1.7.0

A complete native Android habit tracker using Kotlin, Jetpack Compose Material 3, Room, MVVM, a repository and Hilt. All user records and settings are local. There is no account, Internet permission, analytics SDK, advertising SDK, server or automatic app-data cloud backup.

Open this directory directly in Android Studio. The supplied screenshots were visual references; no third-party branding or app code was copied. The native Android app is canonical. The accompanying localhost browser preview is a separate implementation with separate browser storage; it neither runs the APK nor shares the Android database.

## Build, install and upgrade

1. Install Android Studio, JDK 17, Android SDK Platform 36 and Build Tools 35.0.0. Select JDK 17 as the Gradle JDK. The project pins Android Gradle Plugin 8.10.1, Gradle 8.11.1 and Kotlin 2.1.21.
2. Open this directory, sync Gradle and Run `app` on Android 8.0 / API 26 or later.
3. Run `./gradlew assembleDebug testDebugUnitTest lintDebug` (Windows: `gradlew.bat assembleDebug testDebugUnitTest lintDebug`). The first build downloads development dependencies; the installed app works offline.
4. The APK is `app/build/outputs/apk/debug/app-debug.apk`. Install through Android Studio or `adb install -r app/build/outputs/apk/debug/app-debug.apk`. Updating an existing installation requires the same package and signing certificate. Read [UPGRADE.md](UPGRADE.md); do not uninstall or clear storage to work around incompatible signatures.
5. For publishing, use Android Studio **Build → Generate Signed App Bundle / APK** and your own release signing key. Release builds enable R8 and resource shrinking. Keep signing keys/passwords out of distributed source. Future updates need the same release key. Changing `applicationId` creates a separate app with separate data. Provide store artwork/privacy policy and review current store requirements.

This release uses Android version code 8 and Room database schema 4. Activities has its own ledger, and Settings links to Schedules & Reminders. The implementation notes below describe the original architecture.

## Step 1 — Project setup

`settings.gradle.kts`, root `build.gradle.kts`, `gradle.properties`, `gradle/wrapper/` and `app/build.gradle.kts` contain the full configuration. Both wrapper scripts and the official wrapper binary are included. Dependencies are pinned: Compose BOM, Room/KSP, Hilt, Navigation, Kotlin Serialization, WorkManager, Glance, Vico and Reorderable. DataStore's preference dependency is included as requested; **Room is the canonical preference store** so settings restore atomically with user records.

`LifeTrackApplication.kt` observes local changes and synchronizes widget/reminder work. `di/AppModule.kt` provides Room and registers the migration. `MainActivity.kt` hosts Compose and notification navigation.

The app declares `VIBRATE` and `POST_NOTIFICATIONS`. The merged manifest also contains WorkManager's normal `WAKE_LOCK`, `ACCESS_NETWORK_STATE`, `RECEIVE_BOOT_COMPLETED` and `FOREGROUND_SERVICE` permissions, plus the generated signature-protected dynamic-receiver permission. Network-state observation does not grant Internet access. There is no `INTERNET`, broad storage or exact-alarm permission. See [PRIVACY.md](PRIVACY.md).

## Step 2 — Database

`data/Models.kt`, `HabitDao.kt`, `AppDatabase.kt` and `DatabaseMigrations.kt` implement schema version 2. Dates are local epoch days in SQLite and ISO 8601 in JSON; timestamps are epoch milliseconds. Exported Room schemas 1 and 2 are in `app/schemas/`. The registered `MIGRATION_1_2` preserves existing data; no destructive fallback is enabled.

| Table | Fields and relationships |
| --- | --- |
| `habits` | Auto-generated Long ID, name/description/icon/color, start date, archive flag/date, order/category/timestamps; indexed nullable `afterHabitId` self-reference with SET NULL; scheduled weekdays, effective-date schedule history, integer target/unit and optional reminder time |
| `habit_logs` | Auto-generated ID, cascading habit reference, date, COMPLETED / SKIPPED / FAILED / IN_PROGRESS, numeric progress, note, frozen flag/timestamps; unique `(habitId, date)` and date index |
| `habit_pauses` | Cascading habit reference, inclusive start and nullable exclusive end; unique `(habitId, startDate)`; an open interval represents the current archive |
| `freeze_usage` | Calendar-month primary key and redemption timestamp; survives habit deletion |
| `app_settings` | Singleton theme/dynamic color, haptic/animation preferences, monthly reminder opt-in and last successful export timestamp |
| `reminder_deliveries` | Operational once-per-day ledger keyed by `(habitId, date)` with timestamp and cascading reference; excluded from manual backups |

Migration makes old daily habits scheduled all seven weekdays with target 1. Old COMPLETED logs receive progress 1; other statuses receive progress 0. IDs, notes, timestamps, stacks, order, settings and freezes remain. Existing archived habits receive an open pause from archive date + 1. Earlier archive/restore intervals never stored by version 1 cannot be reconstructed.

Stacks cannot form cycles. Deleting a parent detaches linked habits; deleting a habit cascades its logs, pauses and delivery ledger, including when it was just created without logs.

## Step 3 — Repository and ViewModel

`data/HabitRepository.kt` is the write boundary for screens, widgets, notifications and files. Transactions protect daily upserts, numeric increments, exact undo, ordering, freezes, deletion and backup replacement. Future check-ins, pre-start dates, unscheduled weekdays, recorded pause dates and edits to archived check-ins are rejected. A start date cannot move after an existing check-in.

**Any saved log protects its habit's target and unit**, including skipped, failed and partial entries. The editor explains this and the repository enforces it. Create a new habit for a different goal; logged quantities are never rewritten to match a new target. Other habit settings remain editable under their validation rules.

`ui/HabitViewModel.kt` exposes `StateFlow<LifeTrackState>`, loading/error and busy states, and committed UI events. Writes are serialized and failed saves retain drafts. Today updates on resume and every 30 seconds while the process lives. `domain/Analytics.kt` contains pure rules; due-day counting uses arithmetic intervals rather than allocating every historical date.

## Step 4 — Reusable components

- `ui/components/HabitCard.kt`: 16 dp rounded bordered card, quantity progress and animated `CheckInButton` with accessible state/long press.
- `HeatmapWidget.kt`: scrollable percentage-density grid, legend, selected-day details and accessible previous/next controls.
- `CompletionTrend.kt`: interactive Vico rolling-rate chart and accessible exact-date/value slider.
- `UiPreferences.kt`: shared haptic/motion controls respected by check-in/chart feedback.
- `ui/DateSelection.kt`: Material date picker; future check-ins are unavailable, future habit starts are allowed.

The visual style uses whitespace, readable type and subtle borders. Dynamic wallpaper colors work on Android 12+, with fixed light/dark fallback. Separate Settings switches control haptics and animations. When enabled, completion fills/scales the circle and relative Days 7, 30, 100 and 365 trigger subtle confetti. Analytics cards adapt for narrow widths and larger text.

## Step 5 — Main screens

`ui/LifeTrackApp.kt` supplies Today / Analytics / Settings navigation, detail/editor routes, snackbars, guarded undo and celebration effects.

- **Today:** create habits or use templates; search text and filter category or Due / Completed / Rest & planned status; select past dates; complete or increment quantities. Long press edits status/notes. Drag to reorder, or use the handle menu / screen-reader Move up/down actions. Future/rest-day check-ins are disabled. Empty states explain next actions.
- **Editor:** name, description, emoji/icon, category/color, start date, weekdays, target/unit, inexact local reminder and optional stack. `HabitTemplates.kt` supplies reading, movement, stillness, water and writing suggestions. Stacking is a cue, not a prerequisite. Unsaved edits prompt before discarding.
- **Detail:** current/best streak, total completions, quantities/status/notes, backfill, freeze, Vico trend, whole-routine heatmap and archive/restore. Permanent deletion first warns, then requires typing `DELETE` separately. Dialogs retain failed drafts and expose inline errors.
- **Analytics:** heatmap, metrics, Today/Week/Month rings, matched elapsed-period You vs. You, category donut, eight-week bars with exact-value slider and streak leaderboard. All statistics respect effective schedules and recorded pauses.
- **Settings:** normal/encrypted JSON export/import, last backup, monthly reminder, theme/dynamic color, haptic/motion controls, archived habits, About and uninstall warning.

### Tracking rules

- Day 1 is the start date; day numbers count calendar days including rest/pause dates. Future habits show their start date. The phone's current local calendar defines today; DST does not change day counts.
- Select one or more weekdays. Edits take effect today and preserve earlier schedules in effective-date history. This supports weekdays, rather than arbitrary every-N-days or weekly quotas.
- Progress below target is IN_PROGRESS; reaching target is COMPLETED. An incomplete today has until midnight and keeps yesterday's streak. A missing, FAILED or partial **past due day** breaks the chain.
- Rest days, recorded pauses, SKIPPED and frozen days bridge streaks without adding completed days. Rates exclude skips. Heatmap density uses completed / **all** scheduled, unpaused eligible habits, including skips. Partial amounts are visible but do not count as completions. No-opportunity periods show a dash/explanation.
- Archive preserves that day's history and opens a pause on **archive date + 1**. Restore closes it at the **restore date, exclusive**, so restore day is active when scheduled. Same-day archive/restore removes an empty interval. Recorded pause dates stay excluded after restore and cannot be backfilled; the relative day count continues.
- One freeze per **target calendar month across the app** can protect a past due missing, FAILED or IN_PROGRESS day by making it frozen SKIPPED. Editing/deleting the log or habit does not refund the month. Unlimited explicit skips are separate.
- Undo restores the exact previous log, including note, amount and frozen state, only if the habit identity and current saved log still match that operation. A newer edit, replacement import, archive or changed eligibility can reject undo instead of overwriting data. It never refunds a spent freeze.
- Future days are excluded. Category charts use the current category for past completions. Weeks start Monday; comparisons match elapsed weekdays. Months match elapsed days, clamped to the previous month's length. Missing comparable history is explained.

## Step 6 — Export and import

`data/Backup.kt` defines JSON data format version 2, dates, version-1 conversion and strict validation. `HabitRepository.kt` captures a consistent transaction snapshot, writes UTF-8 off the main thread and updates the last-export timestamp only after the output stream successfully closes. Files include habits/IDs, logs/quantities, notes/timestamps, order/stacks, schedule history, pauses, settings and freeze usage. The operational reminder ledger is excluded and resets on import.

Android's Storage Access Framework starts the create-file picker in Downloads when supported; you choose the actual location. No broad storage permission is needed. Google Drive or another provider is involved only if you select it in the system picker; LifeTrack never connects to it itself. See [SAF documentation](https://developer.android.com/training/data-storage/shared/documents-files).

Plain, readable JSON remains available. `data/BackupCrypto.kt` optionally wraps it in an authenticated encrypted JSON envelope:

| Property | Format |
| --- | --- |
| Wrapper | `kind = "lifetrack-encrypted"`, `version = 1`; distinct from inner data format version 2 |
| Cipher | AES-256-GCM, 128-bit authentication tag, fresh random salt 16 bytes / IV 12 bytes per export |
| Derivation | PBKDF2-HMAC-SHA256; new exports 600,000 iterations, accepted decryption range 210,000–600,000 |
| Authentication | Ciphertext and canonical envelope metadata; changed files / wrong passwords are rejected |
| Limits | Plain/decrypted JSON 10 MiB (10 × 1,024 × 1,024 bytes), encrypted envelope 15 MiB; sizes and KDF bounds checked before expensive processing |

Encrypted export requires at least 10 password characters. Passwords are never persisted and cannot be recovered; keep a separate safe record. File encryption does **not** encrypt the local SQLite database, which resides in Android private app storage. This feature is not a security certification.

Import confirms replacement, accepts normal version-1 JSON and upgrades it in memory; new exports use version 2. Encrypted files decrypt before the same checks. UTF-8 BOMs are accepted. Required fields, versions, enums, dates, progress/target consistency, colors, timestamps, IDs, daily uniqueness, relationships, cycles, pause intervals and freezes are checked **before deleting anything**. Bad passwords, future logs, malformed/unsupported/oversized files are rejected. All user records replace the database in one transaction. Invalid input or a failed transaction leaves the existing database intact. Import neither merges databases nor modifies the source file.

Automatic app-data cloud/device-transfer backups exclude records through manifest and legacy / Android 12+ extraction rules; see [Android backup controls](https://developer.android.com/identity/data/autobackup). Uninstall or clearing storage deletes local data. Export before changing devices or removing the app; verify recovery while the original remains available.

## Step 7 — Widget and local reminders

`widget/LifeTrackWidget.kt` / `WidgetRefreshWorker.kt` provide a Glance widget of today's active scheduled habits. It reads one consistent Room snapshot using the indexed **today's logs** query rather than all history, and keeps no second habit database. The title opens the app. Check in completes the **whole target**, atomically and idempotently preserving its latest note; use the app for partial amounts. Archived/future/rest/paused habits are excluded.

Actions verify the displayed date and habit creation identity to reject stale midnight or replacement-import buttons. Database changes and periodic work refresh widgets; Android can defer background work, and opening the app refreshes promptly. Launcher appearance/resize still need device QA.

`reminder/HabitReminderWorker.kt` scans roughly every **15 minutes** when reminders are enabled. After the chosen local time, an incomplete habit on a scheduled, unpaused day can notify once per habit/day through a transactional ledger. Posting failure or newly denied permission releases the claim for retry; an old worker cannot release a replacement habit's claim. Tap opens the habit; Complete finishes its full goal, preserving notes. Actions guard stale dates/identities. Notifications request private lock-screen visibility with generic public text; device/user settings determine actual visibility.

`reminder/BackupReminderWorker.kt` schedules one opt-in unique local 30-day worker. Recent exports suppress it; disabling cancels it; tapping opens Settings. Android 13+ requests notification permission when a reminder feature is enabled; denial does not block habit saving/check-in. No exact alarm or network operation is used. Android Doze, battery-saving and manufacturer policies may delay or suppress both reminders; they are **inexact prompts**, not exact-time alarms.

## Source map

Kotlin source root: `app/src/main/java/com/lifetrack/app/`.

| Area | Files |
| --- | --- |
| Application/injection | `LifeTrackApplication.kt`, `MainActivity.kt`, `di/AppModule.kt` |
| Persistence/backup | `data/Models.kt`, `HabitDao.kt`, `AppDatabase.kt`, `DatabaseMigrations.kt`, `HabitRepository.kt`, `Backup.kt`, `BackupCrypto.kt` |
| Rules | `domain/Analytics.kt` |
| Navigation/screens | `ui/LifeTrackApp.kt`, `HabitViewModel.kt`, `TodayScreen.kt`, `HabitEditorScreen.kt`, `HabitDetailScreen.kt`, `AnalyticsScreen.kt`, `SettingsScreen.kt` |
| Shared UI | `ui/Theme.kt`, `DateSelection.kt`, `HabitTemplates.kt`, `ui/components/HabitCard.kt`, `HeatmapWidget.kt`, `CompletionTrend.kt`, `UiPreferences.kt` |
| Widget/reminders | `widget/LifeTrackWidget.kt`, `WidgetRefreshWorker.kt`; `reminder/HabitReminderWorker.kt`, `ReminderPolicy.kt`, `BackupReminderWorker.kt` |
| Android/resources | `app/src/main/AndroidManifest.xml`, `app/src/main/res/` including themes/icons, widget metadata and backup rules |
| Schemas/tests | `app/schemas/`, `app/src/test/java/com/lifetrack/app/` |

## Validation and release review

[VALIDATION.md](VALIDATION.md) records executed checks and artifact hashes. Final upgraded-source verification is recorded there when completed; an earlier build or a newly written test is not automatically a passing final run. The accompanying [evaluation](../LifeTrack-evaluation/senior-review.md) and [market research](../LifeTrack-evaluation/market-research.md) explain the fixed criteria and evidence limits. Scores are expert judgments of the inspected scope, not measured reliability or release certification.

JVM rules/JSON/encryption and Room/Compose integration tests run through `gradlew.bat testDebugUnitTest`. Build/lint with `gradlew.bat assembleDebug lintDebug bundleRelease lintRelease`. Browser tests validate only the preview.

Before public release, check physical devices: TalkBack/keyboard navigation, large text/narrow screens, themes, date/time-zone changes, haptics/motion, quantity/notes/undo, schedules, archive/restore/delete, reorder, SAF failures/cancellation, plain/encrypted backup recovery, same-key installed upgrades, widgets/resize, notification denial/actions/timing under idle restrictions. Keep backups/signing keys safe and review current Play requirements. See [PRIVACY.md](PRIVACY.md), [UPGRADE.md](UPGRADE.md) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
