# LifeTrack Version 2 — Architecture

**Status:** Authoritative for the Version 2 line (branch `version-2`). Written at Prompt 1; later prompts extend, not contradict, this document.

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
data/GoalModels.kt        entities (Goal, GoalPhase, GoalMilestone, GoalTask, GoalTaskDependency),
                          persisted-status enums + GoalTransitions, GoalValidator (shared by
                          repository and backup validation), import normalizers
data/GoalDao.kt           observation flows (goal-scoped) + snapshot reads + explicit delete orders
data/GoalRepository.kt    transactional CRUD, state machines, dependency graph checks, reordering
data/AppDatabase.kt       version 11; dao() (habits) + goalDao() (roadmaps)
data/Backup.kt            LifeTrackBackup v10 lists + validateGoalRoadmaps
data/HabitRepository.kt   export snapshot/version cascade, replace-all import, reset (goal leg)
docs/                     this document set
```

Deliberately **not** created in Prompt 1: `domain/Goal*.kt` engines (timeline/progress engines arrive with Prompt 3 when semantics are defined), any `ui/` goal code (Prompt 2), any session/reminder code (Prompt 5). No repository method returns a fake or a `TODO` — Prompt-2+ contracts are documented in §7 and §8 as interface sketches in this document, not stubs in code.

### 2.2 Data ownership

- `GoalRepository` is the **single writer** for goals/phases/milestones/tasks/dependencies. Compose UI never touches Room directly (v1 invariant).
- `HabitRepository` remains the single writer for habits and owns the **whole-database lifecycle operations** (export, replace-all import, reset) because they span both domains. It accesses `goalDao()` only inside those three operations; goal-record writes outside them are invalid by convention and by validation.
- Goal records never reference habit tables. Habit linking (Prompt 3) will add a dedicated link table rather than embedding habit ids in goal rows.

### 2.3 Persistence strategy

Five new tables (schema 11), all additive from schema 10:

| Table | Parent FKs | Delete semantics (declared + explicitly executed) |
|---|---|---|
| `goals` | — | root; tree delete is explicit (children first) |
| `goal_phases` | `goals` CASCADE | children detach (milestones/tasks SET NULL) before phase delete |
| `goal_milestones` | `goals` CASCADE; `goal_phases` SET NULL | tasks detach before milestone delete |
| `goal_tasks` | `goals` CASCADE; `goal_phases` SET NULL; `goal_milestones` SET NULL | dependency edges removed both directions before task delete |
| `goal_task_dependencies` | `goal_tasks` CASCADE ×2 | composite PK (dependentTaskId, prerequisiteTaskId), index on prerequisiteTaskId |

Every row carries `uuid TEXT NOT NULL` with a unique index — the **stable external identity**. Row `id`s are local database identity: full-backup restores reproduce them verbatim (replace-all), while the future partial Goal Plan import (Prompt 4) matches records by UUID and mints fresh row ids. UUIDs are minted by the repository on first save (`UUID.randomUUID()`), validated as canonical 8-4-4-4-12 hex everywhere, and never generated during reads or serialization.

Time representation: calendar planning fields are `LocalDate` (existing `Converters` epoch-day mapping); actual completion instants are epoch-millis `Long`s. No column mixes the two meanings. Session scheduling (Prompt 5) will add its own time columns per §8 — no premature "time" fields exist.

Statuses persist as strings with `fromStored()` fallback + strict validator rejection on import + transition-graph enforcement on write (the established `ScheduleMode`/`StreakPolicy` pattern). Derivable facts (progress %, overdue, blocked) are never stored.

### 2.4 Validation architecture

One source of truth: `GoalValidator` in `data/GoalModels.kt`. The repository calls it after normalizing each save; `BackupValidator.validateGoalRoadmaps` calls the same functions for import, adding the cross-record checks that only a whole graph can answer (row-id uniqueness, global UUID uniqueness, parent resolution maps, duplicate edges, cycle detection over the complete edge list). Count caps live in `GoalValidator` and the **totals** are enforced at both layers — repository creates check all-goals counts (a database can never outgrow what an export would accept, so the backup safety net cannot be outgrown), and import checks the same totals on the incoming document. Cycle detection is a linear iterative DFS over "depends on" edges — no recursion, bounded by `MAX_DEPENDENCIES = 10_000`.

One documented asymmetry: `decode` validates the raw document (mirroring the journal behavior since v7), then `importFrom` normalizes goal text (trim, blank→null) and revalidates — so a file whose title is only valid after trimming is rejected at decode-time checks but accepted by the import path it actually flows through. Fails closed either way.

### 2.5 Backup integration

- **Export** (`HabitRepository.exportTo`): one transaction snapshot reads all five goal tables; `formatVersion` becomes 10 when any goal record exists (cascade checked first, before the v9 flexible-scheduling branch). Without goal data the export stays v3–v9 and the `@EncodeDefault(NEVER)` goal lists remain absent from the JSON, so older app versions decode plain exports unchanged.
- **Import** (`importFrom`): decode gate widens to `1..10`; a v10 file decodes with strict keys; goal text normalizes like journals (trim, blank→null) before validation; the replace-all transaction clears goal tables edges→tasks→milestones→phases→goals and reinserts goals→phases→milestones→tasks→dependencies inside the existing single transaction. Older formats (v1–v9) restore exactly as before, with goal tables cleared to defaults.
- **Reset** (`resetAllData`): clears the goal tables with the same ordering, preserving the app-lock flag.
- **Encrypted backups**: the v10 payload rides the existing AES-256-GCM envelope untouched; no crypto change accompanies a feature addition.
- **Known limitation (documented, accepted)**: a v1.11 (or older) install cannot restore a v10 backup — the version gate rejects it with "This backup version is not supported by this app." This is the established behavior of every previous format bump.

### 2.6 Migration strategy

`MIGRATION_10_11` (in `DatabaseMigrations.kt`, registered in `AppModule`) is pure `CREATE TABLE`/`CREATE INDEX` — no existing table is touched. The exported `11.json` is generated by KSP; `DatabaseMigrationTest` builds every historical era (1–11) from its exported schema and lets Room validate the migrated end state, and asserts the goal-table FK declarations and indexes against the migrated DDL. No destructive fallback exists anywhere in the chain.

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

## 3. Relationship with existing habits (Prompt 1 decision)

**No link table in schema 11.** Rationale: no consumer exists until Prompt 3 (progress + analytics), and the link's semantics (what a habit completion contributes to which milestone) are undecided product questions — shipping the table now would bake in an unvalidated shape. The design reserves what Prompt 3 needs:

1. Stable UUIDs already exist on all goal records (import matching).
2. Full backups preserve row ids (future link rows restore identically under replace-all).
3. Adding the link table in Prompt 3 is one more additive migration (11→12) with the same test discipline — the chain supports it by construction.
4. Binding invariant recorded now: goal analytics read habit progress **read-only** through links and must never write habit logs or double-count progress (PRD §6.F, FR-15).

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
- **Explicit repository cascades over FK enforcement**: required anyway (Room does not enable FK enforcement), and it keeps delete semantics observable and testable regardless of connection settings.
- **SET NULL over CASCADE for phase/milestone deletion**: preserves actual work (planning principle 1); containers are disposable, records are not.
- **UUIDs as TEXT**: 36 bytes per row is negligible at roadmap scale and buys stable cross-file identity.

## 7. Future contracts (documented, not implemented)

### 7.1 Timeline engine (Prompt 2)
Pure `domain/` function set over persisted records: ordered phase/task views, planned vs. today position, dependency-aware "ready to work" filtering, reschedule helpers. Inputs are records read via `GoalRepository` flows; the engine never writes.

### 7.2 Import preview (Prompt 4)
`GoalPlanDocument` (Prompt 3's schema) → validated candidate list → conflict classification by UUID → user decisions → one transaction applying decisions via repository writes. Preview reads only; the merge re-validates every record before its write.

### 7.3 Study sessions (Prompt 5)
New tables (additive, schema 12+): `goal_sessions` with **planned** start/end as local date-time + the user-selected timezone id captured at creation (calendar semantics survive DST), and **actual** start/end as UTC epoch millis recorded at the moment they happen; confirmation state, partial minutes, skip reasons, and the linked goal/task identity. Sessions reference goals/tasks by row id (stable under full-backup replace-all); the future partial import path remaps by UUID before writing link-bearing rows. Sessions join the full-backup envelope as a versioned additive list with their own gates; missed-session recovery is read-time, never a silent completion write.

## 8. Prompt dependencies

Prompt 1 (this foundation) blocks Prompt 2 (needs entities/DAO/repo), which blocks Prompt 3 (progress engines + plan schema), which blocks Prompt 4 (import preview/merge), while Prompt 5 (sessions) needs only Prompt 1 identity + backup patterns and can proceed after Prompt 2. Prompts 6–8 depend on all predecessors.
