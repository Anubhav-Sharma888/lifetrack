# LifeTrack Goal Plan JSON — Format Specification (schemaVersion 1)

A **Goal Plan** is a standalone, versioned JSON document that an external AI (or a person) can
generate to describe a complete roadmap: one goal, its phases, milestones, tasks and task
dependencies. It is a *proposed plan* — not user history.

**Identity note:** a Goal Plan is deliberately a different document type from a full LifeTrack
backup. Backups use `"formatVersion"` and are restored wholesale; Goal Plans use
`"kind": "lifetrack.goal-plan"` + `"schemaVersion"` and are (in a later release) imported
partially against existing data. The two are never interchangeable.

## Document envelope

| Property | Type | Required | Meaning |
|---|---|---|---|
| `kind` | string | yes | Exactly `"lifetrack.goal-plan"`. |
| `schemaVersion` | integer | yes | Exactly `1` for this contract. |
| `planId` | string | yes | Canonical UUID (8-4-4-4-12 hex). The document's stable identity. |
| `goal` | object | yes | The proposed goal (below). |
| `phases` | array | no | Up to 500 phase objects. |
| `milestones` | array | no | Up to 1,000 milestone objects. |
| `tasks` | array | no | Up to 5,000 task objects. |
| `dependencies` | array | no | Up to 10,000 dependency edges. |

Unknown properties are **rejected**, not ignored — AI output that drifts from the schema fails
loudly instead of silently dropping content.

## Goal object

| Property | Type | Required | Constraints |
|---|---|---|---|
| `uuid` | string | yes | Canonical UUID; the goal's stable external identity. |
| `title` | string | yes | 1–120 characters. |
| `description` | string | no | ≤ 2,000 characters. |
| `category` | string | no | 1–40 characters. |
| `priority` | string | no | `LOW`, `MEDIUM` or `HIGH`. |
| `plannedStartDate` | date | no | ISO `yyyy-MM-dd`, ≤ `targetDate`. |
| `targetDate` | date | no | ISO `yyyy-MM-dd`. |

## Phase object

`uuid` (canonical UUID, required), `name` (1–120, required), `description` (≤ 2,000), `order`
(int ≥ 0), `plannedStartDate` / `plannedEndDate` (dates, start ≤ end). Phases may overlap; no
fixed week grid is imposed.

## Milestone object

`uuid` (required), `title` (1–120, required), `description` (≤ 2,000), `phaseUuid` (optional —
must reference an existing phase `uuid`), `targetDate` (optional date). Milestones are always
imported as **pending**: achievement is user-verified in the app, never accepted from a document.

## Task object

`uuid` (required), `title` (1–120, required), `description` (≤ 2,000), `order` (int ≥ 0),
`phaseUuid` (optional), `milestoneUuid` (optional), `plannedDate` / `deadline` (dates,
planned ≤ deadline), `estimatedMinutes` (1–10,080). Tasks are imported as **not started** — the
schema has no `status`, `completedAt` or progress fields, so fabricated history is
unrepresentable rather than filtered.

## Dependency object

`taskUuid` + `prerequisiteUuid` — both must reference task `uuid`s **in the same document**.
Self-edges, duplicates and cycles are rejected. Dependencies are derived at read time; no gate
state is persisted.

## Dates

All dates are ISO-8601 calendar dates (`yyyy-MM-dd`), interpreted as local calendar days — never
as instants or timestamps. Dates may be in the past or future; planned dates describe intent.

## Identity requirements

- Every record carries a caller-generated canonical UUID minted **when the document is written**,
  not during parsing. Parsing the same file twice always produces identical identities.
- Identifiers never depend on titles, ordering or array position.
- References (phase/milestone/task links, dependency edges) use these UUIDs — never names, never
  local database ids.

## What this format does NOT support (schemaVersion 1)

- Completion history of any kind (statuses, completion timestamps, achievement dates).
- Habit logs, habit links, study sessions or schedules.
- Multiple goals per document (one `goal` per plan).
- Local database row ids or Android-specific fields.

## File limits

Files must be ≤ 2 MB, JSON nesting depth ≤ 64, and all collection caps above apply. Parsing
performs no database writes; the in-app import (preview, conflict decisions, one transactional
apply) is a separate, explicitly confirmed operation.
