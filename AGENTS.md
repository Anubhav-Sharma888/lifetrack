# LifeTrack — agent instructions

Offline-first Android habit tracker: Kotlin, Jetpack Compose Material 3, Room, MVVM + repository, Hilt, WorkManager, Glance. Source root: `app/src/main/java/com/lifetrack/app/`.

## Non-negotiable constraints (apply to every session and agent)

- Offline-first always: no `INTERNET` permission, no accounts, no analytics/cloud sync, no automatic app-data cloud backup.
- Room is the canonical preference store; JSON backups (format v3/v4) are the only restorable export. Any schema change needs an additive Room migration with exported `app/schemas/*.json`, no destructive fallback, and backup-format versioning with in-memory conversion of older formats.
- Preserve existing user data, tracking rules and backward compatibility. Tracking semantics live in `domain/Analytics.kt` and `HabitRepository.kt`; change them only with test coverage.
- Do not commit, push, merge, or modify `main` unless the user explicitly asks.
- Report faithfully: state the exact commands and results you observed; never claim a check you did not run (see VALIDATION.md culture).

## Agent routing (lead assigns scope; one writer per file)

| Concern | Agent | Files / scope |
|---|---|---|
| Requirements, acceptance criteria | product-requirements-analyst | no code; docs/ notes |
| Structure, boundaries, migration strategy | architecture-specialist | analysis + agreed structural edits |
| Data-layer logic (repo, backup, CSV, crypto) | backend-engineer | `data/` (`HabitRepository.kt`, `Backup.kt`, `BackupCrypto.kt`, `CsvExport.kt`), `domain/` |
| Room schema/entities/migrations only | database-migration-specialist | `data/Models.kt`, `AppDatabase.kt`, `DatabaseMigrations.kt`, `app/schemas/` |
| Compose UI & widgets | frontend-engineer | `ui/`, `widget/`, `res/` |
| Usability/accessibility assessment | ui-ux-specialist | read-first; coordinate edits with frontend-engineer |
| Security & privacy review | security-privacy-specialist | `BackupCrypto.kt`, escaping guards, manifest, `res/xml/backup_rules.xml`, data flows |
| Cross-component wiring | integration-engineer | ViewModel ↔ repository ↔ widget/reminders ↔ `LifeTrackApplication.kt` |
| Build/versioning/release config | devops-deployment-specialist | `build.gradle.kts`, `gradle.properties`, versionCode/versionName |
| Tests & verification | quality-analyst | `app/src/test/`, running suites |
| Post-work unit review | independent-reviewer | strictly read-only (Read/Grep/Glob); findings go to the lead |
| Final release assessment | gold-final-reviewer | after all specialist work settles; coordinates fixes via the lead |

Avoid redundant analysis: one specialist per concern; reviewers see finished diffs, not in-progress work.

## Coordination limits

- Maximum two lightweight analysis/review agents concurrently; heavy builds and test suites run sequentially, one at a time.
- UI, database, and security changes each get one owning agent; the lead merges their reports before review.

## Resource policy for builds/tests (this machine)

- JDK 21 JBR: `C:\Program Files\Android\Android Studio2\jbr` (set `JAVA_HOME`; no JDK on PATH).
- Gradle must always run with `--max-workers=2 --no-parallel --no-daemon`; `org.gradle.workers.max=2` is pinned in `gradle.properties` as the effective default.
- Check free RAM before building (`Get-CimInstance Win32_OperatingSystem`). Pinned heap is `-Xmx3g`; if free RAM is under ~4 GB, cap the workload instead of building anyway: `-Dorg.gradle.jvmargs=-Xmx1200m -Dkotlin.compiler.execution.strategy=in-process` plus a focused `--tests` filter. Robolectric suites need the full pinned heap — defer them until memory frees.
- Run builds/tests at BelowNormal priority (`$p.PriorityClass` after `Start-Process -PassThru`; `Start-Process -Priority` is unsupported on Windows PowerShell 5.1).
- Focused suites: `gradlew.bat --max-workers=2 --no-parallel --no-daemon testDebugUnitTest` (add `--tests <class>`); full check per README: `assembleDebug testDebugUnitTest lintDebug`.

## Current state

- Branch `develop/v1.8.0`; versionName 1.7.0 / versionCode 8; Room schema 4; backup format v3/v4.
- v1.8.0 plan (agreed): 1) CSV export, 2) app lock, 3) daily reflection (mood + journal), 4) milestones, 5) flexible scheduling (every-N-days / weekly quotas). Single Room migration 4→5 and backup format v3→v4-bump when those land.
- Known gap: README still says "Room database schema 4 … backup format version 2" in places — refresh docs when releasing 1.8.0.
