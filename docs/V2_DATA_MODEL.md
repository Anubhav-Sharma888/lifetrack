# LifeTrack Version 2 — Data Model

**Database:** `lifetrack.db` · **Room schema version 11** (additive from 10) · **Backup formats v1–v10**
Source of truth: `data/GoalModels.kt` (entities + validator), `data/GoalDao.kt` (queries), `data/DatabaseMigrations.kt` (DDL), `app/schemas/com.lifetrack.app.data.AppDatabase/11.json` (exported schema).

---

## 1. Entity overview

```
goals (root)
 └── goal_phases            goalId → goals (CASCADE)
      ├── goal_milestones   goalId → goals (CASCADE) · phaseId → goal_phases (SET NULL, optional)
      └── goal_tasks        goalId → goals (CASCADE) · phaseId → goal_phases (SET NULL, optional)
                            · milestoneId → goal_milestones (SET NULL, optional)
goal_task_dependencies      dependentTaskId → goal_tasks (CASCADE)
                            prerequisiteTaskId → goal_tasks (CASCADE)
                            PK(dependentTaskId, prerequisiteTaskId)
```

Existing v1 tables (`habits`, `habit_logs`, `habit_pauses`, `reminder_deliveries`, `freeze_usage`, `streak_protections`, `daily_reflections`, `achievement_acknowledgments`, `activities`, `activity_entries`, `app_settings`) are **untouched** by schema 11; no goal table references them and vice versa.

Room **does** enforce these foreign keys at runtime (the declarations are validated at open time and cascades execute on delete), and **`GoalRepository` still performs every child delete explicitly** in the order the table shows — deterministic ordering, observable semantics. The DDL declarations are pinned by `DatabaseMigrationTest`.

## 2. Tables

*(Schema 12 addition, Prompt 2):* `goal_schedule_revisions` — one audit row per rescheduled calendar date: `id` PK, `entityType` (TASK/MILESTONE/PHASE/GOAL), `entityUuid` (no foreign key by design — the entity row may be deleted independently and the repository removes its history explicitly), `field`, `formerDate`/`newDate` (nullable LocalDates), `scope` (SINGLE/SHIFT_DEPENDENTS), `changedAt`. Indexes on `entityUuid` and `(entityUuid, field, changedAt)`. Backup format v11 carries it (`goalScheduleRevisions`, `(NEVER)`); restore inserts it last; the original plan stays recoverable via the earliest revision per (entity, field). Written only by the explicit reschedule operations and the editors- saves when dates change — never by plain metadata saves.


### 2.1 `goals`

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK AUTOINCREMENT | Local row identity; not exported as identity |
| `uuid` | TEXT NOT NULL | **Stable external identity**; canonical 8-4-4-4-12 hex; unique index |
| `title` | TEXT NOT NULL | 1–120 chars after trim |
| `description` | TEXT | nullable, ≤ 2,000 chars, blank→null |
| `category` | TEXT NOT NULL | 1–40 chars, default "Personal" |
| `priority` | TEXT NOT NULL | `LOW` / `MEDIUM` / `HIGH` (default MEDIUM) |
| `plannedStartDate` | INTEGER | nullable LocalDate (epoch day) |
| `targetDate` | INTEGER | nullable LocalDate; ≥ plannedStartDate when both present |
| `status` | TEXT NOT NULL | `PLANNED` / `ACTIVE` / `COMPLETED` / `PAUSED` / `CANCELLED`; default PLANNED |
| `isArchived` | INTEGER NOT NULL | visibility toggle, separate operation from status |
| `archivedOn` | INTEGER | nullable LocalDate; present ⇔ isArchived |
| `completedAt` | INTEGER | epoch millis; present ⇔ status == COMPLETED |
| `sortOrder` | INTEGER NOT NULL | ≥ 0, assigned at creation, explicit reorders |
| `createdAt` / `updatedAt` | INTEGER NOT NULL | monotonic via `nextTimestamp` |

### 2.2 `goal_phases`

`id`, `uuid` (unique), `goalId` (NOT NULL), `name` (1–120), `description` (nullable ≤ 2,000), `sortOrder` (≥ 0, per goal), `plannedStartDate`, `plannedEndDate` (nullable LocalDates, start ≤ end), `status` (`PLANNED`/`ACTIVE`/`COMPLETED`/`CANCELLED`), `completedAt` (⇔ COMPLETED), `createdAt`, `updatedAt`.
Indexes: unique `uuid`, `goalId`.

### 2.3 `goal_milestones`

`id`, `uuid` (unique), `goalId` (NOT NULL), `phaseId` (nullable — phase-less milestones anchor to the goal), `title` (1–120), `description` (nullable ≤ 2,000), `targetDate` (nullable LocalDate deadline), `status` (`PENDING`/`ACHIEVED`), `achievedAt` (epoch millis, ⇔ ACHIEVED), `createdAt`, `updatedAt`.
Ordering: by creation (`createdAt, id`) — milestones are checkpoints; no sort column (additive if Prompt 2 needs explicit ordering).
Indexes: unique `uuid`, `goalId`, `phaseId`.

### 2.4 `goal_tasks`

`id`, `uuid` (unique), `goalId` (NOT NULL), `phaseId` (nullable), `milestoneId` (nullable), `title` (1–120), `description` (nullable ≤ 2,000), `sortOrder` (≥ 0, goal-global), `plannedDate` (nullable LocalDate), `deadline` (nullable LocalDate, ≥ plannedDate when both present), `estimatedMinutes` (nullable, 1–10,080), `status` (`NOT_STARTED`/`IN_PROGRESS`/`COMPLETED`/`SKIPPED`), `completedAt` (⇔ COMPLETED), `createdAt`, `updatedAt`.
Indexes: unique `uuid`, `goalId`, `phaseId`, `milestoneId`.

### 2.5 `goal_task_dependencies`

Composite PK (`dependentTaskId`, `prerequisiteTaskId`); edge = "dependent needs prerequisite first". Index: `prerequisiteTaskId` (reverse lookups). Constraints enforced by `GoalRepository.addDependency` and `GoalValidator.validateDependencyGraph`: endpoints exist, same goal, no self-edge, no duplicate edge, no cycle anywhere (linear iterative DFS). "Blocked" is **derived** at read time — no stored blocked state.

## 3. Identity model

| Identity | Scope | Lifetime | Used by |
|---|---|---|---|
| Row `id` | Local database | Stable under normal operation; reproduced verbatim by full-backup replace-all | FKs, repository APIs |
| `uuid` | Global, cross-file | Permanent record identity, minted once by the repository | Backup validation (global uniqueness), future Goal Plan import matching (Prompt 4) |

Rules: UUIDs are canonical 8-4-4-4-12 hex, globally unique across **all** goal records in a backup (goals+phases+milestones+tasks), minted on first save when blank, never regenerated, never derived from titles.

## 4. State machines

Persisted as strings; transitions enforced in `GoalRepository` via `GoalTransitions` (see PRD §7). Invariants:

- `status == COMPLETED ⇔ completedAt != null` (goals, phases, tasks); `status == ACHIEVED ⇔ achievedAt != null` (milestones).
- New records start at their initial state (PLANNED / PENDING / NOT_STARTED).
- Reopening COMPLETED/ACHIEVED/SKIPPED clears the completion instant.
- `isArchived ⇔ archivedOn != null` (goals only).

## 5. Deletion behavior

| Action | Effect |
|---|---|
| `deleteGoal` | one transaction: dependencies → tasks → milestones → phases → goal row |
| `deletePhase` | detach milestones (`phaseId=NULL`) + tasks (`phaseId=NULL`), then delete phase row |
| `deleteMilestone` | detach tasks (`milestoneId=NULL`), then delete milestone row |
| `deleteTask` | remove dependency rows (both directions), then delete task row |
| `removeDependency` | delete the edge only |

No goal operation ever touches habits, activities, reflections or acknowledgments.

## 6. Migration path

Schema 10 → 11: `MIGRATION_10_11` creates the five tables + 11 indexes, purely additive. `app/schemas/…/11.json` exported. `DatabaseMigrationTest` walks every historical era (1→11, 2→11, … 10→11) and asserts preservation, emptiness of new tables, FK declarations and index presence. Future: schema 12 (Prompt 3, habit-goal links), 13+ (Prompt 5, sessions) — all additive.

## 7. Backup mapping (format v10)

`LifeTrackBackup` gains five `@EncodeDefault(NEVER)` lists — `goals`, `goalPhases`, `goalMilestones`, `goalTasks`, `goalTaskDependencies` — present only when non-empty, so plain v3–v9 exports remain key-free. Full rows are exported (all columns, row ids included). Restore is replace-all: clear goal tables (edges → tasks → milestones → phases → goals), insert parents before dependents, inside the existing single restore transaction. Import normalizes text (trim, blank→null) exactly like repository saves. `appLockEnabled` never appears in any format.
