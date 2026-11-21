# LifeTrack Version 2 — Architecture

**Status:** Authoritative for the Version 2 line (branch `version-2`). Written at Prompt 1; Prompts 2–3 extend it (timeline engine, progress engine, habit links, Goal Plan contract); later prompts extend, not contradict, this document.

---

## 1. Current system architecture (v1.x baseline)

Single-module Android application, package `com.lifetrack.app`:

| Layer | Location | Contents |
|---|---|---|
| UI | `ui/` | Compose Material 3 screens (`LifeTrackApp.kt` hosts a simple string-route NavHost: today / activities / analytics / settings / achievements / schedules / habit editors), shared components under `ui/components/` |
| State | `ui/HabitViewModel.kt` | One application-scoped ViewModel exposing a combined UI state and domain events (celebrations) |
| Domain | `domain/` | Pure-Kotlin engines: `Analytics.kt`, `Scheduling.kt`, `StreakPolicy.kt`, `ProgressTracking.kt`, `Achievements.kt` — no Android or Room imports |
| Data | `data/` | Room database (`AppDatabase`), entities (`Models.kt`), DAOs (`HabitDao.kt`), repositories (`HabitRepository.kt`), backup stack (`Backup.kt`, `BackupCrypto.kt`, `BackupInspector.kt`), CSV (`CsvExport.kt`) |
| Background | `reminder/`, `widget/` | WorkManager workers for check-in reminders, backups and widget refresh |
| DI | `di/AppModule.kt` | Hilt singleton module providing the Room database with the full migration chain |

Established invariants that Version 2 preserves:

- **Room is the canonical store**; JSON backups are the only restorable export; CSV is an analysis copy.
- **Backup format versioning**: `LifeTrackBackup(formatVersion = 3…10)` with `@EncodeDefault(NEVER)` additive lists, per-version gates (`require(formatVersion >= N || collection.isEmpty())`) in both `validate` and `decode`, and strict JSON config (`ignoreUnknownKeys = false`, `isLenient = false`, depth ≤ 64 pre-check).
- **All mutations flow through repositories** with `require`-based validation, monotonic `nextTimestamp` writes, and `db.withTransaction` for multi-table work.
- **Room enforces declared foreign keys at runtime** (proven by `deleteHabit`'s orphan-free tests: a bare habit delete cascades its logs). The declarations are validated at open time, and repositories still perform every parent/child delete explicitly — deterministic ordering, observable semantics. *(This corrects a v1.11 session note that claimed enforcement was off; the disassembly conclusion was wrong.)*
- **Migration discipline**: additive `ALTER TABLE`/`CREATE TABLE` only; exported `app/schemas/*.json`; Robolectric migration tests rebuild each historical era from its exported schema and let Room validate the migrated end state.

## 2. Version 2 additions (Goal Roadmaps)

### 2.1 Module and package boundaries

No new Gradle module. The feature is a **bounded new domain inside the existing module**:

```
data/GoalModels.kt        entities (Goal, GoalPhase, GoalMilestone, GoalTask, GoalTaskDependency,
                          GoalScheduleRevision, GoalHabitLink), persisted-status enums +
                          GoalTransitions, GoalValidator (shared by repository and backup
                          validation), import normalizers
data/GoalDao.kt           observation flows (goal-scoped) + snapshot reads + explicit delete orders
data/GoalRepository.kt    transactional CRUD, state machines, dependency graph checks, reordering,
                          reschedule + history writes, habit links + read-only activity signals
data/GoalPlanDocument.kt  Goal Plan JSON contract: document model, bounded strict parser,
                          validator, exporter (Prompt 3)
domain/GoalTimeline.kt    pure snapshot/classification/shift engine (Prompt 2)
domain/GoalProgress.kt    pure progress math: breakdowns, rates, readiness (Prompt 3)
data/AppDatabase.kt       version 13; dao() (habits) + goalDao() (roadmaps)
data/Backup.kt            LifeTrackBackup v10–v12 lists + validateGoalRoadmaps
data/HabitRepository.kt   export snapshot/version cascade, replace-all import, reset (goal leg)
ui/GoalsScreen.kt, ui/GoalDetailScreen.kt, ui/GoalDialogs.kt, ui/GoalViewModel.kt
                          goal hub, detail, editors, verification/link dialogs (Prompt 2–3)
docs/                     this document set + the Goal Plan format/schema/AI-prompt documents
```

Deliberately **not** created: any `ui/` goal-plan import UI (Prompt 4 owns preview/merge — the Prompt 3 parser is UI-free and importable later unchanged), any session/reminder code (Prompt 5). No repository method returns a fake or a `TODO` — Prompt-4+ contracts are documented in §7 and §8 as interface sketches, not stubs in code.

### 2.2 Data ownership

- `GoalRepository` is the **single writer** for goals/phases/milestones/tasks/dependencies/schedule revisions/habit links. Compose UI never touches Room directly (v1 invariant).
- `HabitRepository` remains the single writer for habits and owns the **whole-database lifecycle operations** (export, replace-all import, reset) because they span both domains. It accesses `goalDao()` only inside those three operations; goal-record writes outside them are invalid by convention and by validation.
- **Habit linking is a dedicated association table** (`goal_habit_links`, schema 13) — never habit ids embedded in goal rows. `GoalRepository.linkHabit/unlinkHabit` write only the link rows; every habit-data read for goal features goes through `HabitRepository.habitActivity` and is strictly read-only (FR-15: a linked habit never completes a task or a goal, and habit logs are untouchable by goal code).

### 2.3 Persistence strategy

Seven goal-domain tables across schemas 11–13, all additive:

| Table | Parent FKs | Delete semantics (declared + explicitly executed) |
|---|---|---|
| `goals` | — | root; tree delete is explicit (children first) |
| `goal_phases` | `goals` CASCADE | children detach (milestones/tasks SET NULL) before phase delete |
| `goal_milestones` | `goals` CASCADE; `goal_phases` SET NULL | tasks detach before milestone delete |
| `goal_tasks` | `goals` CASCADE; `goal_phases` SET NULL; `goal_milestones` SET NULL | dependency edges removed both directions before task delete |
| `goal_task_dependencies` | `goal_tasks` CASCADE ×2 | composite PK (dependentTaskId, prerequisiteTaskId), index on prerequisiteTaskId |
| `goal_schedule_revisions` (schema 12) | none by design (uuid-bound audit rows) | repository removes an entity's history explicitly on delete |
| `goal_habit_links` (schema 13) | `goals` CASCADE; `habits` CASCADE | composite PK (goalId, habitId); deleting either side removes its links; index on habitId |

Every row carries `uuid TEXT NOT NULL` with a unique index — the **stable external identity**. Row `id`s are local database identity: full-backup restores reproduce them verbatim (replace-all), while the future partial Goal Plan import (Prompt 4) matches records by UUID and mints fresh row ids. UUIDs are minted by the repository on first save (`UUID.randomUUID()`), validated as canonical 8-4-4-4-12 hex everywhere, and never generated during reads or serialization.

Time representation: calendar planning fields are `LocalDate` (existing `Converters` epoch-day mapping); actual completion instants are epoch-millis `Long`s. No column mixes the two meanings. Session scheduling (Prompt 5) will add its own time columns per §8 — no premature "time" fields exist.

Statuses persist as strings with `fromStored()` fallback + strict validator rejection on import + transition-graph enforcement on write (the established `ScheduleMode`/`StreakPolicy` pattern). Derivable facts (progress %, overdue, blocked) are never stored.

### 2.4 Validation architecture

One source of truth: `GoalValidator` in `data/GoalModels.kt`. The repository calls it after normalizing each save; `BackupValidator.validateGoalRoadmaps` calls the same functions for import, adding the cross-record checks that only a whole graph can answer (row-id uniqueness, global UUID uniqueness, parent resolution maps, duplicate edges, cycle detection over the complete edge list). Count caps live in `GoalValidator` and the **totals** are enforced at both layers — repository creates check all-goals counts (a database can never outgrow what an export would accept, so the backup safety net cannot be outgrown), and import checks the same totals on the incoming document. Cycle detection is a linear iterative DFS over "depends on" edges — no recursion, bounded by `MAX_DEPENDENCIES = 10_000`.

One documented asymmetry: `decode` validates the raw document (mirroring the journal behavior since v7), then `importFrom` normalizes goal text (trim, blank→null) and revalidates — so a file whose title is only valid after trimming is rejected at decode-time checks but accepted by the import path it actually flows through. Fails closed either way.

### 2.5 Backup integration

- **Export** (`HabitRepository.exportTo`): one transaction snapshot reads all goal tables; `formatVersion` is picked by cascade — any habit-goal link → 12, else any schedule revision → 11, else any goal record → 10 (each cascade branch checked first, before the flexible-scheduling branch). Without goal-domain data the export stays v3–v9 and the `@EncodeDefault(NEVER)` goal lists remain absent from the JSON, so older app versions decode plain exports unchanged.
- **Import** (`importFrom`): decode gate widens to `1..12`; goal-domain text normalizes like journals (trim, blank→null) before validation; the replace-all transaction clears links + revisions first, then goal tables edges→tasks→milestones→phases→goals, and reinserts goals→phases→milestones→tasks→dependencies→revisions→links inside the existing single transaction. Older formats (v1–v9) restore exactly as before, with goal-domain tables cleared to defaults. Link validation requires both sides to resolve and enforces the caps and duplicate-pair rejection.
- **Reset** (`resetAllData`): clears the goal tables with the same ordering, preserving the app-lock flag.
- **Encrypted backups**: the v10 payload rides the existing AES-256-GCM envelope untouched; no crypto change accompanies a feature addition.
- **Known limitation (documented, accepted)**: a v1.11 (or older) install cannot restore a v10 backup — the version gate rejects it with "This backup version is not supported by this app." This is the established behavior of every previous format bump.

### 2.6 Migration strategy

`MIGRATION_10_11`, `MIGRATION_11_12` (Prompt 2, schedule-history audit table) and `MIGRATION_12_13` (Prompt 3, `goal_habit_links`) are pure `CREATE TABLE`/`CREATE INDEX` — no existing table is touched. The exported `13.json` is generated by KSP; `DatabaseMigrationTest` builds every historical era (2–12 → 13, plus the v1 full chain) from its exported schema and lets Room validate the migrated end state, and asserts the goal-table FK declarations and indexes against the migrated DDL. No destructive fallback exists anywhere in the chain.

### 2.7 Transaction boundaries

| Operation | Transaction |
|---|---|
| Any single save (goal/phase/milestone/task) | one `withTransaction` (validation reads + write) |
| `deleteGoal` | one transaction: dependencies → tasks → milestones → phases → goal |
| `deletePhase` / `deleteMilestone` | one transaction: detach children (SET NULL updates) → delete |
| `deleteTask` | one transaction: remove edges → delete |
| `addDependency` | one transaction: graph read + cycle proof + insert |
| Reorders | one transaction: full reindex |
| `exportTo` | snapshot reads in one transaction; file write outside; `lastExportAt` update in its own transaction (v1 pattern) |
| `importFrom` / `resetAllData` | one transaction spanning both domains; held under `backupMutex` |

## 3. Relationship with existing habits (Prompt 1 decision, Prompt 3 implementation)

**Prompt 1 decision — no link table in schema 11.** Rationale: no consumer existed yet, and the link's semantics were undecided product questions. The design reserved what Prompt 3 needed:

1. Stable UUIDs already exist on all goal records (import matching).
2. Full backups preserve row ids (link rows restore identically under replace-all).
3. Adding the link table later is one more additive migration with the same test discipline.
4. Binding invariant recorded then and **held by the Prompt 3 implementation**: goal features read habit progress **read-only** through links and never write habit logs or double-count progress (PRD §6.F, FR-15).

**Prompt 3 update:** the dedicated link table shipped as `goal_habit_links` (schema 13). Its shape is deliberately minimal — an association plus `createdAt` — because the product semantics stayed "association only": the detail screen shows each linked habit's honest completion signals (window counts, last completion) computed fresh by `HabitRepository.habitActivity` at read time. No contribution rule, weight or automation was introduced; whether a habit completion ever advances anything toward a goal remains an open product question for a later prompt, and until then nothing derives status from a link.

## 4. Security concerns

- No new permission, worker, receiver, or connectivity primitive. The attack surface added in Prompt 1 is **import validation**, covered by: strict UTF-8 decode, depth ≤ 64 linear pre-check, strict key parsing (`ignoreUnknownKeys = false`), count caps, canonical-UUID checks, referential resolution before any write, single-transaction restore, and the same 10 MB plain / 15 MB envelope size budget.
- Imported status strings are validated against enum names (no unknown value ever reaches a column). Crafted files can never write a row that violates the invariants the live repository enforces.
- App lock remains local-only and is stripped on export and rejected-with-preservation on import (unchanged).

## 5. Testing strategy

- **Migration**: extend `DatabaseMigrationTest` — every era 2..10 → 11 with era-faithful seeds; v1 full chain; FK DDL + index assertions; new-table emptiness + insert/read-back.
- **Repository**: Robolectric `GoalRepositoryTest` against the generated Room implementation (no fakes) — lifecycle, state machines, referential integrity, dependency constraints, explicit cascade behavior, caps, reorder.
- **Backup**: pure-JVM `GoalBackupTest` — v10 round trip, key-free plain exports, version gates, referential/dependency rejections, caps, normalization, app-lock absence.
- **Integration**: export/import tests through the real `HabitRepository` path with file URIs (format-version computation, transactional failure isolation).
- Robolectric suites run at the full pinned heap (`-Xmx3g`) when ≥4 GB is free; resource policy per `AGENTS.md` governs every build.

## 6. Architectural trade-offs

- **Single module over multi-module**: the codebase is a cohesive offline app; a new module would add build time (a hard constraint on this laptop) without an isolation win. Revisit only if build times or team structure demand it.
- **String statuses over Int enums**: matches the established persisted-enum pattern, keeps diffs readable, tolerates unknown stored values at read time while rejecting them at write/import time.
- **Explicit repository cascades alongside FK enforcement**: Room does enforce declared foreign keys (see §1 invariant), and the repositories still execute every delete explicitly in documented order — deterministic, observable, testable, and identical regardless of connection settings.
- **SET NULL over CASCADE for phase/milestone deletion**: preserves actual work (planning principle 1); containers are disposable, records are not.
- **UUIDs as TEXT**: 36 bytes per row is negligible at roadmap scale and buys stable cross-file identity.

## 7. Contracts (shipped as designed unless noted)

### 7.1 Timeline engine (Prompt 2, shipped)
Pure `domain/GoalTimeline.kt` over persisted records: ordered phase/task views, planned vs. today classification, dependency-aware prerequisite gates, timing-conflict display, deterministic Kahn order, batch shift previews, originalPlannedDates recovery. The engine never writes.

### 7.1a Progress engine (Prompt 3, shipped)
Pure `domain/GoalProgress.kt`: task/milestone breakdowns, percentage rates that are **null when not measurable** (no tasks ⇒ no fabricated 100%), `readyForReview` as a derived view (requires every task COMPLETED — SKIPPED is bypass, not completion — and every milestone ACHIEVED; null with no tasks and no milestones). Read-time only; nothing stores or auto-promotes a "ready" state. The dependency-gate policy lives beside it in `GoalTimeline.PrerequisiteGate`: a COMPLETED prerequisite satisfies (`SATISFIED_COMPLETED`), a SKIPPED one bypasses (`SATISFIED_BYPASSED`), anything else leaves the gate `PENDING`.

### 7.2 Import preview (Prompt 4)
`GoalPlanDocument` (shipped in Prompt 3) → validated candidate list → conflict classification by UUID → user decisions → one transaction applying decisions via repository writes. Preview reads only; the merge re-validates every record before its write. **Recorded obligation:** the Goal Plan parser validates document-level structure and ordering (planned ≤ deadline) but deliberately does not apply the full-backup calendar-date ranges — plan dates may legitimately describe the past or future. The Prompt 4 importer MUST run every converted record through `GoalValidator`'s entity-level checks (including the `earliestDate`-anchored ranges) before any write, so out-of-range dates cannot reach Room.

### 7.3 Study sessions (Prompt 5)
New tables (additive, schema 14+): `goal_sessions` with **planned** start/end as local date-time + the user-selected timezone id captured at creation (calendar semantics survive DST), and **actual** start/end as UTC epoch millis recorded at the moment they happen; confirmation state, partial minutes, skip reasons, and the linked goal/task identity. Sessions reference goals/tasks by row id (stable under full-backup replace-all); the partial import path remaps by UUID before writing link-bearing rows. Sessions join the full-backup envelope as a versioned additive list with their own gates; missed-session recovery is read-time, never a silent completion write.

### 7.4 Goal Plan JSON (Prompt 3, shipped as a contract)
A standalone document type, deliberately separate from backups: envelope `kind: "lifetrack.goal-plan"` + `schemaVersion: 1` + caller-minted canonical UUIDs; one goal, up to 500 phases / 1,000 milestones / 5,000 tasks / 10,000 dependencies; no status, completion or history fields (fabricated progress is *unrepresentable*, not filtered); strict parsing (`ignoreUnknownKeys = false`) so schema drift fails loudly. `data/GoalPlanDocument.kt` holds the document model, the bounded parser (2 MB cap, depth ≤ 64 via the shared pre-check, kind/version checks before decode), `GoalPlanValidator` (UUIDs, lengths, ranges, reference resolution, acyclicity with proven DFS), the exporter (a goal exported as a re-importable plan) and the round-trip writer. The format, JSON Schema and the AI prompt template live in [`docs/GOAL_PLAN_FORMAT.md`](GOAL_PLAN_FORMAT.md), [`docs/goal-plan.schema.json`](goal-plan.schema.json) and [`docs/GOAL_PLAN_AI_PROMPT.md`](GOAL_PLAN_AI_PROMPT.md); valid/invalid fixtures under `docs/examples/goal-plans/` are executed by the parser tests. Import UI is Prompt 4.

## 8. Prompt dependencies

Prompt 1 (this foundation) blocks Prompt 2 (needs entities/DAO/repo), which blocks Prompt 3 (progress engines + plan schema), which blocks Prompt 4 (import preview/merge), while Prompt 5 (sessions) needs only Prompt 1 identity + backup patterns and can proceed after Prompt 2. Prompts 6–8 depend on all predecessors.
