# LifeTrack — agent instructions

Offline-first Android habit tracker: Kotlin, Jetpack Compose Material 3, Room, MVVM + repository, Hilt, WorkManager, Glance. Source root: `app/src/main/java/com/lifetrack/app/`.

## Non-negotiable constraints (apply to every session and agent)

- Offline-first always: no `INTERNET` permission, no accounts, no analytics/cloud sync, no automatic app-data cloud backup.
- Room is the canonical preference store; JSON backups (format v3/v4) are the only restorable export. Any schema change needs an additive Room migration with exported `app/schemas/*.json`, no destructive fallback, and backup-format versioning with in-memory conversion of older formats.
- Preserve existing user data, tracking rules and backward compatibility. Tracking semantics live in `domain/Analytics.kt` and `HabitRepository.kt`; change them only with test coverage.
- Do not commit, push, merge, or modify `main` unless the user explicitly asks.
- Report faithfully: state the exact commands and results you observed; never claim a check you did not run (see VALIDATION.md culture).

## Agent routing (lead assigns scope; one writer per file)

| Concern | Agent | Files / scope |
|---|---|---|
| Requirements, acceptance criteria | product-requirements-analyst | no code; docs/ notes |
| Structure, boundaries, migration strategy | architecture-specialist | analysis + agreed structural edits |
| Data-layer logic (repo, backup, CSV, crypto) | backend-engineer | `data/` (`HabitRepository.kt`, `Backup.kt`, `BackupCrypto.kt`, `CsvExport.kt`), `domain/` |
| Room schema/entities/migrations only | database-migration-specialist | `data/Models.kt`, `AppDatabase.kt`, `DatabaseMigrations.kt`, `app/schemas/` |
| Compose UI & widgets | frontend-engineer | `ui/`, `widget/`, `res/` |
| Usability/accessibility assessment | ui-ux-specialist | read-first; coordinate edits with frontend-engineer |
| Security & privacy review | security-privacy-specialist | `BackupCrypto.kt`, escaping guards, manifest, `res/xml/backup_rules.xml`, data flows |
| Cross-component wiring | integration-engineer | ViewModel ↔ repository ↔ widget/reminders ↔ `LifeTrackApplication.kt` |
| Build/versioning/release config | devops-deployment-specialist | `build.gradle.kts`, `gradle.properties`, versionCode/versionName |
| Tests & verification | quality-analyst | `app/src/test/`, running suites |
| Post-work unit review | independent-reviewer | strictly read-only (Read/Grep/Glob); findings go to the lead |
| Final release assessment | gold-final-reviewer | after all specialist work settles; coordinates fixes via the lead |

Avoid redundant analysis: one specialist per concern; reviewers see finished diffs, not in-progress work.

## Coordination limits

- Maximum two lightweight analysis/review agents concurrently; heavy builds and test suites run sequentially, one at a time.
- UI, database, and security changes each get one owning agent; the lead merges their reports before review.

## Resource policy for builds/tests (this machine)

- JDK 21 JBR: `C:\Program Files\Android\Android Studio2\jbr` (set `JAVA_HOME`; no JDK on PATH).
- Gradle must always run with `--max-workers=2 --no-parallel --no-daemon`; `org.gradle.workers.max=2` is pinned in `gradle.properties` as the effective default.
- Check free RAM before building (`Get-CimInstance Win32_OperatingSystem`). Pinned heap is `-Xmx3g`; if free RAM is under ~4 GB, cap the workload instead of building anyway: `-Dorg.gradle.jvmargs=-Xmx1200m -Dkotlin.compiler.execution.strategy=in-process` plus a focused `--tests` filter. Robolectric suites need the full pinned heap — defer them until memory frees.
- Run builds/tests at BelowNormal priority (`$p.PriorityClass` after `Start-Process -PassThru`; `Start-Process -Priority` is unsupported on Windows PowerShell 5.1).
- Focused suites: `gradlew.bat --max-workers=2 --no-parallel --no-daemon testDebugUnitTest` (add `--tests <class>`); full check per README: `assembleDebug testDebugUnitTest lintDebug`.

## Current state

- Branch `develop/achievements` (branched from the v1.9.0-preview.1 release on `main`); versionName 1.9.0 / versionCode 9; Room schema **10**; backup format v1–v9 (v6 = Smart Streaks, v7 = daily reflections, v8 = achievement acknowledgments, v9 = flexible scheduling; each format required only when such data exists; plain exports stay key-free for older apps via `@EncodeDefault(NEVER)`). Latest published preview: tag `v1.10.0-preview.1` (= Prompt 7 tip).
- App lock (opt-in biometric/device-credential gate) is local-only: `appLockEnabled` is stripped from backups and rejected on import — never serialize it.
- Daily reflections (`daily_reflections`, date-scoped, NO habit FK) and achievement acknowledgments (`achievement_acknowledgments`, id-scoped, NO FK): import/reset must clear both explicitly — habit cascades never touch these tables.
- Flexible scheduling (`domain/Scheduling.kt`, schema 10: `scheduleMode`/`intervalDays`/`intervalAnchor`/`weeklyTarget`): WEEKDAYS (default, legacy habits unchanged), EVERY_N_DAYS (LocalDate/epochDay grid from the revision anchor — never elapsed millis), WEEKLY_QUOTA (ISO Mon–Sun, any day counts). Canonical semantics: quota habits are check-in-eligible every day but NEVER daily-due — `Analytics.dueOn` gates `unmarked`/`density`/streaks-zero/rate exclusion, reminders (policy + claim + daily worker) and CSV per-row resolution. Null-mode schedule revisions are weekday periods (`Scheduling.mode`), so weekday-era history survives mode switches. Import validation range-checks interval 2..365 and weeklyTarget 1..7 at habit AND revision level.
- v1.8.0/v1.9.0/v1.10.0 progress: 1) ✅ CSV export, 2) ✅ directional progress tracking, 3) ✅ biometric app lock, 4) ✅ Smart Streaks (engine + UI), 5) ✅ mood/journal data layer + UI, 6) ✅ achievements engine + acknowledgment persistence, 7) ✅ achievements UI (Prompt 6), 8) ✅ flexible scheduling (Prompt 7 engine + Prompt 8 UI), 9) ✅ UI/UX refinement phase 1 (Prompt 9), 10) ✅ final visual polish + cross-feature fixes (Prompt 10: token/radius/contrast pass, quota streak-decision guard, celebration-race fix). Next: Prompt 11 = integration and device QA; Prompt 12 = release.
- Celebration driver (HabitViewModel): `celebrationSuppressed` gates the whole driver during reset/import transitions — flags move BEFORE the transactions (reset keeps celebrate mode; import seeds silently) and reset clears the flag in `finally`. Never emit celebrations or acknowledgment writes without re-checking the flag.
- Quota/streak boundary: streak decisions (`setStreakProtection`) and the decision block hide for weekly-quota habits at mode-at-date, mirroring `useFreeze`/the freeze button — period-based habits have no chain.
- Dialog convention (Prompt 9): new dialogs use the custom `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` + Surface shell with an 85%-of-window height cap read from `LocalWindowInfo.containerSize` — platform `AlertDialog` width derivation measure-loops under Robolectric, and no second window is ever stacked on a platform AlertDialog. Never call `LocalDate.now()` in a composable: use `state.today`. Robolectric limitation: the M3 DatePicker day grid never composes (device-QA item); don't write picker-driven UI tests.
- Deferred deliberately: weekly quota week-walking streaks + a weekly nudge reminder (engine KDoc: need product decisions with their UI); Prompt 11 device-QA list in WORK_PROGRESS.md session 12 (TalkBack traversal, large-font reflow, device contrast, both DatePicker flows, dialog IME).
- Known gap: README/UPGRADE describe the pre-1.8.0 state in places — refresh docs when releasing (Prompt 12).
