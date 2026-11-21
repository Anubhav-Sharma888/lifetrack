# AI prompt template: generate a LifeTrack Goal Plan JSON

Copy this template, replace the `<ANGLE-BRACKET>` variables, and give it to any AI (ChatGPT,
Gemini, Claude…). The AI must respond with **only** the JSON file content — no commentary, no
markdown fences — following the rules below. The result is a Goal Plan file LifeTrack can
validate and (in a later release) import.

---

You are generating a LifeTrack **Goal Plan** JSON document.

**Goal:** <what you want to achieve>
**Experience level:** <beginner / intermediate / advanced>
**Duration:** <e.g. 24 weeks>
**Hours available per week:** <e.g. 6>
**Starting date:** <e.g. 2026-11-23>
**Milestone frequency:** <e.g. every 3–4 weeks>
**Constraints:** <anything that should shape the plan>

Rules:

1. Respond with valid JSON only — no commentary, no markdown code fences.
2. Use exactly this document shape:
   `"kind": "lifetrack.goal-plan"`, `"schemaVersion": 1`, `"planId"`, `"goal"`, `"phases"`,
   `"milestones"`, `"tasks"`, `"dependencies"`.
3. Every record (`planId`, goal, phases, milestones, tasks) carries a unique random UUID in the
   canonical form `xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx`. Never reuse a UUID and never derive one
   from a title.
4. All dates are calendar dates in `yyyy-MM-dd` form, consistent with the starting date and
   duration. A phase may overlap another phase.
5. Break the goal into realistic phases and small, actionable tasks (one task ≈ one work
   session). Do not claim mastery based on time spent.
6. Create measurable milestones at the requested frequency. A milestone is a verifiable outcome,
   not a chapter title.
7. Add `dependencies` where one task needs another finished first. Dependencies must be acyclic,
   reference task UUIDs from this same document, and never self-reference or duplicate.
8. Do not invent completion history: there are no status, completedAt or progress fields in the
   schema. Everything is planned work.
9. Do not add any keys beyond the schema (no habit suggestions, no sessions, no resources, no
   notes arrays). Unknown keys make the file invalid.
10. Keep task titles specific and short (≤ 120 characters); use the description fields for detail.
11. Respect the weekly hours: the planned workload per week should roughly fit.
12. Estimate `estimatedMinutes` per task when reasonable (1–10,080).

Schema summary (v1): goal { uuid, title, description?, category?, priority? (LOW|MEDIUM|HIGH),
plannedStartDate?, targetDate? }; phase { uuid, name, description?, order?, plannedStartDate?,
plannedEndDate? }; milestone { uuid, title, description?, phaseUuid?, targetDate? };
task { uuid, title, description?, order?, phaseUuid?, milestoneUuid?, plannedDate?, deadline?,
estimatedMinutes? }; dependency { taskUuid, prerequisiteUuid }. Limits: ≤ 500 phases,
≤ 1,000 milestones, ≤ 5,000 tasks, ≤ 10,000 dependencies.

Example shape (structure only — content is yours):

{ "kind": "lifetrack.goal-plan", "schemaVersion": 1, "planId": "<uuid>", "goal": { ... },
  "phases": [ { ... } ], "milestones": [ { ... } ], "tasks": [ { ... } ],
  "dependencies": [ { "taskUuid": "<uuid>", "prerequisiteUuid": "<uuid>" } ] }
