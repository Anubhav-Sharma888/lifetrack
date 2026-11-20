# LifeTrack Version 2 — Implementation Roadmap

**Branch:** `version-2` (branched from the v1.11.0-preview.1 tip of `main`, starting commit `e97c8b9653303efb5a026f03c482c61e40c1fd33`) · **Target release:** `2.1.0` · **First prerelease:** `v2.1.0-preview.1` (Prompt 8)

Eight consolidated prompts. Each session implements, validates, reviews and commits its own scope; later prompts never silently absorb earlier ones.

| # | Prompt | Delivers | Depends on | Status |
|---|---|---|---|---|
| 1 | Requirements + Database Foundation | Full PRD, architecture, data model; Room schema 11 (goals/phases/milestones/tasks/dependencies + UUID identity); `GoalRepository` with state machines, referential integrity, dependency cycle detection; backup format v10 export/import/reset; migration 10→11 + era tests; docs | — | ✅ **This prompt** |
| 2 | Goal UI + Timeline Engine | Goal hub, goal create/edit, goal detail, phase/milestone/task management, timeline calculations, dependency-aware readiness, rescheduling, navigation integration | 1 | ⬜ Not started |
| 3 | Progress Tracking + AI JSON Specification | Task completion UX, milestone verification, progress calculations, habit↔goal links (additive schema 12), Goal Plan JSON schema + parser + sample exports | 2 | ⬜ Not started |
| 4 | Import Preview + Transactional Partial Import | Import preview UI, duplicate detection by UUID, conflict resolution (keep existing / keep imported / copy / skip), transactional merge with rollback | 3 | ⬜ Not started |
| 5 | Study Sessions + Reminders | Session planning, start reminders, completion prompts, background scheduling, permission handling | 1 (identity), 2 (UI entry) | ⬜ Not started |
| 6 | Session Recovery + Goal Analytics | Confirmation/partial/snooze/missed-session handling, rescheduling, planned-vs-actual analytics, delay calculations | 5 | ⬜ Not started |
| 7 | UX, Accessibility and Security | Visual refinement, accessibility pass, navigation polish, performance, security audit, privacy review, edge cases | 2–6 | ⬜ Not started |
| 8 | Integration, QA and Release | Full regression, migration verification, backup restoration testing, device QA, versioning, CI, APK, GitHub prerelease, final report | 7 | ⬜ Not started |

## Cross-prompt contracts already fixed by Prompt 1

- **Identity**: every goal record carries a permanent UUID; row ids are local. Prompt 4 matches records by UUID only.
- **State machines**: persisted transitions (PRD §7) — later prompts add UI affordances, not new states, without a new additive migration.
- **Backup discipline**: additive versioned lists with `@EncodeDefault(NEVER)`; per-version gates in `validate` + `decode`; replace-all restore with parents-before-children inserts; sessions/links follow the same pattern (schemas 12+).
- **Validation caps** (PRD §10) are shared constants in `GoalValidator`; new features reuse them rather than inventing limits.
- **Data safety**: no destructive migration anywhere in the chain; every multi-table write is one transaction; goal operations never touch habit tables.
- **Deferred deliberately**: habit↔goal link table (Prompt 3, additive schema 12 — invariants in V2_ARCHITECTURE §3); study-session persistence design (V2_ARCHITECTURE §7.3); SKIPPED-satisfies-dependency-gate read-time policy (Prompt 3); weekly-quota-style goal period analytics (Prompt 6, needs product decisions).

## Release gates (Prompt 8)

Full unit suite + lint + debug/release build; every historical schema era migrates; v1–v10 backup round trips; device QA checklist (TalkBack, large font, contrast, pickers, IME); version bump 2.1.0 / versionCode per policy; signed APK; GitHub prerelease with release notes; README/UPGRADE refresh (still describing pre-1.8 in places — recorded in v1.x notes, owned here).
