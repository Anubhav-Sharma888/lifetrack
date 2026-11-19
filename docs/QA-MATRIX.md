# LifeTrack QA Matrix — Prompt 11 (Integration Testing and Release Hardening)

Generated from the actual test inventory on branch `develop/achievements` @ beb26a9 + the Prompt 11 hardening diff. Every AUTOMATED row points at the executing test file; results are only valid as of the last full `testDebugUnitTest` gate recorded in WORK_PROGRESS.md (Session 13). Device rows are classified MANUAL and reported honestly as NOT RUN — no Android device or emulator was available in this environment.

| Feature area | Scenario | Classification | Environment | Result | Evidence |
|---|---|---|---|---|---|
| Backup/restore | Plain export/import round trip: IDs, notes, dates, settings, stacking, freeze ledger | AUTOMATED | Robolectric (JVM) | PASS | `BackupValidationTest: backup round trip preserves IDs notes dates settings and stacks` |
| Backup/restore | Corrupt/malformed/deeply nested files rejected before any write | AUTOMATED | Robolectric (JVM) | PASS | `BackupValidationTest: malformed achievement acknowledgments…` / `deeply nested import files are rejected before the parser can overflow`; `HabitRepositoryTest: malformedOrStructurallyInvalidImportNeverErasesCurrentData` |
| Backup/restore | Unsupported future versions rejected | AUTOMATED | Robolectric (JVM) | PASS | `DailyReflectionBackupTest: backups above version nine are rejected`; `BackupValidationTest: unknown backup version rejected` |
| Backup/restore | Version gates v3/v5/v6/v7/v8/v9 (feature data requires its version) | AUTOMATED | Robolectric (JVM) | PASS | `DirectionalBackupTest` (v5); `StreakProtectionRepositoryTest` (v6); `DailyReflectionBackupTest` (v7); `BackupValidationTest` (v8 acks, v9 flexible) |
| Backup/restore | v1 oldest format → schema-10 engine defaults | AUTOMATED | Robolectric (JVM) | PASS | `BackupValidationTest: a hand made version one backup imports with schema-10 engine defaults` |
| Backup/restore | Encrypted backups: wrong password, tampering, iteration bounds, salt/IV manipulation | AUTOMATED | Robolectric (JVM) | PASS | `BackupCryptoTest` (13 tests) |
| Backup/restore | Export → reset → import restores every record | AUTOMATED | Robolectric (JVM) | PASS | `HabitRepositoryTest: exportThenResetThenImportRestoresEveryRecord` |
| Backup/restore | Reflections/acks import (replace, not merge); unknown achievement IDs inert | AUTOMATED | Robolectric (JVM) | PASS | `DailyReflectionRepositoryTest: importing replaces existing reflections…`; `BackupValidationTest: unknown achievement ids import as inert rows` |
| Backup/restore | Size caps (10/15 MiB), streaming read, UTF-8 strictness | AUTOMATED (static) | Code-inspection | PASS (static) | `HabitRepository.kt:498-516`; security audit 2026-11-20 |
| CSV export | RFC 4180 escaping, BOM, CRLF, multiline notes | AUTOMATED | Robolectric (JVM) | PASS | `CsvExportTest` (12 tests) |
| CSV export | Formula-injection guard (first-char + first-non-whitespace) | AUTOMATED | Robolectric (JVM) | PASS | `CsvExportTest: formula prefixes receive a leading apostrophe`; audit verified the guard covers every cell |
| CSV export | Schedule columns per row date (mode/interval/anchor/weekly target) | AUTOMATED | Robolectric (JVM) | PASS | `CsvExportTest: interval habit rows… / quota habit rows… / no-log detail rows…` |
| Habit CRUD | Create/edit/delete, cascade, stacking, orphan prevention | AUTOMATED | Robolectric (JVM) | PASS | `HabitRepositoryTest` (6 tests) |
| Check-ins | One record per habit+date (DB-enforced), widget idempotency, stale-widget guard | AUTOMATED | Robolectric (JVM) | PASS | `HabitRepositoryTest: checkingInTwice… / databaseItselfEnforces… / widgetCompletion… / staleWidget…` |
| Check-ins | Future/before-start/archived rejection | AUTOMATED | Robolectric (JVM) | PASS | `HabitRepositoryTest: rejectsFutureBeforeStartAndArchivedCheckIns…` |
| Smart Streaks | Threshold bridging (inclusive), MANUAL rows, protect/break overrides, zero-progress inert | AUTOMATED | Robolectric (JVM) | PASS | `StreakPolicyTest` (13 tests) |
| Smart Streaks | Decisions: past-day guard, pause guard, round trip, cascade, backup v6 | AUTOMATED | Robolectric (JVM) | PASS | `StreakProtectionRepositoryTest` (13 tests); `HabitRepositoryTest: weeklyQuotaDaysRejectStreakDecisions…` |
| Smart Streaks | Strict/automatic/manual analytics; quota habits zero day-streaks | AUTOMATED | Robolectric (JVM) | PASS | `StreakAnalyticsTest` (12 tests) |
| Flexible scheduling | WEEKDAYS grid + historical revisions | AUTOMATED | Robolectric (JVM) | PASS | `SchedulingTest: weekday habits… / weekday history revisions…` |
| Flexible scheduling | EVERY_N_DAYS grid: anchor, month/year crossings, re-anchor on edit, next date | AUTOMATED | Robolectric (JVM) | PASS | `SchedulingTest` (4 tests); `ScheduleAnalyticsTest: arithmeticDayCountMatchesCalendarReference…` |
| Flexible scheduling | WEEKLY_QUOTA: any-day completions, achieved/ongoing/missed/rest, pause-aware, foreign-pause isolation | AUTOMATED | Robolectric (JVM) | PASS | `SchedulingTest: weekly quota status… / quota progress ignores…`; `ScheduleAnalyticsTest: quotaEraCompletions…` |
| Flexible scheduling | Mode-at-date resolution across weekday→interval→weekday chains; era preservation | AUTOMATED | Robolectric (JVM) | PASS | `SchedulingTest: mode resolves from history… / weekday era history… / mode at date follows each era…` |
| Flexible scheduling | Editor draft restoration (mode + parameters) | AUTOMATED | Robolectric (JVM) | PASS | `EditorRecoveryTest: scheduleModeSelectionsAndParameterDraftsSurviveRestoration` |
| Flexible scheduling | Quota habits: no daily dues, no day-streaks, no daily overdue reminders, no streak freezes | AUTOMATED | Robolectric (JVM) | PASS | `AnalyticsTest` (quota tests ×3); `ReminderPolicyTest: quotaHabitsNeverReceiveDailyReminders`; `HabitRepositoryTest: weeklyQuotaHabitsDoNotUseStreakFreezes` |
| Flexible scheduling | Schedules/Analytics/CSV/Today/Detail show the same canonical state | AUTOMATED | Robolectric (JVM) | PASS | `EditorRecoveryTest: habitScheduleRow… / weeklyQuotaPartialDayWell…`; `AnalyticsScreenTest: schedulesScreenUsesTopAppBarAndStateToday`; `CsvExportTest: interval/quota rows` |
| Directional activities | INCREASING/DECREASING/PERSONAL_BEST current/baseline/fraction semantics | AUTOMATED | Robolectric (JVM) | PASS | `ProgressTrackingTest` (17 tests) |
| Directional activities | Zero targets (DECREASING), invalid inputs, backup v5 gates | AUTOMATED | Robolectric (JVM) | PASS | `HabitRepositoryTest: decreasingActivityMayRecordZero…`; `DirectionalBackupTest` (7 tests) |
| Mood/Journal | Mood-only, journal-only, combined, trim, 2000-char cap, date bounds, overwrite timestamps | AUTOMATED | Robolectric (JVM) | PASS | `DailyReflectionRepositoryTest` (18 tests) |
| Mood/Journal | Draft survival (save-state restoration), cancel semantics, remove confirmation | AUTOMATED | Robolectric (JVM) | PASS | `EditorRecoveryTest: noteDraftSurvives…`; `ReflectionUiTest` (4 tests) |
| Mood/Journal | Journal privacy: excluded from CSV; platform-backup excluded; not in notifications/widgets/logs | AUTOMATED (static) | Code-inspection | PASS (static) | `CsvExport.kt` (no reflection columns); `backup_rules.xml`; security audit 2026-11-20 (finding L-2 fixed: PRIVACY.md now discloses journals) |
| Achievements | 13 definitions: completion/streak/activity/reflection computation, revocation on deletion | AUTOMATED | Robolectric (JVM) | PASS | `AchievementsTest` (25 tests) |
| Achievements | Quota habits: completions count, streaks never | AUTOMATED | Robolectric (JVM) | PASS | `AchievementsTest: weekly quota completions count toward milestones while day streaks stay zero` |
| Achievements | Celebration-once + reset/import transition race guard + fresh-journey re-celebration | AUTOMATED | Robolectric (JVM) | PASS | `EditorRecoveryTest: resetSuppressesTheCelebrationRaceAndTheFreshJourneyCelebratesAgain`; `HabitViewModel` celebrationSuppressed |
| Achievements | Backup acknowledgment rows: known + unknown (inert) IDs, malformed rejection | AUTOMATED | Robolectric (JVM) | PASS | `BackupValidationTest: unknown achievement ids import as inert rows / malformed achievement acknowledgments…` |
| Reminders | Time-of-day, status exclusions, pause quiet, interval grid dates, quota exclusion, claim-once + retry | AUTOMATED | Robolectric (JVM) | PASS | `ReminderPolicyTest` (6 tests); `UpgradeBehaviorTest: remindersAreClaimedOnce… / failedNotificationClaim…`; `HabitRepositoryTest: claimReminderSkipsQuota…` |
| App Lock | Gate hiding, FLAG_SECURE, re-lock on backgrounding, prompt-in-flight race, no-enrolled guard, draft survival | AUTOMATED | Robolectric (JVM) | PASS | `AppLockGateTest` (8 tests) |
| App Lock | Setting persists locally, survives restores, never travels in backups | AUTOMATED | Robolectric (JVM) | PASS | `HabitRepositoryTest: appLockSettingPersistsLocally…`; `BackupValidationTest: app lock is a local-only choice…` |
| Widget | Placeholder under lock, stale-date guard, expectedCreatedAt guard, snapshot isolation | AUTOMATED (static) | Code-inspection | PASS (static) | Security audit 2026-11-20 (verified-clean §4); `LifeTrackWidget.kt:70-77,244-258` |
| Migrations | Full chain v1→10 from v1; every exported era 2..9 upgrades forward | AUTOMATED | Robolectric (JVM) | PASS | `DatabaseMigrationTest: versionOneMigration… / everyExportedSchemaVersionStillMigratesForwardToVersionTen` |
| Migrations | Per-era preservation: schedule/goal history, Smart-Streak defaults, reflections/acks tables | AUTOMATED | Robolectric (JVM) | PASS | `DatabaseMigrationTest: versionSix/Seven/Eight/Nine tests` |
| Migrations | FK declarations on migrated DDL (cascades + SET NULL; reflections/acks FK-free) | AUTOMATED | Robolectric (JVM) | PASS | `DatabaseMigrationTest: migratedSchemasDeclareTheirForeignKeysWithoutEnforcingThem` |
| Undo/edits | Undo restores frozen entry, rejects newer writes, keeps notes; clock rollback guard | AUTOMATED | Robolectric (JVM) | PASS | `UpgradeBehaviorTest: exactUndo… / undoRejects… / quickUndo… / clockRollback…` |
| Historical edits | Schedule changes keep earlier history; archive/restore gap semantics; goal edits never rewrite records | AUTOMATED | Robolectric (JVM) | PASS | `UpgradeBehaviorTest: scheduleChanges… / archiveRestore… / targetAndUnitEdits…` |
| Reset | Erases every record incl. date-scoped tables; restores defaults; repo stays usable; rejects stale actions | AUTOMATED | Robolectric (JVM) | PASS | `HabitRepositoryTest: resetErasesEveryRecord… / resetRejectsStale… / afterReset…` |
| Date handling | All domain date math is LocalDate-based (no elapsed millis) — TZ/DST drift architecturally excluded from the data layer | AUTOMATED (static) | Code-inspection | PASS (static) | `Scheduling.kt` KDoc; `ScheduleAnalyticsTest: arithmeticDayCountMatchesCalendarReference…`; device TZ change = Prompt 11 device QA |
| Performance | Remember-key completeness, hoisted engine walks, LazyColumn keys, Analytics rate caching | AUTOMATED (static) | Code-inspection | PASS (static) | Prompt 9/10 architecture reviews; `SchedulesScreen` quota remember; `TodayScreen` HabitScheduleFacts map |

## Manual — device QA checklist (NOT RUN — no device/emulator available in this environment; Prompt 12/manual QA)

| Scenario | Why it cannot run here | Status |
|---|---|---|
| TalkBack traversal (merged toggleable rows, single-node heatmap, emoji-cleared cards, mood selector, achievement cards, App Lock) | Requires a real screen reader | NOT RUN |
| Large fonts to 200% (IntrinsicSize seal, dialog viewport caps, scheduleNote wrapping) | Requires real fontScale rendering | NOT RUN |
| Contrast measurement on device (dynamic-color tokens replace the static palette on API 31+) | Requires device sampling | NOT RUN |
| M3 DatePicker flows (Today date picker, detail past-date navigation) | Day grid never composes under Robolectric (documented limitation) | BLOCKED (automation) / NOT RUN (device) |
| Dialog IME behavior on the two custom shells (note fields, Save/Cancel reachability) | Requires hardware keyboard/IME cycling | NOT RUN |
| Installation/upgrade paths, signature compatibility, process recreation, device restart, uninstall/reinstall | Requires device/emulator + signed builds | NOT RUN |
| WorkManager reminder delivery on real alarm timing; battery-sensitive deferrals | Requires device | NOT RUN |
| Landscape / gesture vs three-button navigation / edge-to-edge insets | Requires device | NOT RUN |

## Summary

- AUTOMATED: 321 tests across 25 suites (final gate result recorded in WORK_PROGRESS.md Session 13). All feature scenarios above PASS.
- MANUAL: all device-dependent scenarios NOT RUN — no device/emulator in this environment. Prompt 12 must run the manual checklist or ship with it marked NOT RUN.
- Static evidence: security audit (READY WITH LOW FINDINGS; L-1 depth guard + L-2/L-3 disclosures fixed this session), architecture reviews (Prompt 9/10), and the code-inspection rows above.
