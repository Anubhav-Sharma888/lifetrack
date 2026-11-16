package com.lifetrack.app.data

import com.lifetrack.app.domain.Analytics
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Spreadsheet analysis copy of habit details and check-in history.
 *
 * This is deliberately not a backup: JSON backups stay the only restorable format, and the
 * CSV omits settings, freezes, pauses, schedule/goal history ledgers, the activity ledger,
 * stacking links, sort order, archive dates and reminder times.
 * Writing it must not touch `lastExportAt`, so the 30-day backup reminder stays accurate.
 *
 * Excel compatibility: a UTF-8 BOM lets Excel detect UTF-8, and rows use the RFC 4180 CRLF
 * terminator. Cells containing commas, quotes or line breaks are quoted with inner quotes
 * doubled. A cell whose first non-whitespace character could start a spreadsheet formula
 * (=, +, -, @, tab, CR, LF) receives a leading apostrophe, the standard mitigation; programs
 * that honor it display the text unchanged, and nothing else in the value is rewritten.
 */
object CsvExport {
    const val MIME = "text/csv"

    internal val COLUMNS = listOf(
        "habit_id", "habit_name", "habit_description", "habit_category", "habit_icon",
        "habit_color", "habit_start_date", "habit_scheduled_days", "habit_target", "habit_unit",
        "habit_archived", "habit_created_at", "habit_updated_at",
        "log_date", "log_status", "log_progress", "log_frozen", "log_note",
        "log_created_at", "log_updated_at"
    )

    /** Characters a spreadsheet evaluates as a formula when they begin a cell. */
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r', '\n')

    private val WEEKDAYS = (1..7).map { day ->
        DayOfWeek.of(day).getDisplayName(TextStyle.SHORT, Locale.ROOT)
    }

    /** One row per check-in; a habit without any logs still gets one detail row with empty log fields. */
    fun write(snapshot: HabitSnapshot, out: Appendable, zone: ZoneId = ZoneId.systemDefault()) {
        out.append('\uFEFF')
        appendRow(out, COLUMNS)
        val byHabit = snapshot.logs.groupBy { it.habitId }
        snapshot.habits.forEach { habit ->
            val own = byHabit[habit.id].orEmpty().sortedBy { it.date }
            if (own.isEmpty()) appendRow(out, cells(habit, zone, null))
            else own.forEach { log -> appendRow(out, cells(habit, zone, log)) }
        }
    }

    /** Log rows carry the schedule and goal effective on that date, matching the analytics rules. */
    private fun cells(habit: Habit, zone: ZoneId, log: HabitLog?): List<String> {
        val asOf = log?.date
        val scheduled = asOf?.let { Analytics.scheduledDays(habit, it) } ?: habit.scheduledDays
        val goal = asOf?.let { habit.goalOn(it) } ?: habit
        return listOf(
            habit.id.toString(), habit.name, habit.description.orEmpty(), habit.category, habit.icon,
            habit.colorHex, habit.startDate.toString(),
            scheduled.joinToString(" ") { WEEKDAYS.getOrElse(it - 1) { "?" } },
            goal.targetCount.toString(), goal.targetUnit,
            if (habit.isArchived) "TRUE" else "FALSE",
            timestamp(habit.createdAt, zone), timestamp(habit.updatedAt, zone),
            asOf?.toString().orEmpty(), log?.status?.name.orEmpty(), log?.progress?.toString().orEmpty(),
            when { log == null -> ""; log.isFrozen -> "TRUE"; else -> "FALSE" }, log?.note.orEmpty(),
            timestamp(log?.createdAt, zone), timestamp(log?.updatedAt, zone)
        )
    }

    private fun timestamp(millis: Long?, zone: ZoneId): String =
        millis?.let { DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(Instant.ofEpochMilli(it).atZone(zone)) }.orEmpty()

    private fun appendRow(out: Appendable, values: List<String>) {
        values.forEachIndexed { index, value ->
            if (index > 0) out.append(',')
            out.append(cell(value))
        }
        out.append("\r\n")
    }

    /** Formula-injection guard first, then RFC 4180 escaping; every exported cell passes through here.
     *  The guard checks both the literal first character and the first non-whitespace character:
     *  trimStart would otherwise hide tab/CR/LF prefixes, and some consumers evaluate "  =hidden". */
    internal fun cell(value: String): String {
        if (value.isEmpty()) return value
        val guarded = if (listOfNotNull(value.firstOrNull(), value.trimStart().firstOrNull())
                .any { it in FORMULA_PREFIXES }) "'$value" else value
        val escaped = guarded.replace("\"", "\"\"")
        return if (escaped.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"$escaped\"" else escaped
    }
}
