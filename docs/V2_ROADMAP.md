# LifeTrack Version 2 — Implementation Roadmap

**Branch:** `version-2` (from the v1.11.0-preview.1 tip of `main`, starting commit `e97c8b9653303efb5a026f03c482c61e40c1fd33`) · **Release policy:** each prompt lands a preview published by ChatGPT (v2.2.0-preview.1 after Prompt 2, v2.3.0-preview.1 after Prompt 3, … v2.8.0-preview.1 after Prompt 8). ZCode owns development and validation only.

Eight consolidated prompts. Each session implements, validates, reviews and commits its own scope; later prompts never silently absorb earlier ones.

| # | Prompt | Delivers | Depends on | Status |
|---|---|---|---|---|
| 1 | Requirements + Database Foundation (no APK) | Full PRD, architecture, data model; Room schema 11 (goals/phases/milestones/tasks/dependencies + UUID identity); `GoalRepository` with state machines, referential integrity, dependency cycle detection; backup format v10 export/import/reset; migration 10→11 + era tests; docs | — | ✅ Complete |
| 2 | Goal UI + Timeline Engine (v2.2.0-preview.1 — published by ChatGPT) | Goals hub (5th bottom destination), goal editor, goal detail with phases/milestones/tasks, vertical timeline, dependency management, non-destructive rescheduling with schedule history (schema 12, backup v11), navigation integration | 1 | ✅ Complete (497e705) |
| 3 | Progress Tracking + AI JSON Specification (v2.3.0-preview.1) | Progress engine + honest counts, task completion UX, milestone verification, habit↔goal links (additive schema 13, backup v12), Goal Plan JSON schema + strict parser + fixtures + docs | 2 | ✅ Complete (6c15b6d) |
| 4 | Import Preview + Transactional Partial Import (v2.4.0-preview.1) | Import preview UI, duplicate detection by UUID, conflict resolution (keep existing / keep imported / copy / skip), transactional merge with rollback | 3 | ✅ Complete |
| 5 | Study Sessions + Reminders (v2.5.0-preview.1) | Session planning (timezone-safe wall clock + UTC actuals), start reminders, end check-ins, explicit lifecycle (start/confirm/cancel), background scheduling with stale-job protection, permission-aware notifications, schema 14 + backup v13 | 3 (progress), 4 (import untouched) | ✅ Complete (38cca4e) |
| 6 | Session Recovery + Goal Analytics (v2.6.0-preview.1) | Confirmation/partial/snooze/missed-session handling, rescheduling, planned-vs-actual analytics, delay calculations, habit ringing alarms (schema 15, backup v14) | 5 | ✅ Complete (28efbe3) |
| 7 | UX, Accessibility and Security (v2.7.0-preview.1) | Visual refinement, accessibility pass, navigation polish, performance, security audit, privacy review, edge cases | 2–6 | ✅ Complete |
| 8 | Integration, QA and Release (v2.8.0-preview.1) | Full regression, migration verification, backup restoration testing, device QA, versioning, CI, APK, GitHub prerelease, final report | 7 | ⬜ Not started |

## Cross-prompt contracts already fixed by Prompt 1

- **Identity**: every goal record carries a permanent UUID; row ids are local. Prompt 4 matches records by UUID only.
- **State machines**: persisted transitions (PRD §7) — later prompts add UI affordances, not new states, without a new additive migration.
- **Backup discipline**: additive versioned lists with `@EncodeDefault(NEVER)`; per-version gates in `validate` + `decode`; replace-all restore with parents-before-children inserts; sessions/links follow the same pattern (schemas 12+).
- **Validation caps** (PRD §10) are shared constants in `GoalValidator`; new features reuse them rather than inventing limits.
- **Data safety**: no destructive migration anywhere in the chain; every multi-table write is one transaction; goal operations never touch habit tables.
- **Resolved from the deferred list (Prompt 3)**: habit↔goal link table shipped as schema 13 (association only, invariants in V2_ARCHITECTURE §3); the SKIPPED-satisfies-dependency-gate policy shipped read-time (SATISFIED_BYPASSED — bypass satisfies the gate, never counts as completion). Still deferred: study-session persistence design (V2_ARCHITECTURE §7.3); weekly-quota-style goal period analytics (Prompt 6, needs product decisions); whether a habit completion ever contributes to a goal (open product question, association-only for now).

## Release gates (Prompt 8)

Full unit suite + lint + debug/release build; every historical schema era migrates; v1–v10 backup round trips; device QA checklist (TalkBack, large font, contrast, pickers, IME); version bump 2.1.0 / versionCode per policy; signed APK; GitHub prerelease with release notes; README/UPGRADE refresh (still describing pre-1.8 in places — recorded in v1.x notes, owned here).
