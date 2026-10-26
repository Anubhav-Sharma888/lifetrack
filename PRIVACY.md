# LifeTrack privacy

Applies to LifeTrack 1.1.0, the native Android application.

LifeTrack works without an account or Internet connection. Habits, descriptions, check-ins, numeric progress, notes, links, weekday schedules and their history, recorded archive pauses, freezes, reminder settings and preferences remain in a Room SQLite database in Android private app storage. A local delivery ledger prevents duplicate daily habit reminders. The app does not add its own database encryption; Android private storage and device security protect the local database.

There is no `INTERNET` permission, advertising, analytics SDK, login, remote database or cloud synchronization. Habit data is not sent to a developer-operated service. Manifest and backup rules disable automatic app-data cloud backup and device-transfer backup.

## Permissions and local features

Vibration supports optional check-in feedback. Notification permission is requested on supported versions when enabling a reminder. Haptics, animations and reminders can be disabled in Settings; Android settings also control notifications.

WorkManager contributes normal permissions for wake locks, network-state observation, receiving boot events and foreground services. AndroidX adds the app's signature-protected non-exported dynamic-receiver permission. Network-state observation does not grant Internet access. Widget/reminder workers perform no network operation. There is no broad storage or exact-alarm permission.

Optional habit reminders use local times on scheduled, active days. Notifications contain habit names, request private lock-screen visibility and supply generic public text; your device/notification settings determine actual visibility. Complete writes to the local database. A once-per-day ledger avoids duplicates; failed posting releases its claim for retry. A separate opt-in reminder suggests a backup roughly every 30 days. Android may delay either feature to conserve battery; neither is an exact-time alarm.

The optional launcher widget displays habit names and today's status. Add it only if you are comfortable with that information appearing on your home screen. Actions update the same database; the widget does not store a second habit database.

## Manual files

Export offers readable JSON or optional password-encrypted JSON. Both contain habits/IDs, quantities/logs, notes, timestamps, schedule history, pauses, links, ordering, settings and spent freeze months. Operational notification-delivery records are excluded. Android's system picker lets you choose Downloads, removable storage or an installed document provider. A provider such as Google Drive may upload a file if you explicitly choose it; LifeTrack itself does not connect to it. Deleting app data never deletes exported copies.

Encrypted files use AES-256-GCM, a 128-bit authentication tag, a fresh random 16-byte salt and 12-byte IV, and PBKDF2-HMAC-SHA256 with 600,000 iterations. Compatible files with 210,000–600,000 iterations can be opened. Encrypted export requires at least 10 password characters. Passwords are not persisted or transmitted and cannot be recovered by LifeTrack. Keep a separate safe password record. This protects the exported file, not the app's local SQLite database. Plain JSON is readable by anyone with access to the file.

Import reads only the selected file and replaces local records after confirmation, decryption when applicable and validation. Invalid files or passwords leave current records intact. The source file is unchanged. Normal JSON version 1 is upgraded; new exports use data format version 2. Plain/decrypted data is capped at 10 MiB, encrypted envelopes at 15 MiB. Import resets the operational delivery ledger, so a restored habit may receive another reminder that day if still due.

## Retention and deletion

Uninstalling or clearing app storage deletes the local database. Export and verify a backup before changing devices or removing the app. An update using the same package and signing key migrates in place; see [UPGRADE.md](UPGRADE.md).

Archived habits and recorded pause history remain until explicitly deleted. Permanent deletion removes the habit, its logs, pauses and delivery ledger; linked dependents detach. A spent freeze remains spent even if its habit is deleted. Saved files remain until you delete them separately.

The browser preview uses separate browser storage and does not share the Android database; its own disclosures apply. Device security, screenshots, externally created backups, saved files and third-party document providers are governed by device settings and the services you choose.
