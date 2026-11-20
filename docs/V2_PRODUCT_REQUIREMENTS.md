# LifeTrack Version 2 — Product Requirements Document

**Status:** Authoritative for the Version 2 line (branch `version-2`, target release `2.1.0`).
**Scope of this document:** the complete Version 2 feature set, with the Prompt 1 data foundation marked as implemented. Later prompts implement their own sections.
**Supersedes:** no prior PRD exists; this is the first Version 2 requirements document.

---

## 1. User problem

LifeTrack v1 tracks *actions*: daily habits, directional activities, streaks and reflections. It answers "did I do what I planned today?" — but it cannot answer "what am I trying to accomplish over the next six months, how is that broken down, and am I actually making progress?"

A user preparing for a long undertaking (learning machine learning over 24 weeks, writing a thesis, training for a certification) currently has two bad options: one monolithic habit ("study"), which proves consistency but not completion; or dozens of free-floating habits, which lose the structure. Neither records *what was genuinely completed*, *what is late*, or *what should be worked on next*.

## 2. Product vision

LifeTrack Version 2 introduces **Goal Roadmaps**: a structured planning layer that coexists with habits — never replaces them.

The user can define a long-term objective, break it into phases, mark verifiable milestones, schedule concrete tasks, and record actual completions. The app separates *planning* from *progress*: a delayed milestone stays visibly delayed; a finished task stays finished; elapsed time never fabricates completion. When circumstances change, the plan adapts without rewriting history. When the user prefers, an AI assistant can draft the whole roadmap as a JSON file that LifeTrack validates, previews and imports.

## 3. Core vocabulary (binding for all prompts)

| Term | Meaning |
|---|---|
| **Goal** | A long-term desired outcome ("Learn machine learning"). |
| **Phase** | A major subdivision of a goal ("Mathematical foundations"). |
| **Milestone** | A verifiable checkpoint that indicates meaningful progress ("Comfortable with linear algebra"). |
| **Task** | A specific unit of work ("Work through vector spaces"). |
| **Habit** | A recurring behavior (existing v1 concept; may *contribute to* a goal, never *completes* one on its own). |
| **Session** | A planned or actual period of work, e.g. studying 17:00–18:00 (Prompt 5). |

These are related but not interchangeable. The schema enforces the distinctions.

## 4. Reference scenario (never hardcoded)

The canonical example is a 24-week machine-learning roadmap: four phases (Mathematical Foundations weeks 1–6, Programming Foundations weeks 7–12, Core ML weeks 13–20, Integration Project weeks 21–24) with weekly tasks and realistic milestones. It is illustrative only: goals may have any number of phases, overlapping date ranges, tasks without phases or deadlines, and durations from days to years. No 24-week limit, no ML-specific terminology, no fixed phase count exists anywhere in the product.

## 5. Non-negotiable product principles

1. **Progress must represent actual work.** Reaching the end of week 6 does not complete the mathematics phase. Completion comes only from explicit user actions. Evidence/notes may support tasks later, but are never mandatory and never auto-derived.
2. **Planning and progress are separate.** Planned dates are intent; completion instants are facts. The original schedule is recoverable; rescheduling edits planning fields only and never touches completion records. Delayed records stay visibly delayed.
3. **Plans are flexible.** Sequential or overlapping phases; tasks without phases, milestones or deadlines; optional tasks; dependencies; rescheduling. No rigid week grid.
4. **Completion is never fabricated.** A passing deadline, a delivered notification, a parent's status and a dependency gate never write a completion. Notification interaction counts as evidence of study only when the user explicitly confirms it.
5. **Local-first.** No account, no remote database, no cloud sync, no `INTERNET` permission, no AI API key. AI-generated plans arrive as local JSON files the user chooses to import.
6. **Existing semantics are preserved.** Habits, activities, Smart Streaks, analytics and achievements behave exactly as in v1.x. Goal analytics never double-count habit progress, and habit records are never written by goal features.

## 6. Functional requirements

### 6.A Goal management (Prompt 2 UI; persistence in Prompt 1)

- FR-1: Create, edit, archive and delete goals.
- FR-2: A goal has a title (1–120 chars), optional description (≤2,000), category (1–40), priority (LOW/MEDIUM/HIGH), optional planned start and target dates, and a status.
- FR-3: Goal status is one of PLANNED, ACTIVE, COMPLETED, PAUSED, CANCELLED with a legal-transition graph (§7.1); COMPLETED records its instant; reopen is explicit.
- FR-4: Archiving is a non-destructive visibility toggle (distinct from CANCELLED) with its own operation, mirroring habits; archived goals keep children and history.
- FR-5: Goal views show progress, completion status, upcoming tasks and delayed tasks.

### 6.B Roadmap planning (Prompts 1–2)

- FR-6: A goal holds ordered phases (1–500 per goal); phases carry optional planned start/end dates and status.
- FR-7: A goal holds milestones (≤1,000 per goal), optionally attached to a phase; a milestone is PENDING or ACHIEVED, and ACHIEVED records its instant.
- FR-8: A goal holds tasks (≤5,000 per goal), optionally attached to a phase and/or milestone; tasks carry planned date, optional deadline, optional estimated minutes (≤10,080) and an explicit sort order.
- FR-9: Tasks may declare "needs first" dependencies on other tasks of the **same goal**; self-edges, duplicate edges, cross-goal edges and cycles are rejected. Dependencies are derived gates, never stored states.
- FR-10: Deleting a phase detaches its milestones and tasks (they survive as goal-level records). Deleting a milestone detaches its tasks. Deleting a task removes its dependency edges. Deleting a goal removes its whole tree in one transaction.
- FR-11: Completed records remain editable in planning metadata (titles, notes, dates); status changes follow the transition graph; a completed task's completion instant is never rewritten by an edit.

### 6.C Progress tracking (Prompt 3)

- FR-12: Task completion is an explicit user action recorded with an instant; reopen is explicit and clears the instant.
- FR-13: Milestone achievement is user-confirmed (verification UI arrives in Prompt 3; the persisted state machine ships in Prompt 1).
- FR-14: Progress summaries derive from record states; nothing is completed automatically.
- FR-15: Habits may be *linked* to a goal from Prompt 3 (additive migration). A linked habit contributes consistency/activity signals; it never completes a task or a goal without an explicit user rule, and habit logs are read-only to goal features.

### 6.D AI-generated plan import (Prompts 3–4)

- FR-16: A user asks any AI for a roadmap; the resulting **Goal Plan JSON** is a distinct document type from the full LifeTrack backup (separate format identity and schema, defined in Prompt 3).
- FR-17: Import validates the file, previews contents (goal/phase/milestone/task counts), detects duplicates by stable UUID, and offers explicit conflict resolution (keep existing / keep imported / import as copy / skip).
- FR-18: Partial import is non-destructive and transactional; it never erases progress history; malformed or unsafe data is rejected before any write. Imported records pass the same validation as manually created ones.

### 6.E Study sessions (Prompts 5–6)

- FR-19: Users schedule work periods (start time, planned duration) optionally linked to a task, phase or goal.
- FR-20: Start reminders and end-of-session confirmations are delivered via WorkManager; the user confirms completion, partial completion or skipping. Notification delivery is best-effort — exact alarms are not promised unless an explicitly supported and verified design lands.
- FR-21: Sessions record planned vs. actual times with timezone-safe semantics (§9.5).

### 6.F Goal analytics (Prompt 6)

- FR-22: Goal/phase/task completion, planned vs. actual progress, time invested, schedule adherence, delayed milestones and completion trends.
- FR-23: Goal analytics are computed from goal records only; they are distinct from habit streak calculations and never double-count habit progress.

## 7. State machines (persistence level, shipped in Prompt 1)

### 7.1 Goal
`PLANNED → ACTIVE, CANCELLED · ACTIVE → PAUSED, COMPLETED, CANCELLED · PAUSED → ACTIVE, CANCELLED · COMPLETED → ACTIVE (explicit reopen) · CANCELLED → PLANNED (explicit revive)`. Saving without a status change is always legal. New goals start PLANNED. `status == COMPLETED ⇔ completedAt != null` is an enforced invariant.

### 7.2 Phase
`PLANNED → ACTIVE, CANCELLED · ACTIVE → COMPLETED, CANCELLED · COMPLETED → ACTIVE (reopen) · CANCELLED → PLANNED (revive)`. Phase cancellation is retained deliberately (an abandoned sub-effort within an active goal must stay visible rather than be deleted); goal-level pause/cancel does not express this.

### 7.3 Milestone
`PENDING → ACHIEVED · ACHIEVED → PENDING (explicit revert)`. `status == ACHIEVED ⇔ achievedAt != null`.

### 7.4 Task
`NOT_STARTED → IN_PROGRESS, COMPLETED, SKIPPED · IN_PROGRESS → NOT_STARTED, COMPLETED, SKIPPED · COMPLETED → NOT_STARTED (reopen) · SKIPPED → NOT_STARTED (unskip)`. Reopening is a single concept: a reopened record lands in NOT_STARTED and may then move on. `status == COMPLETED ⇔ completedAt != null`. Whether SKIPPED satisfies a dependency gate is a read-time policy decided in Prompt 3; it is never stored.

## 8. Non-functional requirements

- NFR-1 Offline-first: all features work with no network; no new permissions beyond v1.x.
- NFR-2 Data safety: Room is canonical; JSON backups (formats v1–v10) are the only restorable export; schema changes are additive migrations with exported schemas; no destructive fallback.
- NFR-3 Bounded validation: every limit is sized ~25× above a real 24-week roadmap (§10) so AI-generated plans import comfortably while no import can exhaust memory.
- NFR-4 Deterministic identity: stable external UUIDs on every goal record; display names are never identifiers; row ids are local only.
- NFR-5 Accessibility: goal UI follows the app's established conventions (48dp targets, roles, live regions, no decorative emoji in TalkBack) from Prompt 2 onward.
- NFR-6 Performance: dependency validation is linear in edges; cycle detection is bounded; observation flows are goal-scoped.

## 9. Failure scenarios and edge-case policies

| Scenario | Policy |
|---|---|
| Goal deletion | Whole tree removed explicitly in one transaction; habits/activities/reflections untouched. |
| Goal archiving | Non-destructive toggle; children intact; reversible; excluded from active views later. |
| Phase deletion | Milestones and tasks detach (SET NULL), never destroyed. |
| Task deletion | Its dependency edges removed in the same transaction; no dangling rows. |
| Dependency deletion | Removes only the gate; task statuses unchanged. |
| Completed tasks after rescheduling | Planning fields editable; completion record immutable; completed tasks never re-enter overdue logic. |
| Tasks without a phase | First-class state; visible as goal-level work. |
| Milestones without a phase | First-class state, anchored to the goal. |
| Tasks without due dates | Legal; excluded from due/overdue logic; ordered by sort order. |
| Overlapping phases | Legal; phase date ranges are informative only. |
| Duplicate task/goal names | Legal; identity is UUID-based, never names. |
| Imported external IDs | Must be canonical UUIDs, globally unique across all goal records in a backup; collisions reject the import. |
| Task ordering changes | Explicit reorder APIs reindex goal-scoped order; relative order never inferred from timestamps. |
| Interrupted transactions | Every multi-table write (restore, tree delete, future plan import) is one Room transaction: all-or-nothing. |
| Encrypted backups | Goal data rides the existing envelope unchanged; wrong passphrase writes nothing. |

## 10. Validation limits

| Field | Limit |
|---|---|
| Goal/phase/milestone/task title or name | 1–120 characters, not blank |
| Descriptions | ≤ 2,000 characters |
| Category | 1–40 characters |
| Estimated task duration | 1–10,080 minutes (≤ 7 days per task) |
| Calendar dates | 1900-01-01 … today+100 years (past dates legal — delays must stay visible) |
| Counts per backup | goals ≤ 100 · phases ≤ 500 · milestones ≤ 1,000 · tasks ≤ 5,000 · dependency edges ≤ 10,000 |
| Timestamps | epoch millis ≥ 0, `createdAt ≤ updatedAt`; completion instants bound to status invariants |

## 11. Data integrity requirements

- Foreign keys declared for every child edge (goal-owned children CASCADE; optional phase/milestone attachments SET NULL; dependency edges CASCADE both directions). Room enforces them at runtime, and the repository additionally performs every child delete explicitly in the declared order — deterministic ordering, observable semantics; the migration test pins the DDL declarations.
- Full-backup restore is a replace-all operation that clears goal tables (edges → tasks → milestones → phases → goals) and reinserts parents before dependents.
- App lock never travels in backups; goal records never alter that contract.

## 12. Offline-first, security, accessibility expectations

- No new permission, worker, receiver or network primitive is introduced by goal features.
- Goal Plan JSON import (Prompt 4) is subject to the same structural pre-checks as backups (UTF-8 strictness, JSON depth ≤ 64, size caps) before parsing.
- Accessibility expectations for goal UI are set in Prompt 2 (roles, targets, traversal order) and audited in Prompt 7.

## 13. Feature boundaries

- v1 habits/activities/analytics/achievements: unchanged; goal features add, never modify.
- No evidence uploads in this version; notes may support tasks later.
- No cross-goal dependencies in this version (schema leaves the door open: the edge table references plain task ids).
- No shared/collaborative goals, no calendar feed, no widgets for goals in this version (candidates post-2.1.0).

## 14. Acceptance criteria — Prompt 1 (data foundation)

1. A goal can be created (identity, PLANNED, sort order assigned), read, updated, archived and its status walked through the legal graph; illegal transitions are rejected.
2. Completion instants are reconciled with statuses on every save (set on completion, cleared on reopen, preserved across metadata edits).
3. Phases, milestones and tasks attach to their goal with same-goal parent validation; cross-goal references and dangling parents are rejected.
4. Dependencies persist; self/cross-goal/duplicate/circular edges are rejected; diamond graphs pass; removing an edge or task leaves no dangling rows.
5. Phase/milestone deletion detaches children; goal deletion clears the tree without touching habits.
6. Count caps reject overflow at the repository and in backups.
7. Schema 10 → 11 migrates additively; every historical era (1–10) reaches 11 with all pre-existing data intact; new tables start empty and accept rows; FK declarations and all 11 new indexes exist.
8. Backup v10 round-trips a goal tree exactly; older formats v1–v9 import unchanged and key-free; a v9 file carrying goal keys is rejected; damaged imports write nothing.
9. `appLockEnabled` remains absent from every export and is never restored.
10. The full unit suite, lint and debug build pass with the established resource limits.
