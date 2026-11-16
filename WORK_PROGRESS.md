# LifeTrack v1.8.0 — Work Progress (sessions 2026-11-15 → 2026-11-17: CSV export, directional tracking, app lock, Smart Streaks Phases 1–2)

Branch: `develop/v1.8.0` · Backup at `D:\LifeTracker-backup` (untouched)

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

