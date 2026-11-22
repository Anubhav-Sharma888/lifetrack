# LifeTrack — agent instructions

Offline-first Android habit tracker: Kotlin, Jetpack Compose Material 3, Room, MVVM + repository, Hilt, WorkManager, Glance. Source root: `app/src/main/java/com/lifetrack/app/`.

## Non-negotiable constraints (apply to every session and agent)

- Offline-first always: no `INTERNET` permission, no accounts, no analytics/cloud sync, no automatic app-data cloud backup.
- Room is the canonical preference store; JSON backups (formats v1–v10) are the only restorable export. Any schema change needs an additive Room migration with exported `app/schemas/*.json`, no destructive fallback, and backup-format versioning with in-memory conversion of older formats.
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

### Version 2 line (active)

- Branch **`version-2`** (branched from the `main` tip `e97c8b9653303efb5a026f03c482c61e40c1fd33`). **Release policy (supersedes the per-Prompt-8 plan):** each prompt lands a preview published by **ChatGPT** (v2.2.0-preview.1 after Prompt 2, v2.3.0-preview.1 after Prompt 3, … v2.8.0-preview.1 after Prompt 8); ZCode owns development, validation, commit + push to `origin/version-2` only — never publish APKs/tags/workflows or change versionName/versionCode, never merge to `main`, no force-push, protect `D:\LifeTracker-backup` and ChatGPT's `release/v*` branches. Eight-prompt plan: [`docs/V2_ROADMAP.md`](docs/V2_ROADMAP.md); requirements: [`docs/V2_PRODUCT_REQUIREMENTS.md`](docs/V2_PRODUCT_REQUIREMENTS.md); architecture: [`docs/V2_ARCHITECTURE.md`](docs/V2_ARCHITECTURE.md); data model: [`docs/V2_DATA_MODEL.md`](docs/V2_DATA_MODEL.md).
- **Prompt 1/8 landed**: Room schema **11** (additive `MIGRATION_10_11`): `goals`, `goal_phases`, `goal_milestones`, `goal_tasks`, `goal_task_dependencies` — every row carries a permanent canonical UUID (unique index) as external identity; row ids are local only. Backup format **v10** = goal roadmaps (additive `@EncodeDefault(NEVER)` lists; v3–v9 plain exports stay key-free). All goal writes go through `data/GoalRepository.kt` (state machines in `GoalTransitions`, shared validation in `GoalValidator`). **Room DOES enforce declared FKs at runtime** (verified in Prompt 2 — the earlier "enforcement is off" note was wrong); repositories still perform every child delete explicitly for deterministic, observable semantics. Replace-all import/reset clear goal tables edges→tasks→milestones→phases→goals.
- **Prompt 2/8 landed**: Goal Roadmaps UI (hub as 5th bottom destination, editor, detail, dialogs) + pure `domain/GoalTimeline.kt` + schema **12** (`goal_schedule_revisions` audit table, backup v11) + non-destructive rescheduling (`rescheduleX`, `shiftDependentTasks` names an infeasible dependent and aborts atomically). Detail screen runs the hot-StateFlow pattern (selectGoal → flatMapLatest → retryWhen backoff → catch; state-gated in tests — see GoalScreensTest `awaitSettled`).
- **Prompt 3/8 landed**: pure `domain/GoalProgress.kt` (breakdowns, nullable-when-unmeasurable rates, derived `readyForReview` — never stored, never auto-promoted); task quick actions + explicit two-step milestone verification UI; prerequisite-gate policy `SATISFIED_COMPLETED / SATISFIED_BYPASSED / PENDING` (skipped bypasses the gate, never counts as completion); schema **13** `goal_habit_links` (association-only, backup v12, read-only `habitActivity` signals — goal code never writes habit logs); **Goal Plan JSON** contract (`kind: "lifetrack.goal-plan"`, `schemaVersion: 1`) in `data/GoalPlanDocument.kt` + `docs/GOAL_PLAN_FORMAT.md` / `docs/goal-plan.schema.json` / `docs/GOAL_PLAN_AI_PROMPT.md` + fixtures in `docs/examples/goal-plans/` — a distinct document type, never restorable as a backup.
- **Prompt 4/8 landed**: Goal Plan **import** — `data/GoalPlanImport.kt` (bounded file reader, UUID classification with cross-type/foreign-goal conflicts, four decisions: Keep existing / Use imported (plan-provided fields only, preserves status/history, writes schedule revisions) / Copy as new (fresh uuids minted in-transaction) / Skip, one-transaction apply re-verifying the reviewed preview fingerprint) + `ui/GoalPlanImportScreen.kt` / `GoalPlanImportViewModel.kt` (Goals → Import Goal Plan route `goalImport`). Import never deletes; absence from the file means nothing; repeated imports are idempotent; failed imports roll back completely. Schema 13 and backup v12 unchanged.
- Data-safety constraints for every V2 session: statuses/UUIDs validated against `GoalValidator` on every write and import; nothing ever auto-completes (no deadline/notification/parent-derived status writes); goal code reads habit data read-only through `goal_habit_links` + `HabitRepository.habitActivity` and never writes habit logs/settings; every multi-table write is one transaction; **Goal Plan import never deletes and never calls the full-backup replace-all path**.
- **Prompt 5/8 landed**: study sessions — schema **14** `goal_sessions` (additive `MIGRATION_13_14`, SET NULL goal/phase/task associations), backup **v13** (`goalSessions`, gates 13→14 in validate and decode), `SessionRepository` lifecycle (PLANNED → IN_PROGRESS → COMPLETED on explicit actions; PLANNED → COMPLETED keeps actualStart null; CANCELED preserved; awaiting-review derived, never stored), `SessionTimePolicy` DST policy (planned wall clock in the recorded IANA zone; actual times are UTC instants), best-effort unique per-event WorkManager reminders revalidated at delivery, `lifetrack://session/<uuid>` deep links behind App Lock, Sessions screen/editor/detail from the Goals top bar. Missed/partial/snooze/recovery stay Prompt 6. Room commits canonical; WorkManager reconciled idempotently after lifecycle events, restore/reset/startup.
- **Prompt 6/8 landed**: session recovery + goal analytics + habit ringing alarms — Room schema **15** (additive `MIGRATION_14_15`), backup **v14** (`habit_alarms` + session recovery/reported minutes/snooze/reschedule fields), `SessionRecoveryRepository`, `GoalSessionAnalytics` planned-vs-actual calculations, `HabitAlarm` model/DAO/repository/recurrence engine/scheduler/AlarmRingService foreground service with sustained audio, dismiss/snooze, reboot/time reconciliation.
- **Prompt 7/8 landed**: UX visual refinement (button hierarchy, token consistency, card styling, spacing), accessibility audit & fixes (TalkBack labels on phases/milestones/tasks/unlink, `heading()` semantics on section headers, 48dp minimum touch targets, polite live regions on errors, numeric keyboard types), navigation & back safety (dirty draft tracking + discard dialogs on `GoalEditorScreen` and `SessionEditorScreen`, back handler lock during in-flight import on `GoalPlanImportScreen`), performance & service lifecycle (cancelled timeout coroutine job on stop/dismiss/snooze/onDestroy to eliminate ghost reschedulings in `AlarmRingService`, `.setFullScreenIntent(open, true)`), edge cases (duplicate alarm time rejection, confirmation dialogs on destructive session deletion/cancellation), deep link parsing (`lifetrack://session/<uuid>`) routed behind App Lock.
- Prompt sequencing: 1 ✅, 2 ✅, 3 ✅, 4 ✅, 5 ✅, 6 ✅, 7 ✅ (audited, reviewed & verified) — remaining: 8 = integration/QA/release.

### Version 1 line (archived, sessions 1–13)

- Branches `develop/achievements` / `main`; latest published preview `v1.11.0-preview.1` (versionCode 12). Room schema **10** at that tip; backup formats v1–v9 (v6 = Smart Streaks, v7 = daily reflections, v8 = achievement acknowledgments, v9 = flexible scheduling; each format required only when such data exists; plain exports stay key-free for older apps via `@EncodeDefault(NEVER)`).
- App lock (opt-in biometric/device-credential gate) is local-only: `appLockEnabled` is stripped from backups and rejected on import — never serialize it.
- Daily reflections (`daily_reflections`, date-scoped, NO habit FK) and achievement acknowledgments (`achievement_acknowledgments`, id-scoped, NO FK): import/reset must clear both explicitly — habit cascades never touch these tables.
- Flexible scheduling (`domain/Scheduling.kt`, schema 10: `scheduleMode`/`intervalDays`/`intervalAnchor`/`weeklyTarget`): WEEKDAYS (default, legacy habits unchanged), EVERY_N_DAYS (LocalDate/epochDay grid from the revision anchor — never elapsed millis), WEEKLY_QUOTA (ISO Mon–Sun, any day counts). Canonical semantics: quota habits are check-in-eligible every day but NEVER daily-due — `Analytics.dueOn` gates `unmarked`/`density`/streaks-zero/rate exclusion, reminders (policy + claim + daily worker) and CSV per-row resolution. Null-mode schedule revisions are weekday periods (`Scheduling.mode`), so weekday-era history survives mode switches. Import validation range-checks interval 2..365 and weeklyTarget 1..7 at habit AND revision level.
- v1.8.0/v1.9.0/v1.10.0 progress: 1) ✅ CSV export, 2) ✅ directional progress tracking, 3) ✅ biometric app lock, 4) ✅ Smart Streaks (engine + UI), 5) ✅ mood/journal data layer + UI, 6) ✅ achievements engine + acknowledgment persistence, 7) ✅ achievements UI (Prompt 6), 8) ✅ flexible scheduling (Prompt 7 engine + Prompt 8 UI), 9) ✅ UI/UX refinement phase 1 (Prompt 9), 10) ✅ final visual polish + cross-feature fixes (Prompt 10: token/radius/contrast pass, quota streak-decision guard, celebration-race fix). Next: Prompt 11 = integration and device QA; Prompt 12 = release.
- Celebration driver (HabitViewModel): `celebrationSuppressed` gates the whole driver during reset/import transitions — flags move BEFORE the transactions (reset keeps celebrate mode; import seeds silently) and reset clears the flag in `finally`. Never emit celebrations or acknowledgment writes without re-checking the flag.
- Quota/streak boundary: streak decisions (`setStreakProtection`) and the decision block hide for weekly-quota habits at mode-at-date, mirroring `useFreeze`/the freeze button — period-based habits have no chain.
- Dialog convention (Prompt 9): new dialogs use the custom `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` + Surface shell with an 85%-of-window height cap read from `LocalWindowInfo.containerSize` — platform `AlertDialog` width derivation measure-loops under Robolectric, and no second window is ever stacked on a platform AlertDialog. Never call `LocalDate.now()` in a composable: use `state.today`. Robolectric limitation: the M3 DatePicker day grid never composes (device-QA item); don't write picker-driven UI tests.
- Deferred deliberately: weekly quota week-walking streaks + a weekly nudge reminder (engine KDoc: need product decisions with their UI); Prompt 11 device-QA list in WORK_PROGRESS.md session 12 (TalkBack traversal, large-font reflow, device contrast, both DatePicker flows, dialog IME).
- Known gap: README/UPGRADE describe the pre-1.8.0 state in places — refresh docs when releasing (Prompt 12).
