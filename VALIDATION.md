# LifeTrack 1.1.0 validation

Validated 26 October 2026. Native database schema 2; app versionCode 2. JDK 17, Gradle 8.11.1, AGP 8.10.1, Kotlin 2.1.21, Android SDK 36 and Build Tools 35.0.0.

## Executed final checks

```text
gradlew.bat assembleDebug testDebugUnitTest lintDebug bundleRelease lintRelease
node --test --test-reporter=junit tests/*.test.mjs
```

- Native: **102 tests passed**, zero failures/errors/skips.
- Preview: **49 tests passed**, zero failures/errors/skips.
- Debug lint: 0 errors, 27 warnings, 1 hint.
- Release lint: 0 errors, 27 warnings, 1 hint.
- Debug APK built and signature checked with Android SDK apksigner; release AAB built with R8/resource shrinking and verified unsigned.
- Actual SQLite schema1→2 migration tested with exported schema1, preserving IDs, notes, timestamps, settings and open archive interval.
- Date schedules/history, pause/resume, partial goals, immutable historical goals, exact guarded Undo, concurrent increments, safe imports, reminder claims/retry and daily indexed snapshots covered by regression tests.
- Compose tests cover compact/large-font analytics, numeric semantics, motion/haptic preferences, status preservation, protected fields, non-drag reordering, draft restoration and inline failed-save retention. The dialog's finite viewport was repaired without removing the failing test.
- Compiled native AES-GCM encryption and browser WebCrypto interoperate in both directions with multilingual content; static cross-runtime fixtures are tested.
- Manual browser QA used isolated `?qa=review` data: search, numeric increment/decrement, guarded Undo, detail goal protection, schedule controls and Analytics verified. User preview data was not reset.
- Release manifest checked: no INTERNET permission, allowBackup=false, explicit backup/extraction exclusions. WorkManager contributes normal background/system permissions; the app does not claim that only two permissions exist.
- Room schemas1 and2, the checksum-pinned Gradle wrapper and source tests are included.

| Native suite | Tests | Failures |
| --- | ---: | ---: |
| BackupCryptoTest | 13 | 0 |
| BackupValidationTest | 16 | 0 |
| DatabaseMigrationTest | 1 | 0 |
| HabitRepositoryTest | 14 | 0 |
| UpgradeBehaviorTest | 16 | 0 |
| VersionTwoBackupValidationTest | 5 | 0 |
| AnalyticsTest | 8 | 0 |
| ScheduleAnalyticsTest | 6 | 0 |
| ReminderPolicyTest | 4 | 0 |
| AnalyticsScreenTest | 2 | 0 |
| EditorRecoveryTest | 7 | 0 |
| HabitComponentsTest | 5 | 0 |
| UpgradedComponentsTest | 5 | 0 |

## Verification limits

No physical Android device/emulator session was performed. Launcher rendering/resize, real vibrations, Android notification permission/Doze behavior, SAF providers, battery consumption, cold-start memory and sustained performance still need hardware QA before a public release. Robolectric/compile checks do not certify those behaviors. Reminders are inexact WorkManager nudges, not exact alarms. Numeric targets/units are protected once history exists; create a new habit for a new target. UI history observation still materializes records; background widget/reminder queries are date-bounded. The database is Android-private storage, not app-level encrypted storage. Optional backup encryption does not supply password recovery.

Detailed criterion scores and source evidence are supplied in the accompanying evaluation report. Scores are expert judgments, not store ratings, reliability percentages, penetration-test results or certification.

APK SHA-256: `67a2fa70c864fe0c0e0326f0412d02763b311ec2a756a7615c598db4062142ed`

Unsigned AAB SHA-256: `fe9790b0f8372367c582ee052a51896db2aeae4de2c275c933d2e82353fbbd8a`
