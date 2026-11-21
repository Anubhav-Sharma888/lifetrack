# LifeTrack Version 2 — Data Model

**Database:** `lifetrack.db` · **Room schema version 14** (additive from 10) · **Backup formats v1–v13**
Source of truth: `data/GoalModels.kt` (entities + validator), `data/GoalDao.kt` (queries), `data/DatabaseMigrations.kt` (DDL), `app/schemas/com.lifetrack.app.data.AppDatabase/14.json` (exported schema).

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
goal_schedule_revisions     entityType/entityUuid audit rows; uuid-bound, NO foreign key by design
goal_habit_links            goalId → goals (CASCADE) · habitId → habits (CASCADE)
                            PK(goalId, habitId) — the only goal-domain FK into a habit table
goal_sessions               goalId/phaseId/taskId → goals/phases/tasks (SET NULL, optional)
                            planned wall-clock start/end in the recorded IANA zone;
                            actual UTC epoch-millis start/end; unique uuid
```

Existing v1 tables (`habits`, `habit_logs`, `habit_pauses`, `reminder_deliveries`, `freeze_usage`, `streak_protections`, `daily_reflections`, `achievement_acknowledgments`, `activities`, `activity_entries`, `app_settings`) are **untouched** by schemas 11–14 except one read-only edge: `goal_habit_links.habitId` references `habits(id)`. The link table stores only the association — goal features read habit data through `HabitRepository` and never write habit logs, reminders or settings.

Room **does** enforce these foreign keys at runtime (the declarations are validated at open time and cascades execute on delete), and **`GoalRepository` still performs every child delete explicitly** in the order the table shows — deterministic ordering, observable semantics. The DDL declarations are pinned by `DatabaseMigrationTest`.

## 2. Tables

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

### 2.6 `goal_schedule_revisions` (schema 12, Prompt 2)

One audit row per rescheduled calendar date: `id` PK, `entityType` (TASK/MILESTONE/PHASE/GOAL), `entityUuid` (no foreign key by design — the entity row may be deleted independently and the repository removes its history explicitly), `field`, `formerDate`/`newDate` (nullable LocalDates), `scope` (SINGLE/SHIFT_DEPENDENTS), `changedAt`. Indexes on `entityUuid` and `(entityUuid, field, changedAt)`. Backup format v11 carries it (`goalScheduleRevisions`, `(NEVER)`); restore inserts it last; the original plan stays recoverable via the earliest revision per (entity, field). Written only by the explicit reschedule operations and the editor saves when dates change — never by plain metadata saves.

### 2.7 `goal_habit_links` (schema 13, Prompt 3)

Association table: `goalId` + `habitId` composite PK, `createdAt` epoch millis. Both FKs CASCADE (deleting either side removes its links inside the same transaction), plus an index on `habitId` for reverse lookups. Caps: 50 links per goal, 10,000 total (repository and backup validation share the constants). Duplicate pair is rejected ("already supports"); `unlinkHabit` is idempotent. The table carries no semantics — what a habit contributes to a goal is computed read-only at display time (`HabitRepository.habitActivity`: completion counts, window counts, last completion date). Habit logs are never written by goal code, and habit deletion cascades the link rows while leaving the goal intact (and vice versa). Backup format v12 carries the list (`goalHabitLinks`, `(NEVER)`); restore clears it first and inserts after goals+habits.

### 2.8 `goal_sessions` (schema 14, Prompt 5)

One planned block of focused work: `id` PK, `uuid` (unique index — stable external identity), `title` (1–120), `description` (≤ 2,000), `plannedStartDate`/`plannedStartTime`/`plannedEndDate`/`plannedEndTime` (wall-clock in the session's own `timezoneId` — never reinterpreted), `actualStartAt`/`actualEndAt` (UTC epoch millis, present only after user-confirmed actions), `status` (`PLANNED`/`IN_PROGRESS`/`COMPLETED`/`CANCELED`; IN_PROGRESS ⇔ start recorded; COMPLETED ⇔ end recorded; start ≤ end), optional `goalId`/`phaseId`/`taskId` (SET NULL — parent deletion detaches while the row survives), `startReminder`/`endCheckIn` toggles, monotonic `createdAt`/`updatedAt`. Windows are 1–1,440 minutes (cross-midnight via explicit end date) within 1900..today+100. Caps: 5,000 sessions total, 500 upcoming scheduled. Indices: unique `uuid`, `plannedStartDate`, `status`, `goalId`, `taskId`. Nothing ever auto-completes a session, and completing one never completes a task.

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
- **Nothing auto-completes** (V2 data-safety constraint): statuses move only through explicit user actions — the detail screen's task quick actions (Start/Done/Reopen/Restore) and milestone verification dialog drive the same repository transitions; no deadline, notification, reminder or parent status ever writes a status. Milestone verification is a two-step explicit confirmation; "ready for review" is a *derived* view (`GoalProgress.readyForReview`), never a stored or auto-promoted state.
- SKIPPED is a user decision, never dependency-derived; at read time a skipped prerequisite **satisfies** its dependents' gates (`SATISFIED_BYPASSED`) but is never counted as completion (`GoalProgress` counts it separately; `readyForReview` requires every task COMPLETED).

## 5. Deletion behavior

| Action | Effect |
|---|---|
| `deleteGoal` | one transaction: dependencies → tasks → milestones → phases → goal row; schedule revisions and habit links for the goal are removed |
| `deletePhase` | detach milestones (`phaseId=NULL`) + tasks (`phaseId=NULL`), then delete phase row |
| `deleteMilestone` | detach tasks (`milestoneId=NULL`), then delete milestone row |
| `deleteTask` | remove dependency rows (both directions), then delete task row |
| `removeDependency` | delete the edge only |
| habit deletion (habit side) | `goal_habit_links` rows for the habit cascade; the goal itself is untouched |
| goal deletion (link side) | `goal_habit_links` rows for the goal cascade; the habit itself is untouched |

No goal operation ever writes habits, activities, reflections or acknowledgments — reads through links are the only cross-domain surface.

## 6. Migration path

Schema 10 → 11: `MIGRATION_10_11` creates the five roadmap tables + 11 indexes, purely additive. Schema 11 → 12: `MIGRATION_11_12` adds `goal_schedule_revisions` + 2 indexes (Prompt 2). Schema 12 → 13: `MIGRATION_12_13` adds `goal_habit_links` + 1 index (Prompt 3). Schema 13 → 14: `MIGRATION_13_14` adds `goal_sessions` + 5 indices (Prompt 5). Exported schemas: `app/schemas/…/14.json` (current), with the full historical set retained. `DatabaseMigrationTest` walks every historical era (2→14, 3→14, … 13→14) plus the v1 full chain and asserts preservation, emptiness of new tables, FK declarations and index presence against the migrated DDL. Future: schema 14+ (Prompt 5, study sessions) — additive by the same discipline.

## 7. Backup mapping (formats v10–v12)

`LifeTrackBackup` gains nine `@EncodeDefault(NEVER)` lists — v10: `goals`, `goalPhases`, `goalMilestones`, `goalTasks`, `goalTaskDependencies`; v11: `goalScheduleRevisions`; v12: `goalHabitLinks`; v13: `goalSessions` — present only when non-empty, so plain v3–v9 exports remain key-free and each older format decodes unchanged. Full rows are exported (all columns, row ids included). Export computes `formatVersion` by cascade: any session → 13, else any habit-goal link → 12, else any schedule revision → 11, else any goal record → 10, else the pre-goal formats. Restore is replace-all inside the existing single restore transaction: clear goal tables (links + revisions first, then edges → tasks → milestones → phases → goals), insert parents before dependents, links and revisions last. Import normalizes text (trim, blank→null) exactly like repository saves and validates link references (goal+habit must resolve), duplicate pairs and caps. `appLockEnabled` never appears in any format.

The **Goal Plan JSON** (`kind: "lifetrack.goal-plan"`, `schemaVersion: 1`) is deliberately a *different document type* — not a backup, never restorable as one, defined in [`docs/GOAL_PLAN_FORMAT.md`](GOAL_PLAN_FORMAT.md). It carries no row ids and no completion history; it participates in neither export nor import of backups.
