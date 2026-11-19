# LifeTrack — Work Progress (sessions 2026-11-15 → 2026-11-20: CSV export, directional tracking, app lock, Smart Streaks, Mood/Journal, Achievements, Flexible Scheduling engine + UI, UI/UX refinement phases 1-2, integration QA + release hardening)

Branches: `develop/achievements` (current) · `main` (= `32c9bfa`, carries the v1.9.0-preview.1 tag) · Backup at `D:\LifeTracker-backup` (untouched)

---

# Session 13 (Prompt 11/12) — Integration testing, release hardening, QA evidence

## What was done (this session, uncommitted → committing at end)

This stage proves correctness and release readiness — no new features. **QA matrix**: [`docs/QA-MATRIX.md`](docs/QA-MATRIX.md) maps every feature area → scenario → classification → environment → result → evidence, generated from the actual test inventory (310 `@Test` functions inventoried before hardening) and honestly separating AUTOMATED (Robolectric/JVM, PASS) from MANUAL device rows (NOT RUN — no device/emulator in this environment).

## Defects found and fixed this session

- **L-1 (security, crash)**: a crafted ~20 KB import file at JSON nesting depth 10,000 crashed the process — kotlinx-serialization 1.8.1 walks JSON recursively with no depth bound, `StackOverflowError` escapes every `Exception` handler on the import path (verified empirically against the pinned jar by the security auditor). **Fix**: `BackupValidator.MAX_JSON_DEPTH = 64` + a linear (non-recursive) character-scan pre-check in `decode` rejecting over-nested files before the parser runs; the same pre-check guards the **encrypted-envelope leg** (`BackupCrypto.decrypt` — the streaming Envelope decoder walks JSON recursively too), closing the crash class on both import legs; regression test pins the rejection message. Real backups nest a handful of levels — the bound cannot touch legitimate exports (entire backup suite re-verified green, including all 13 encrypted-backup tests).
- **L-2 (disclosure)**: PRIVACY.md still said "1.1.0"/"format version 2" and omitted the app's most sensitive data category — daily reflections (mood + journal). Refreshed: current version, stored-data inventory (reflections, activities, acknowledgments, v3–v9 formats), manual-files list now names mood/journal text.
- **L-3 (disclosure)**: CSV export copy did not state notes ship unencrypted (the JSON row did). Settings copy updated: "contains your notes unencrypted".
- Deferred (documented, not fixed): CI actions pinned to major tags not SHAs (release-publishing supply-chain hardening → Prompt 12, needs verified SHAs); inline-emoji announcements and celebration snackbar emoji (device listening pass); SettingsAction disabled dimming; swatch 48dp target.

## Migration hardening (release-critical, Stage 7)

`DatabaseMigrationTest` grew from 1 test (full chain v1→10, already deep) to **7 tests**: every exported schema era **2..9 upgrades forward to v10** with era-faithful seeded rows (per-era habit columns validated against each exported JSON), Smart-Streak defaults minted at 6→7, `daily_reflections`/`achievement_acknowledgments` tables created by 7→8/8→9 with INSERT+read-back, schedule/goal history surviving 9→10 unchanged, and FK **declarations** asserted against the migrated DDL (cascades on the four child tables, SET NULL stacking self-reference, reflections/acks deliberately FK-free — the reason import/reset clear them explicitly). Evidence note: Room 2.7.1 never enables SQLite FK enforcement (verified by disassembly) — declarations are what open-time validation compares; enforcement is a connection setting. The PRAGMA-based first attempt was replaced by `sqlite_master` DDL assertions after `SupportSQLiteDatabase.query` returned empty cursors for PRAGMAs.

## Backup compatibility hardening (Stage 8)

Acknowledgment import contract pinned: unknown achievement IDs are **inert** (validator checks row shape only; `Achievements.evaluate` can never surface an unknown id as acknowledged; the repository prunes unknown ids before insert) — rejecting them would silently drop a retired achievement's seen-marker, a product decision deliberately not made here. Malformed rows (blank/oversized ids, negative timestamps, duplicates) rejected with pinned messages. v1-format → schema-10 engine defaults pinned. `AchievementIds.FIRST_COMPLETION` etc. confirmed against the catalogue.

## Device QA — honest status

**NOT RUN / BLOCKED** — no Android device or emulator was available in this environment, and none is claimed. The full manual checklist lives in [`docs/QA-MATRIX.md`](docs/QA-MATRIX.md): TalkBack traversal, fontScale 2, device contrast (dynamic color), both M3 DatePicker flows (the **day grid never composes under Robolectric** — header/buttons render, grid contributes zero semantics nodes; discovered while driving past-date navigation, so two picker-dependent tests were replaced by repository/engine coverage + a navigation-free smoke), dialog IME behavior, install/upgrade paths, WorkManager alarm timing, landscape/insets.

## Security audit (Stage 12)

Dedicated audit verdict: **READY WITH LOW FINDINGS** — no Critical/High. Verified clean: permissions (VIBRATE + POST_NOTIFICATIONS only), no connectivity primitives anywhere, no tracking/analytics dependencies, all four workers do Room/notification work only, widget/receiver hardening (lock re-check at action time, stale-date + expectedCreatedAt guards), BackupCrypto (AES-256-GCM, fresh salt/IV, PBKDF2 bounds checked before KDF, AAD-bound headers), no sensitive logging, import validation-before-write in one transaction, backup/extraction rules exclude everything, CI uses only the default token. Findings: L-1/L-2/L-3 fixed (above); L-4 (CI action pinning) deferred to Prompt 12; informational trade-offs documented (app lock not crypto-bound, launcher deep-link extras, String-held passwords).

## Test evidence

- Final gate this session (recorded after the hardening diff): **`BUILD SUCCESSFUL in 17m` — testDebugUnitTest 321/321 across 25 suites, 0 failures** + **lintDebug 0 errors, 27 warnings, 1 hint** (29→27: the two `ModifierParameter` findings cleared by Prompt 10's selector reorders; the remaining 27 are pre-existing dependency/style findings — 17 GradleDependency, 6 UseKtx, 2 UnusedAttribute, 1 VectorRaster, 1 ConstantLocale) + **assembleDebug** fresh APK. The executed count matches the source-level `@Test` inventory exactly (321).
- Focused verifications during the session: DatabaseMigrationTest 7/7, BackupValidationTest 29/29 (+depth guard), backup/crypto suites 53/53, the five Prompt-10-affected suites 94/94.
- Resource note: one gate launch was stopped safely (own process) at 1.5 GB free RAM and relaunched at ≥4 GB, per the resource policy.

## Release readiness (lead assessment)

- **Development readiness: GO** — automated evidence covers every feature area; migration/backup compatibility proven from every historical schema era; no unresolved Critical/High findings; security audit clean of Critical/High.
- **Production release readiness: CONDITIONAL GO** — the manual device checklist (TalkBack, fontScale 2, device contrast, DatePicker flows, install/upgrade paths, alarm timing) has NOT RUN and must be executed on hardware before publishing, or shipped with the gaps marked. Prompt 12 owns that decision.

# Session 12 (Prompt 10/12) — Final visual polish, responsiveness, cross-feature integration

## What was built (this session, uncommitted → committing at end)

- **Final polish audit** (ui-ux-specialist, verified Prompt 9 fixes hold) → 12-file pass, all implemented: hairline two-tier convention documented (`rule` = register wells, `outlineVariant` = cards/dividers/inputs) + the check-in well gained its missing rule border; radius ladder 4/6/8/10 completed (dialog shells 16→10, FreshStart 12→10, reminder pill 16→10); decorative emoji cleared from TalkBack at six sites (HabitCard both layouts, HabitDetail stamp, ActivityDialogs, Analytics rows ×2, Today empty-state); RecordingStrip cells expose `Role.Button`; AA-contrast swaps computed from token math (Achieved "✓", Analytics "+X pp", ANCHOR caps, Reset confirmation → onSecondary/onTertiaryContainer — raw secondary read 3.6:1 and tertiary 3.0:1 in light); Today date-row seal grows with wrapped text (`IntrinsicSize.Min` + `fillMaxHeight` — fontScale-2 fix); per-recomposition `DateTimeFormatter` construction hoisted; Schedules quota walk remembered; heatmap's two competing scroll effects merged (snap once on first layout, animate on selection/range changes — no flicker path); `MoodSelector`/`TrackingModeSelector` modifier-parameter reorders clear the last Compose lint style findings.
- **Cross-feature verification** (integration-engineer, 10 pairs traced): 8 CONSISTENT; 2 Low issues found and **fixed**:
  - **Issue A** — Smart-Streaks decision block was reachable for weekly-quota habits (a chain-less habit showed "keeps the chain alive"; decisions sat inert and would apply retroactively on a switch back). Fixed with the freeze-path pattern: block hides at mode-at-date == WEEKLY_QUOTA (UI) and `setStreakProtection` throws "Weekly-quota habits do not use day streaks." (repository mirror).
  - **Issue B** — celebration-driver race: a stale achieved-but-unacknowledged milestone in a reset/import transition could celebrate and re-persist its ack row. Fixed: `celebrationSuppressed` (@Volatile) set BEFORE the transactions — reset suppresses with celebrate-mode-after (fresh journey re-celebrates re-earnings), import seeds flags before the restore (silent acks) — driver skips while suppressed, `finally` clears on failure, and the ack/emit loop re-checks the flag mid-flight.
- **Responsiveness**: fontScale-2 seal fix; the 85% `LocalWindowInfo` viewport caps (Prompt 9) verified compatible with landscape-relevant dialog sizing; remaining responsive checks are device-QA (Prompt 11).
- **Known Robolectric limitation recorded**: the M3 DatePicker **day grid never composes under Robolectric** (header + buttons render; the grid contributes zero semantics nodes) — calendar-driven past-date navigation is untestable in CI. The two picker-driven UI tests were replaced: the quota guard is pinned at the repository (rejection test) and engine (era-resolution pins) levels plus a navigation-free smoke (quota partial-day well renders without chain language). Device QA must cover both pickers (Prompt 11).

## Test evidence

- **Final: `BUILD SUCCESSFUL in 16m 30s` — testDebugUnitTest 311/311 across 25 suites + lintDebug 0 errors + assembleDebug, all green** (+7 tests vs Prompt 9's 304: quota streak-protection rejection, engine era-resolution pin, achievements quota pin, celebration race guard + fresh-journey re-celebration, icon semantics, RecordingStrip role contract, quota-well smoke).
- One gate launch was stopped safely by the lead (own process) when free RAM dipped to 1.5 GB mid-run; relaunched at 4.7 GB — resource policy followed, no unrelated processes touched.
- Limits verified live: 2 workers, `--no-parallel --no-daemon`, pinned `-Xmx3g`, BelowNormal priority verified on the build JVMs, RAM monitored throughout.

## Reviews (this session)

- `ui-ux-specialist` audit: backlog verified item-by-item with exact specs; contrast computed from tokens (4 failing small-text sites fixed; skip-dash icon and unmarked-ring noted as intentional quiet treatments for device measurement); fresh sweep found the formatter hoists and confirmed celebration restraint, empty states and edge-to-edge coverage.
- `integration-engineer`: 10 cross-feature pairs traced — 8 CONSISTENT, 2 Low issues fixed (above); also verified the polish diff introduced no cross-feature regressions and flagged the two coverage gaps that became tests.
- `independent-reviewer` (+ security-privacy lens): PASS WITH FINDINGS — 2 actionable items fixed pre-commit (celebration suppression cleared in `finally` on reset failure; mid-emission re-check before each ack/emit) and the debug dump artifact removed; security clean (no logging, no permissions/workers, no secrets in fixtures); radius ladder and remember-key completeness verified across all of `ui/`.

## Deferred to Prompt 11 (device QA + integration)

- Physical-device verification (NOT performed — no device was touched): TalkBack traversal (merged toggleable rows, single-node heatmap + auto-scroll, emoji-cleared name-only announcements), large-font reflow at fontScale 2 (IntrinsicSize seal, dialog viewport caps), contrast measurement on device (dynamic-color tokens especially; skip-dash icon and unmarked ring are intentional quiet treatments), both DatePicker flows (grid untestable under Robolectric), M3 dialog IME behavior on the two custom shells.
- Inline-emoji announcements inside labeled strings and celebratory snackbar emoji: P11 listening pass.
- SettingsAction disabled-row dimming, color-swatch 48dp target enlargement, spacing-rhythm documentation: recorded, low priority.
- Prompt 11 = integration and device QA; Prompt 12 = release versioning, signing, publication.

---

# Session 11 (Prompt 9/12) — UI/UX Refinement Phase 1: consistency, navigation, accessibility, performance

## What was built (this session, uncommitted → committing at end)

- **Comprehensive audit first**: ui-ux-specialist reviewed every screen against ten criteria → 1 Critical + 5 High + ~10 Medium findings with an implementation plan; architecture-specialist then reviewed the implemented diff (PASS WITH FINDINGS — two perf gaps it caught were fixed the same session).
- **Design system**: shared `ui/components/AlmanacStat.kt` replaces three near-duplicate private "stat well" composables (HabitDetail StatCard, ActivityDialogs Stat; AnalyticsScreen's ruled-panel MetricCard kept, documented); version no longer hardcoded — Settings reads the package version (About row + dialog).
- **Navigation**: Today's milestone card navigates to Achievements (`NextMilestoneCard` gained `onClick`, wired through LifeTrackApp; a WORK_PROGRESS session-8 claim that this existed was a regression — re-applied); Analytics "View all" gets a chevron + `launchSingleTop` parity; Schedules got a real `TopAppBar` + `state.today` (no more `LocalDate.now()` in screens); Activities' center date-tap now opens the date picker with a "Back to today" reset — same semantics as Today.
- **Today restructure**: one merged date row (the displayLarge date itself opens the calendar, prev/next chevrons flank it); reflection + next-milestone cards moved BELOW the habit cards (tasks before context); per-habit scheduling facts hoisted into a remembered map (`HabitScheduleFacts`) so the engine runs once per state emission, not per item recomposition; derived collections (`completedDates`, `categories`, `due`, `habits` filter) remembered.
- **Forms and destructive actions**: one-tap status save only CREATES a check-in — overwriting an existing day's status requires the Save button; "Clear check-in" confirms (two-step, distinct "Clear it" label) and warns it removes notes + Smart Streaks decisions; "Clear entry" arms an in-place two-step confirm inside the same dialog window; the editor surfaces `operationError` inline with a live region; ActivityEditorDialog Cancel busy-gated.
- **Accessibility**: heatmap grid collapsed from ~371 TalkBack stops to ONE `clearAndSetSemantics` node with an auto-scroll-to-selected effect (touch unchanged); bottom-bar icons stop double-announcing; `PreferenceSwitch` + editor reminder rows are row-wide `toggleable` (Role.Switch, enabled forwarded); theme dialog rows carry radio roles; color swatches announce names ("Selected: Amber") instead of hex; app-lock failure text is a live region with a prompt-in-flight spinner; long-press label corrected to "Edit check-in and note"; remaining ISO date leaks formatted (Started/Starts/next-scheduled/freeze dialog).
- **Performance**: Analytics `rate()` calls remembered (rings + ComparisonCard); HabitDetail streaks/quota/weekly-rhythm remembered (quota habits skip streak walks entirely); Activities converted to a LazyColumn with hoisted per-activity entry lookups; achievements collection is lifecycle-aware.
- **Dialog architecture**: `ActivityLogDialog` converted to the app's custom dialog shell (LogEditorDialog idiom) with a viewport cap from `LocalWindowInfo.containerSize` (density-converted, 85%) — replacing `LocalConfiguration.screenHeightDp` per Compose lint guidance; HabitDetail's LogEditorDialog got the same cap conversion.

## Dialog measure/idle-loop root cause (recorded for future sessions)

Robolectric UI tests hung on `AppNotIdleException` ("infinite composition") whenever a `Material3 AlertDialog` opened on the Activities screen, regardless of what was stacked on top (platform or custom overlay). Systematic elimination (dialog inventory → stacked-window matrix → viewport capping → final fix) traced it to the **platform-default-width derivation measure loop**: `AlertDialog` deriving its width from the platform window measure-loops inside narrow test windows. The fix that works: custom `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` + explicit Surface shell (the LogEditorDialog idiom), with content height capped from `LocalWindowInfo`. Two stacked windows on a platform AlertDialog never settle; stacked on the custom shell they do (HabitDetail's confirm does exactly that and passes). Test-side, `performScrollToNode` on long LazyLists proved nondeterministic in a 320dp viewport — the reflection/milestone tests use bounded gentle swipe probes with center-in-viewport gates instead.

## Test evidence

- **Final: `BUILD SUCCESSFUL` — testDebugUnitTest 304/304 across 25 suites + lintDebug 0 errors + assembleDebug, all green** (+8 tests vs Prompt 8's 296: heatmap single-node a11y, milestone tap-through, editor mode/parameter draft restoration, Today quota card, clear-confirmation flows, status-overwrite guard, reminder-row toggle, reminder-policy grid/quota, Settings switch semantics).
- Two Robolectric suites first ran against drifted expectations (5 failures) — all repaired, plus 3 debug rounds on the dialog idle loop; focused suites re-verified green (EditorRecoveryTest 18/18, AnalyticsScreenTest 6/6) before the full gate.
- Limits verified live: 2 workers, `--no-parallel --no-daemon`, pinned `-Xmx3g` (≥4 GB free at gate time), BelowNormal, RAM monitored throughout.

## Reviews (this session)

- `ui-ux-specialist`: the audit itself (above) — its own High findings (stat-well duplication, milestone dead-UI regression, version hardcoding, date-row merge, viewport cap, instant-save overwrite, editor error surface, heatmap flood, app-lock live region, rate recomputation) all implemented.
- `architecture-specialist` on the implemented diff: PASS WITH FINDINGS — 2 perf gaps fixed (per-item engine reads → `HabitScheduleFacts`; WeeklyRhythm remembered), streak walk skipped for quota habits, `AlmanacStat` padding parameter dropped (YAGNI), argument-order warning KDoc on MetricCard, navigation-option parity, editor toggleable `enabled` forwarded.
- `independent-reviewer` (+ security-privacy checklist): PASS WITH FINDINGS, all Low/Info — stale invariant comment reworded, two ungated Cancels busy-gated, Schedules quota walk + heatmap double-LaunchedEffect noted as deferred polish; security clean (no logging, no new permissions/workers, passwords still non-saveable, CSV/backup untouched); remember keys verified complete; test assertions verified non-vacuous.
- New lint warnings from this diff resolved: `ConfigurationScreenWidthHeight` ×2 fixed via `LocalWindowInfo`; the two `ModifierParameter` warnings are pre-existing style findings in untouched files (deferred — reordering public parameters is churn without value this phase).

## Deferred to Prompt 10 (final polish)

- Color/hierarchy polish: hairline border token unification (`outlineVariant` vs `rule`), non-token radii (12/16dp stragglers), decorative emoji cleared from TalkBack (HabitCard/Detail/Activity icon boxes), recording-strip cells missing `Role.Button`.
- `LocalWindowInfo`-era cleanup: SchedulesScreen quota walk remember; heatmap snap-to-end vs animate-to-selected flicker on relayout.
- Dynamic-color terracotta contrast (needs device measurement); today displayLarge date-row 44dp fixed seal at fontScale 2.
- `AnimationSpec`/skeleton loading states, responsive behavior across device sizes, final integrated visual QA.
- Device QA (NOT performed — no real device was touched): TalkBack traversal on merged toggleable rows + the single-node heatmap, large-font reflow on short viewports, contrast measurements.

---

# Session 10 (Prompt 8/12) — Flexible Scheduling: complete UI and integration

## What was built (this session, uncommitted → committing at end)

- **Habit Editor**: "Schedule" mode selector (Selected weekdays / Every N days / X times per week) using the Smart-Streaks selectable-row idiom (48dp, Role.RadioButton, merged semantics); interval field 2–365 with next-date preview + "runs from {anchor}" explanations (all dates resolved by `Scheduling.nextScheduledOn/intervalAnchor` — no date math in Compose); weekly sessions 1–7 with Monday–Sunday explanation; quota habits disable the reminder switch and save `reminderMinutes = null` (a quota reminder never fires); editor preview habit mirrors `saveHabit` revision seeding exactly (new habits seed at their start date, existing parameter changes re-anchor to today). Drafts survive restoration (pinned by a `StateRestorationTester` test).
- **Today**: schedule-aware cards — interval rest days show "Next scheduled {date}", quota cards show "This week: {c} of {r} · Achieved | {n} session(s) to go | Started mid-week | Missed" ("Week of {d MMM}: …" on navigated past weeks); quota habits hidden from the "Due" filter, skip/missed quick actions and the nudge highlight; header register counts daily-due habits only (`Analytics.dueOn`); LogEditorDialog offers only Completed/In-progress for quota habits (no chain vocabulary on days that have no chain).
- **Habit Detail**: Schedule block (mode, "Every N days"/"From {date} · next scheduled {date}", "{n} sessions per week · Monday through Sunday"); quota stat cards "This week"/"Week status" replace streak cards; WeeklyRhythm rows replace CompletionTrend for quota habits (mode-at-week gated, empty-state line when no quota week exists yet); freeze button hidden for quota (mode checked at the freeze date, matching the repository guard).
- **Schedules**: mode-specific summaries replace the weekday grid for flexible habits; quota habits show "Flexible days · no daily reminder".
- **Analytics**: canonical engine only; footnote explains weekly habits stay out of daily rates/heatmap; leaderboard untouched (quota streaks are 0 by design).
- **CSV**: real scheduling columns (`habit_schedule_mode`, `habit_interval_days`, `habit_interval_anchor`, `habit_weekly_target`) resolved per row date by the engine — log rows render empty for parameters not in force that date; the placeholder weekday-grid rows are gone; RFC 4180 + formula-injection guard unchanged.
- **Reminders**: `DailyCheckInReminderWorker` now uses `Analytics.unmarked` (quota-safe) — weekly habits never appear in "still open" notifications; verified widget/backup-reminder surfaces clean.

## Engine fixes found and fixed during UI work (all pinned by tests)

- `Scheduling.mode` leaked the current mode into weekday-era history after weekday→flexible switches (firstOrNull fallback) — null-mode revisions are now weekday periods; pre-revision dates on habits with history resolve WEEKDAYS. (Prompt 7 latent bug; retroactive-ineligibility class.)
- `Analytics.dueCount` EVERY_N_DAYS counted a phantom grid date on segment boundaries (off-by-one vs the half-open `[a,b)` convention) — inflated interval rates; fixed + calendar-reference test.
- `Scheduling.weeklyQuotaStatus` counted completions against OTHER habits' pauses (any archived habit poisoned all quota counts from its archive date) — habit-scoped now.
- `Analytics.rate` could exceed 100% after a quota→weekday switch — quota-era completions now sample the mode at their own date.
- Born-flexible habits with backdated start dates invented daily dues before their first revision — `saveHabit` seeds new-habit revisions at `habit.startDate`; editor preview mirrors it.
- Backup import validation gaps: habit-level interval/weekly-target range checks (2..365 / 1..7) added at habit AND revision level; revision resolution repaired to match the engine (`revision.mode ?: WEEKDAYS` — the old `?: habit.scheduleMode` fallback false-rejected every weekday→quota switch save; test-pinned both directions).

## Test evidence

- **Final: `BUILD SUCCESSFUL in 11m 52s` — testDebugUnitTest 296/296 across 25 suites + lintDebug (0 errors, 28 warnings, 1 hint) + assembleDebug, all green** (+33 tests vs Prompt 7's 263: engine history/quota tests, CSV columns, backup v9 gates, repository mode-switch round trips, editor restoration, Today quota card, reminder eligibility).
- Limits verified live: 2 workers, `--no-parallel --no-daemon`, pinned `-Xmx3g`, BelowNormal priority, free RAM 4.5–5.2 GB monitored; Robolectric suites ran at the full pinned heap as required.

## Reviews (this session)

- `ui-ux-specialist`: 11 findings, all applied — H1 (log editor bypassed the quota skip/missed exclusion with chain language), M1 (contradictory disabled-switch state), M2 (jargon "anchor" copy), M3 ("days to go" → "sessions to go"), M4 (missing year in next-date), M5 ("Missed" for arithmetically impossible weeks → "Started mid-week"), M6 (internal "quota" vocabulary), L1–L4 (live-region discipline, empty "0 of 0" register, past-week label, partial-days note).
- `security-privacy-specialist`: **PASS, no High/Medium** — injection guard covers the new columns, no new PII class, backup validations reject-only, app-lock regression clean, reminder posture narrowed; 4 informational Lows (stale working logs, documented schedule-timeline sensitivity, corrupt-state cosmetics, tolerated unknown revision modes).
- `independent-reviewer`: 2 High + 3 Medium **all fixed with tests** (foreign-pause quota contamination; dueCount interval off-by-one; rate >100% after switch; pre-quota weeks "Missed"; backdated-start dues) + 5 Lows documented as residuals; backup/history/quota contracts verified end-to-end; suite confirmed non-vacuous (296 declarations).

## Residual / deferred (Prompt 9+/device QA)

- Weekly week-walking streaks and a weekly nudge reminder: deliberately deferred in the engine KDoc — need product decisions with their UI (Prompt 9 candidate).
- Independent-reviewer Lows: `claimReminder`/policy guard harmonized; `saveHabit` overwrites `intervalAnchor` on unrelated saves of revision-free interval habits (import-only corner); CSV schedule-change timeline is inferable (same sensitivity class as existing per-row goal data); unknown revision-mode strings tolerated by validation (fail-safe).
- Device QA: TalkBack on the new selector/summary lines, large-font reflow of scheduleNote lines, celebration-free mode switching.
- Known gap unchanged: README/UPGRADE refresh deferred to the release prompt.

# Session 9 (Prompt 7/12) — Flexible Scheduling: domain engine, persistence, integration

## What was built (this session, uncommitted → committing at end)

- **Three schedule modes**: WEEKDAYS (default, all existing habits unchanged), EVERY_N_DAYS (date-grid recurrence from an explicit anchor, LocalDate/epochDay arithmetic — month/leap/year safe, never elapsed millis), WEEKLY_QUOTA (X completions per ISO Mon–Sun week on any day; distinct period-based metric, never folded into daily denominators).
- **Data layer** (schema **10** via additive MIGRATION_9_10, `10.json` exported): Habit gains scheduleMode/'WEEKDAYS' default, intervalDays, intervalAnchor, weeklyTarget — all `@EncodeDefault(NEVER)` so plain exports stay key-free; ScheduleRevision gains mode/intervalDays/weeklyTarget (nullable, key-free for weekday revisions). Backup **v9** gate: required only when a habit carries flexible configuration; old formats decode with WEEKDAYS defaults.
- **Engine** (`domain/Scheduling.kt`): mode/interval/anchor/quota resolvers from schedule history (per-date), grid eligibility, next-grid-date, weekly-quota status (achieved/ongoing/missed/rest with pause-aware eligible days).
- **Analytics mode-aware**: eligible dispatches per mode; dueCount per-segment dispatched (EVERY_N_DAYS counts grid dates per segment; WEEKLY_QUOTA contributes no daily dues); rate() excludes quota habits entirely; currentStreak/bestStreak return 0 for quota habits (no fabricated day-streaks).
- **Repository guards**: editableHabit mode-aware (INTERVAL grid-only, QUOTA any day); useFreeze rejects quota habits; claimReminder excludes quota habits (no daily overdue reminders — Prompt 8 may add a weekly nudge); ReminderPolicy same exclusion.
- **CSV**: renders the placeholder weekday grid for non-WEEKDAYS modes (flagged for Prompt 8 polish).

## Test evidence

- **Final: `BUILD SUCCESSFUL` — testDebugUnitTest 263/263 across 25 suites + lintDebug, all green** (13 new SchedulingTest tests; all prior suites unchanged and green; schema 10.json exported with the new columns verified).
- Limits verified live: 2 workers, `--no-parallel --no-daemon`, capped `-Xmx2500m`, in-process Kotlin compile, BelowNormal, free RAM 2.8–4.9 GB monitored.

## Reviews (this session)

- `product-requirements-analyst`: decision matrix + edge cases (interval boundaries, quota states, reminder contract) — incorporated.
- `independent-reviewer`: confirmed the WEEKLY_QUOTA daily-denominator exclusion and identified two Critical/High gaps fixed this session — decode gate 1..9 (was 1..8: v9 backups were unrestorable) and the missing EVERY_N_DAYS dueCount branch (interval habits silently computed weekday dues) — plus import-validation additions and quota-habit exclusions in rate/daily-reminder.
- Known residual: standalone bottom-scroll Robolectric fragment from Prompt 4 deferred to device QA; CSV renders the placeholder weekday grid for non-WEEKDAYS modes (Prompt 8 polish).

## Deferred (Prompt 8/12 — Flexible Scheduling UI)

- Schedule-mode selector, interval/quota parameter fields, next-eligible-date display, weekly progress cards.
- Editor behavior for mode switching (placeholder weekday selection is recoverable from the seed revision).

---

# Session 8 (Prompt 6/12) — Achievements interface complete

## What was built (this session, uncommitted → committing at end)

- **AchievementsScreen** (new route "achievements" from Analytics): earned/total summary with percentage + progress line, "Recently unlocked" list from acknowledgment timestamps, four category sections (Habit completion, Consistency, Activity progress, Daily reflections), achievement cards (category icon seal, title, description, ✓ Achieved / locked "current / target" + progress bar, TalkBack state descriptions), detail dialog (exact requirement per id, progress, remaining, Smart Streaks + protected-partial explanations for streak/completion achievements), empty state for brand-new users.
- **Celebrations**: VM celebration driver — milestones earned after the feature's introduction fire exactly one snackbar + confetti (respects the animations toggle); milestones predating it are marked seen silently on the first evaluation (no fabricated unlock events); re-earning after data loss does not re-celebrate while the session acknowledgment set holds, and reset clears it so a fresh journey celebrates again.
- **Today**: compact NextMilestoneCard (locked achievement closest to completion), placed after the reflection card, hidden during fresh-start onboarding.
- **Analytics**: achievements summary row (Milestones X/Y + Earned % + "View all" entry point).
- Deferred to later prompts: per-achievement unlock-date display (needs the derived earnedAt calculation), mood-free celebration copy fine-tuning.

## Test evidence

- **Final: `BUILD SUCCESSFUL` — testDebugUnitTest 250/250 across 24 suites + lintDebug, all green** (Prompt-5 engine suites + all regression suites unchanged and green).

## Reviews (this session)

- `frontend-engineer`: AchievementsScreen.kt (single file; caught its own remember-key pitfall: identity-keyed arrays would reset drafts per keystroke — fixed to value keys).
- `ui-ux-specialist`: **H1** (Analytics call site never passed the achievements list — screen unreachable; fixed by threading `vm.achievements` through LifeTrackApp), **M2** (NextMilestoneCard now navigates to the register), **M3** ("· seen" suffix removed — internal vocabulary), **M4** (state line no longer repeats the title in TalkBack), **M5** (large-font reflow deferred with device QA noted), L1 (TaskAlt seal avoids Today-tab icon collision), L2 (HorizontalDivider idiom), L3 (dialog Close 48dp), L4/L6 documented as product calls — applied.
- `independent-reviewer`: findings applied where in scope for this UI phase (see engine-phase review under Prompt 5 notes).

## Session-5 (Prompt 5/12) summary — see below; engine + acknowledgment persistence landed there

---

# Session 6 (Prompt 4/12) — Mood Tracking + Daily Journal UI

## ✅ Resolution note (2026-11-18)

The diverged-remote blocker recorded below was **resolved by the user**: my Prompt-4 UI was merged (`fba79df` "merge: preserve v1.8.0 preview workflow with Prompt 4 UI"), v1.9.0 was prepared and released, the `v1.9.0-preview.1` tag was published with a CI-built preview APK, and `main` now carries the full lineage including this session's work. The original blocker text is preserved below for the record.

## What was built

## What was built

- **Today**: `ReflectionEditorCard` for the selected date (today or any past date via existing date navigation) — mood selector (five 48dp selectable circles, Role.RadioButton, per-chip meaning), optional journal (2000-char cap with monoData counter), Save gated on change + content, Cancel, Remove behind a confirmation dialog. Drafts use `rememberSaveable` — they survive scroll-away, tab switches and process death. Empty days show an invitation + "Add a reflection" (no auto-opened text field, which fought list scrolling).
- **Activities chronology**: day groups show the day's mood (emoji + label, only when present) and journal (Ellipsis) — read-only, no duplication.
- **Analytics**: "Moods · last 28 days" panel — Days with moods / Avg on habit days / Avg on quiet days + definitional footnote (habit/quiet-day definitions, 1–5 scale) + causation disclaimer + oldest-to-newest emoji/label/date list. Journal text deliberately kept out of Analytics.
- **Settings**: "Export data" subtitle now warns that plain JSON files contain notes and journals unencrypted.

## Test evidence (session 6)

- **Final: `BUILD SUCCESSFUL` — testDebugUnitTest 226/226 across 23 suites (4 new compose tests: invitation, mood-only save + remove-guard, saved read-back + confirm-before-remove, cancel-restores; plus the rewritten short-screen scroll test and reorder scroll fix) + lintDebug, all green.**
- Regression: Smart Streaks, App Lock, CSV export, backups, migrations all green.

## Reviews (session 6)

- `frontend-engineer`: ReflectionWidgets.kt (remember-key correctness fix caught pre-commit: identity-keyed arrays would have reset drafts per keystroke).
- `ui-ux-specialist`: H1 (plain-remember draft loss on scroll → rememberSaveable), M1 (journal ellipsis), M2 (definitional footnote), M3 (chip semantics), M4 (error color for Remove), M5 (48dp TextButtons), M6 (dropped redundant contentDescriptions), M7 (today/past titles + Cancel on empty days), L1/L2 (counter style, color alignment) — all applied.
- `independent-reviewer`: M-1 (card/panel test coverage → ReflectionUiTest 4 tests), M-2 (scroll-test bottom coverage → two-phase scroll), L-1/L-2 applied; I-1 locale format noted; M-3 (date-nav draft loss) documented as a lead product call (see below).

## Product decisions & residual risks (session 6)

- **Date navigation mid-edit resets the reflection draft** (no dirty guard on the date controls): deliberate — date controls are primary navigation; a guard there would fight every use. Documented; revisit if QA shows friction.
- **No mood un-select affordance**: journal-only after mood+journal requires removing the whole reflection. Documented; revisit with Prompt 4 QA.
- **Standalone bottom-scroll Robolectric test omitted**: scrolling from the reflection position to the last habit item trips a LazyColumn/reorderable interaction under Robolectric ("node no longer in the tree") regardless of target; bottom reachability is covered by the reorder test's scroll instead. Device QA will confirm real scrolling.
- Device QA: TalkBack on mood chips/live regions, emoji rendering at fontScale 2, journal drafts across process death.

## Earlier sessions (2026-11-15/16/17) — CSV export, directional tracking, app lock, Smart Streaks, mood/journal data layer: see logs below

---

# Session 5 (Prompt 3/12) — Mood Tracking + Daily Journal data layer

## What was built

- `DailyReflection` entity (schema **8** via additive MIGRATION_7→8): one optional mood (1–5) and/or journal (≤2000 trimmed) per local calendar date; date-scoped, no habit FK; historical dates allowed, future rejected at save and at import.
- Repository APIs: `setReflection` (both-ends date bounds at save, mood reject-don't-clamp, trim-then-takeIf journal, at-least-one required, monotonic timestamps), `clearReflection` (idempotent), `reflections` flow; VM exposure (`state.reflections`, `setReflection`/`clearReflection` wrappers) ready for Prompt 4 UI.
- Backup format **v7**: required only when reflections exist; plain v3–v6 exports stay key-free (`@EncodeDefault NEVER`); per-row validation (date bounds, mood range, journal length, at-least-one, timestamps, date uniqueness); imported journals normalized like saved ones.
- Integration: `importFrom`/`resetAllData` explicitly clear reflections (date-scoped table has NO habit FK — the only such table besides freeze_usage; the key catch from the analyst).

## Test evidence

- **Final: `BUILD SUCCESSFUL` — testDebugUnitTest 222/222 across 22 suites (27 new: 17 repository, 10 backup) + lintDebug, all green.**
- Migration chain test extended to v1→8 with a post-migration reflection INSERT/READ check; STRICT-parity and old-format round trips pinned.

## Reviews (sequential per the 1-agent concurrency limit)

- `product-requirements-analyst`: confirmed the single-entity design, the import/reset clearing gap (fixed before commit), trim normalization, reject-don't-clamp moods, export-ordering pitfalls.
- `security-privacy-specialist`: **PASS, no High/Medium** — no permission/logging/widget/CSV/backup-rule widening; journal never reaches CSV or logs; import/reset atomicity verified; INFO item fixed (validateReflection dead-helper dedup); LOW items documented (plaintext journals in plain backups → Prompt 4 export-UI note; bidi/control chars → Prompt 4 sanitization).
- `independent-reviewer`: no High/Medium; L1 import-normalization asymmetry → fixed; L2 old-format-restore test gap → test added; L3 AGENTS.md staleness → fixed; I2 mood-as-string pinned as coerced-then-range-checked.

## Residual / deferred

- Plaintext journals in plain JSON backups: same posture as notes; Prompt 4 adds an export-UI note recommending encrypted backups.
- Bidi/control-character stripping for imported journals: Prompt 4 (render-time or save-time sanitization).
- CSV export excludes reflections (deliberate — journals are the most personal text; revisit scope later).
- On-device QA and Prompt 4 UI remain.

---

# Session 4 (2026-11-17) — Smart Streaks Phase 2: configuration UI + manual decisions

## What was built (this session)

- **Habit Editor**: "Smart Streaks" section — Strict/Automatic/Manual selector (selectable rows, Role.RadioButton, 48dp targets, TalkBack-merged), threshold field for AUTOMATIC (digits-only, suggested 75, inline error with Polite live region, save gating 1..100), per-mode explanations + honesty line ("changes apply to your history"), dirty tracking, and a save expression that can never block on a hidden out-of-range draft (falls back to the stored value).
- **Habit Detail**: Smart Streaks block inside the check-in well for past partial days — AUTOMATIC shows the threshold in day units ("X of Y needed") + only the differing override button; MANUAL shows both Protect chain / Break chain; an explicit decision shows "You decided: …" (Polite live region) + "Remove my decision". Decisions persist across policy changes and are visible/removable even under Strict (deliberate semantics; editor copy states it).
- **Integration hardening**: `clearLog` also deletes the day's streak decision (clean slate for re-entered partials); dead DAO bulk query removed; the "configured habit" predicate centralized in `domain/StreakPolicy.isConfigured`.

## Test evidence (session 4)

- **Final: `BUILD SUCCESSFUL in 11m 4s` — testDebugUnitTest 193/193 across 20 suites (2 added this session: automaticBridges helper, clearLog row deletion) + lintDebug, all green.**
- Regression coverage confirmed: CSV export, directional tracking, app lock, Room migrations (v1→7), JSON backups v1–v6, editor recovery.

## Reviews (this session)

- `frontend-engineer`: editor UI (single file, accessibility pattern from TrackingModeSelector, save gating).
- `ui-ux-specialist`: H1 (stale decisions invisible under Strict → detail gate widened + editor copy), M1 (threshold copy in day units), M2 (error folded into supporting text + live region), M3/M4 (prose dedup + per-selection retroactivity line), L1–L4 (button heights, live region, wording, clearLog semantics) — all applied.
- `independent-reviewer`: verified all mission-checklist items clean (bridge/gap interplay, row leakage, export combos, schema vs migration, partial-at-target invariant, call sites, wiring); findings applied: M1 (editor hidden-draft save block → fallback-to-stored expression), M2 (three untested behaviors → 2 tests added + EditorRecoveryTest UI cases deferred), Low (automaticBridges KDoc, dead DAO query removed).

## Residual risks / device QA (Phase 2)

- TalkBack traversal and live-region announcements on real devices; terracotta contrast on dynamic-color themes.
- Retroactive re-evaluation is deliberate (always-current threshold); Phase 2 UI copy states it — monitor user feedback.
- Missed (log-less) days cannot be protected by design — the freeze is the remedy; revisit if QA shows demand.
- EditorRecoveryTest has no Smart-Streaks-specific UI cases (radio/threshold interactions) — candidate for Prompt 3.

---

# Session 3 (2026-11-17) — Smart Streaks Phase 1 (domain/db/repository; config UI deferred to Prompt 2/12)

## Design decisions (lead, informed by product-requirements-analyst)

- Threshold is **always-current** (read-time `partialCreditPercent`), consistent with the codebase's retroactive schedule pattern; MANUAL rows are the stability escape hatch. Phase 2 UI copy must say threshold changes apply to history.
- **Zero-progress logs never bridge, even via stale manual rows** (SKIPPED already covers zero days; protects the freeze economy).
- Integer bridge comparison: `progress * 100 >= partialCreditPercent * goalOn(date).targetCount` (inclusive boundary — 6/8 at 75% bridges).
- Protection rows are kept-but-inert when a day later becomes COMPLETED or the log is cleared (undo reactivates); FAILED always breaks regardless of rows.
- `protected` is a Kotlin keyword — the column/field is `protectChain`.

## Work in flight

- Lead: data layer landed (Models policy fields + `StreakProtection` entity, MIGRATION_6→7, AppDatabase v7, DAO surface, Backup v6 gates + validation, export/import wiring).
- backend-engineer: `domain/StreakPolicy.kt` engine + `Analytics.currentStreak/bestStreak` protections integration + `HabitRepository` policy validation and set/clear-protection APIs (in flight).
- quality-analyst: focused tests — StreakPolicyTest, StreakAnalyticsTest, StreakProtectionRepositoryTest (in flight, parallel, non-overlapping).
- Pending: db-migration-specialist verification of 7.json, VM/UI threading (lead), security-privacy + independent-reviewer, full suite + lint.

## Session-2 summary (2026-11-16/17) — app-lock hardening, all four review findings resolved

## Commits

| Commit | Content | Status |
|---|---|---|
| — | feat: opt-in biometric app lock (schema 6, gate, FLAG_SECURE, local-only setting) — implemented in the frozen state | uncommitted → committed this session |
| — | Stage-1 hardening: widget lock enforcement, prompt-churn elimination, reset consistency, lint fixes, draft-survival evidence test | uncommitted → committed this session |

GitHub push was **unblocked** between sessions (credentials completed): remote head matched local `b855d1e` at session start.

## Stage-1 fixes (verified by the full unit suite below)

1. **Lint errors — both fixed, no suppressions:** `local.properties` drive-colon escaping (`PropertyEscape`, my own file); `AppLock.kt` uses `LocalActivity` instead of casting `LocalContext` (`ContextCastToActivity`).
2. **Widget bypass closed** (frontend-engineer + lead): `LifeTrackWidget.provideGlance` checks `appLockEnabled` BEFORE reading any habit data — a locked device renders a placeholder with zero habit content in memory; `CompleteHabitAction.onAction` is inert while locked. Reminder notifications keep showing habit names (pre-existing documented trade-off).
3. **Backgrounded prompt churn eliminated:** `authenticate()` guard against double-tap; `ERROR_CANCELED`/non-resumed-success generation bumps gated on host STARTED; ON_RESUME observer re-offers the sheet once per return.
4. **Editor drafts — prior finding REFUTED with evidence:** `AppLockGateTest.routeBackedDraftsSurviveGateUnmountAndRemount` mirrors the NavHost `SaveableStateProvider` pattern and PASSES — route-content `rememberSaveable` survives the gate's unmount/remount. Residual: device QA still recommended.
5. **Reset consistency:** `resetAllData()` preserves the local `appLockEnabled` (never silently weakens security); pinned by the extended reset test.

## Session-2 test results

- **Final Stage-2 verification: `BUILD SUCCESSFUL in 7m 6s` — testDebugUnitTest 156/156 (17 suites) + lintDebug, all green after every fix.** Regression check for CSV export, directional tracking, migrations, JSON backups ✓.
- Security-privacy-specialist verified fixes 1–3 and caught a vacuous draft test; the remedy (hoisted `SaveableStateHolder` above the gate in LifeTrackApp) makes editor drafts survive re-lock for real, and the test now mirrors the shipped structure.

## Session-2 residual items (device QA / future)

- Real BiometricPrompt sequencing on hardware (stop-during-prompt race, OEM quirks) — reasoned from library sources, not devices.
- Reminder notifications still show habit names on the lock screen (VISIBILITY_PRIVATE hides them behind device unlock — pre-existing trade-off).
- Settings About text and versionName still say 1.7.0 — release plumbing for the 1.8.0 bump.

---

# Session 1 (2026-11-15) — CSV export + directional progress tracking

## Completed work and commits

| Commit | Content | Status |
|---|---|---|
| `1c2d5af` | feat: CSV export — RFC 4180 + formula-injection guard, Settings entry, focused tests | local, verified |
| `bb76877` | docs: project AGENTS.md — specialist agent routing, file ownership, resource policy | local |
| `89aec4a` | feat: directional progress tracking in the Activities ledger (schema 5, backup v5, domain rules, UI, tests) | local, verified |
| *(final commit)* | review fixes (ExerciseTrace NaN crash guard, editor baseline/number gating, TalkBack selector rework, percent clamps, copy fixes), README/UPGRADE/AGENTS.md refresh, 2 new regression tests | working tree → committed at end of session |

## Test results (verified this session, this machine)

- CSV export: `CsvExportTest` 9/9 + `HabitRepositoryTest` CSV method 1/1 (`BUILD SUCCESSFUL in 7m 21s`, capped `-Xmx1200m`).
- Directional tracking focused suite — **final: 27/27 green** (`BUILD SUCCESSFUL in 6m 27s`, pinned `-Xmx3g`, `--max-workers=2 --no-parallel --no-daemon`, BelowNormal priority, fresh JUnit XML):
  - `ProgressTrackingTest` 17/17 (incl. backdated continuation)
  - `DirectionalBackupTest` 7/7 (incl. LOGGED-with-baseline v5 gate)
  - `DatabaseMigrationTest` 1/1 (v1→5 chain incl. MIGRATION_4_5 DEFAULT verification)
  - `HabitRepositoryTest` directional + decreasing-zero methods 2/2
- All runs verified live: 2–3 JVMs, BelowNormal, heap flags checked on process command lines, free RAM 2.0–4.8 GB monitored throughout.

## Review loop (subagent workflow, both read-only)

- `product-requirements-analyst`: 20 edge cases → incorporated (DECREASING zero, future-date guard, per-mode stats, prefill cascade).
- `architecture-specialist`: 5 hazards → H2 (encode-side backup break, `@EncodeDefault(NEVER)`), H1 (schema defaults), H3 (editor rebuild) fixed before commit.
- `frontend-engineer`: implemented `ui/components/ActivityTracking.kt` against the lead's data contract (separate file ownership).
- `independent-reviewer`: C1 **ExerciseTrace NaN crash** (all-zero DECREASING entries → NaN dp → measure crash) — fixed with a scale guard; M1/M2 editor baseline retention + malformed-number gating — fixed; informational items verified (schema/migration alignment, backup compatibility, security clean).
- `ui-ux-specialist`: H1 ill-defined-progress copy → actionable wording; H2 selector TalkBack rework (visible label, `selectable` + `Role.RadioButton`, 48dp rows, display-only radio); H3 percent clamp + PB ÷0 guard; M3–M6 copy/labels — fixed; device-QA assumptions listed below.

## Blockers

### B1 — GitHub push requires interactive credentials (OPEN)
- `git push origin develop/v1.8.0` fails: `could not read Username for 'https://github.com': terminal prompts disabled` (three attempts).
- Cause: Git Credential Manager has no cached GitHub entry; no `GITHUB_TOKEN`/`GH_TOKEN`; `gh` CLI not installed. Reads work anonymously (public repo); writes need auth.
- All commits are safe locally on `develop/v1.8.0`. **No force-push, no main touch.**

### B2 — Robolectric standalone v4→v5 migration test quirk (MITIGATED)
- A standalone test building a version-4 DB from exported `4.json` failed at `SQLiteDatabase.openOrCreateDatabase` with `SQLITE_CANTOPEN` at the WAL pragma — even in isolation — while the structurally identical v1 test passes. Windows/Robolectric file-open quirk, not a migration bug.
- Mitigation: consolidated into the v1→5 test, which verifies MIGRATION_4_5's DEFAULT clauses by inserting an activity row without the new columns on the migrated DB and reading them back, plus Room's schema-5 acceptance on open.
- Residual: "v4 rows preserved through 4→5" relies on SQLite's ALTER TABLE guarantee + schema validation, not a direct row-level test. Worth one dedicated migration test on Linux CI.

## Pending / deferred

- Deferred reviewer Lows: analytics all-time current value under a 28-day heading (documented in footer), trend "best" suffix vs 90-point window, input-cap boundary friction (prefill last+1 at 1,000,000), one-decimal-point validation could be friendlier.
- Device QA needed: Vico chart in scrollable dialog, TalkBack merge order, pinch-vs-scroll, fontScale ≥1.3 targets.

## Needs my action (when you return)

1. **GitHub auth**: run `git push origin develop/v1.8.0` in any terminal and complete the browser flow once (or provide a PAT). Four commits are waiting.
2. Full Gradle suite at pinned heap when ≥4 GB RAM is free: `gradlew.bat --max-workers=2 --no-parallel --no-daemon assembleDebug testDebugUnitTest lintDebug` (focused suites green; full suite not run this session).
3. Physical-device pass for the new Activities UI (TalkBack, large text, charts).

## Recommended next steps

1. Push, then continue v1.8.0: app lock (additive settings + biometric gate) → daily reflection (mood + journal, schema 5→6 additive) → milestones. Flexible scheduling stays last (deepest analytics surface).
2. When releasing: bump versionCode/versionName, refresh README numbers, run the full suite, then `gold-final-reviewer` as the release gate.

