# LifeTrack validation log

## LifeTrack 1.7.0 validation

Validated 12 November 2026 against the updated Google Stitch Personal Almanac export. Activities, Schedules & Reminders, Settings and backups, reset confirmation, and the post-reset Today view use existing app records and controls. Habit schedule cards open the existing editor. Native reminders use the existing scheduler. The browser provides an in-app daily nudge.

- Browser: **99 tests passed**. Syntax checks passed for `app.js` and `analytics.js`. In an isolated browser preview, opened an existing workout from the dated ledger, navigated to daily and per-habit reminder editors, opened both backup flows, and verified the reset backup-first path, exact confirmation, and cancellation.
- Android: **110 tests passed**, zero failures, errors, or skips. `:app:testDebugUnitTest :app:assembleDebug` succeeded with JDK 17 and Gradle 8.11.1 using `--max-workers=2 --no-parallel --no-daemon`.
- The signed debug APK identifies as `com.lifetrack.app`, version name `1.7.0`, version code `8`. Its certificate matches the v1.6.0 APK. Room schema 4 and backup formats carry forward from v1.6.0.
- No phone was connected for an installed upgrade or notification delivery test.

APK SHA-256: `d3db3ed40596fab4fa11f704fe84a4472331887d85fded3035d5e9717beb6143`

---

## LifeTrack 1.6.0 validation

Validated 7 November 2026. Native database schema 4 (unchanged from 1.5.0); app versionCode 7, versionName 1.6.0; applicationId com.lifetrack.app unchanged. JDK 17 (Temurin 17.0.20.1), Gradle 8.11.1, Android SDK 36, Build Tools 35.0.0.

### Executed checks

```text
node --check Preview/dist/app.js
npm test            # in Preview/
gradle :app:testDebugUnitTest :app:assembleDebug --max-workers=2 --no-parallel --no-daemon
apksigner verify --print-certs   # candidate vs the published v1.5.0 APK
```

- Preview: **99 tests passed**, zero failures (was 82 at v1.5.0; +17 for the Reset LifeTrack feature, its stale-tab guards and the failed-second-reset regression).
- Native: **108 tests passed**, zero failures (was 104 at v1.5.0; +4 Reset LifeTrack repository tests).
- Debug APK built from this tree; `apksigner verify --print-certs` shows the candidate is signed with the SAME certificate as the published v1.5.0 APK (signer SHA-256 `4448dfb6f78d17d5947a841b8c87d554c6e9471b7ae2b599802ee5361248c071`), supporting an in-place upgrade.
- Reset fix regression covered: a failed second reset keeps the generation marker and a pre-reset tab cannot write erased records back (unit-tested; also verified live in a real browser across two tabs sharing storage events).
- Live browser QA (Chromium via automation, isolated `?qa=review` data): Today/Settings/Analytics light + dark, desktop folio grid, reset confirmation gate (wrong/exact/trailing-space), cancel preserves data, erase → empty Today with Add a habit + Restore a backup, reload stays empty, cross-tab adoption with no resurrection, dialog scroll reachability at constrained height. User preview data was not touched (namespaced QA profile).
- Personal Almanac redesign applied browser (styles/analytics CSS, bundled OFL variable fonts) and native (Theme + 10 UI files, visual-only; strings, semantics and behavior asserted unchanged by the suites). Fonts: Newsreader, Plus Jakarta Sans, JetBrains Mono (SIL OFL 1.1, texts bundled).
- Known remaining: physical-device QA (in-place upgrade from installed v1.5.0 with disposable data, notification delivery/cancellation, widget refresh, TalkBack) and a real soft-keyboard session still need hardware; no device was available for this validation. The Stitch canvas was access-gated; the four exported Almanac frames + DESIGN.md were the visual references, and no visual parity is claimed for unexported canvas screens.

### v1.6.0 APK

- Candidate APK: `LifeTrack-1.6.0-almanac-debug.apk` (23,458,876 bytes)
- APK SHA-256: `70581846ac00515ddaf28d200ba438b41345e4b36689157e5bcdde278661540e`

---

## LifeTrack 1.1.0 validation

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
