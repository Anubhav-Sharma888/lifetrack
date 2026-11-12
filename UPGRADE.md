# Upgrading LifeTrack

LifeTrack 1.7.0 uses Android version code 8. The application ID is `com.lifetrack.app`. Room schema 4 and existing backup formats carry forward from 1.6.0. Export a backup from your current installation, then install the new APK over it. An in-place update requires the same signing certificate. The browser preview keeps its own storage.

## Historical migration notes for 1.1.0

This release uses Android `versionCode = 2` and Room database schema version 2. The explicit 1 → 2 migration preserves existing user data. It neither requires uninstalling nor uses a destructive fallback.

## Preserve the installation

1. Export a JSON backup from the existing app to a location you can find. Keep it until the update and recovery have been checked.
2. Update with the same application ID (`com.lifetrack.app` by default) and **same signing certificate**. Debug builds also need matching debug keys; another machine may use a different key. Release updates must use the original release signing key. A version number alone is insufficient.
3. Install over the existing app through Android Studio or `adb install -r app-debug.apk` for a matching debug build. If Android reports incompatible signatures, obtain a build signed with the original key. Do not uninstall or clear storage as a workaround: either deletes the local database.
4. Open LifeTrack; Room migrates on first database access. Check habits, notes, history, order, settings and freezes, then save a new backup.

The old and new supplied debug APK signatures were verified to use the same certificate; the evidence accompanies the evaluation in `apk-signature-verification.txt`. This establishes signature compatibility, not a completed installed upgrade on a physical device. See [VALIDATION.md](VALIDATION.md) for executed checks and remaining device QA.

If an old signing key is unavailable, code changes cannot make an installed update signature-compatible. Export from the old app before any deliberate reinstall or package change; keep the original until you have a recoverable copy. A different application ID creates a separate app and database.

## What migrates

- Existing habit/log IDs, names, descriptions, notes, timestamps, links, ordering, categories, archive flags, preferences and spent freeze months remain.
- Daily habits default to all seven weekdays, target 1 and unit `times`. COMPLETED logs receive progress 1; other statuses receive progress 0.
- Haptic/animation controls default to enabled; new per-habit reminder times default to off.
- Currently archived habits receive an open pause beginning on archive date + 1. Archive/restore intervals that version 1 never recorded cannot be reconstructed. New archives preserve that day's history and pause from the following day. Restore closes the interval at its restore date, exclusive, so that date is active when scheduled.
- Schedule-history storage and the operational once-per-day reminder ledger are added. The ledger starts empty.

Future schema upgrades require explicit migrations. Database downgrades are not provided.

## Changed behavior

Weekday edits take effect today and retain past schedules. Any saved log, including skipped/failed/partial, protects that habit's target and unit from editing; historical amounts are never rewritten by a new goal. Create a new habit for a different target/unit. Partial progress is IN_PROGRESS; only a reached target is COMPLETED. Unscheduled days and recorded pauses are neutral in streaks and excluded from rates. Relative day numbers still count calendar days from the original start.

Today adds search/category/status filters, templates, non-drag reorder controls and exact guarded undo. Settings adds haptic/motion controls and optional encrypted exports. Local habit reminders scan roughly every 15 minutes with a once-per-habit/day ledger; Android background restrictions can delay them. Existing notification permission settings apply. Failed posting releases a claim for retry, and lock-screen notifications request private visibility with generic public text.

## Backup compatibility

Normal JSON version-1 backups are accepted and upgraded in memory before any records are replaced. New exports use data format version 2, including quantities, schedule history, pauses and feedback preferences. The old app cannot import version-2 files; keep a pre-upgrade backup if returning to it may be necessary.

Optional encryption wraps JSON in a separate `lifetrack-encrypted` envelope version 1. It uses AES-256-GCM, a 128-bit tag, fresh random 16-byte salt / 12-byte IV and PBKDF2-HMAC-SHA256 at 600,000 iterations. Decryption accepts 210,000–600,000 iterations. Encrypted export needs at least 10 password characters. Passwords are never persisted and cannot be recovered. Encryption protects that file, not the local SQLite database in Android private app storage.

Plain/decrypted JSON is capped at 10 MiB; encrypted envelopes at 15 MiB. Import confirms replacement, validates before deletion and replaces user data atomically. Invalid input, incorrect passwords or failed transactions leave current data intact. The operational reminder ledger is excluded and resets on import, so a due reminder can appear again. Import and app deletion do not remove saved backup files.

See [README.md](README.md) for the source map and all seven implementation steps, [PRIVACY.md](PRIVACY.md) for data handling and [VALIDATION.md](VALIDATION.md) for executed checks. The accompanying [evaluation](../LifeTrack-evaluation/senior-review.md) explains review evidence and limits.
