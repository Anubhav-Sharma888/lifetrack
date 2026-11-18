package com.lifetrack.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Exercises the pure CSV rules; SAF writing is covered by HabitRepositoryTest. */
class CsvExportTest {
    private val zone = ZoneId.of("UTC")
    private val start = LocalDate.of(2026, 11, 1)

    private fun habit(id: Long = 1, name: String = "Read", startDate: LocalDate = start,
        goalHistory: List<GoalRevision> = emptyList(), scheduleHistory: List<ScheduleRevision> = emptyList(),
        scheduleMode: ScheduleMode = ScheduleMode.WEEKDAYS, intervalDays: Int? = null,
        intervalAnchor: LocalDate? = null, weeklyTarget: Int? = null,
        createdAt: Long = 1_000, updatedAt: Long = 2_000) = Habit(
        id = id, name = name, startDate = startDate, goalHistory = goalHistory,
        scheduleHistory = scheduleHistory, scheduleMode = scheduleMode.name, intervalDays = intervalDays,
        intervalAnchor = intervalAnchor, weeklyTarget = weeklyTarget, createdAt = createdAt, updatedAt = updatedAt
    )

    private fun log(habitId: Long = 1, date: LocalDate, status: LogStatus = LogStatus.COMPLETED,
        progress: Int = 1, note: String? = null, isFrozen: Boolean = false,
        createdAt: Long = 3_000, updatedAt: Long = 4_000) = HabitLog(
        id = date.toEpochDay(), habitId = habitId, date = date, status = status,
        progress = progress, note = note, isFrozen = isFrozen, createdAt = createdAt, updatedAt = updatedAt
    )

    private fun export(habits: List<Habit>, logs: List<HabitLog> = emptyList(), zone: ZoneId = this.zone): String =
        buildString { CsvExport.write(HabitSnapshot(habits, logs), this, zone) }

    private fun rows(text: String): List<String> =
        text.removePrefix("\uFEFF").trimEnd('\r', '\n').split("\r\n")

    private fun fields(row: String): List<String> = row.split(',')

    @Test fun `header carries a UTF-8 BOM column names and CRLF framing`() {
        val text = export(listOf(habit()))
        assertTrue(text.startsWith("\uFEFF"))
        assertTrue(text.endsWith("\r\n"))
        val header = fields(rows(text)[0])
        assertEquals(CsvExport.COLUMNS.size, header.size)
        assertEquals(CsvExport.COLUMNS, header)
        // BOM, header row and the single detail row: exactly two CRLF terminators.
        assertEquals(2, text.split("\r\n").size - 1)
    }

    @Test fun `commas quotes and newlines are RFC 4180 escaped`() {
        assertEquals("", CsvExport.cell(""))
        assertEquals("plain", CsvExport.cell("plain"))
        assertEquals("\"a,b\"", CsvExport.cell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.cell("say \"hi\""))
        assertEquals("\"line1\nline2\"", CsvExport.cell("line1\nline2"))
        // The guard runs before escaping, and quote doubling still holds.
        assertEquals("\"'=a,\"\"b\"\"\"", CsvExport.cell("=a,\"b\""))
    }

    @Test fun `formula prefixes receive a leading apostrophe`() {
        assertEquals("'=SUM(A1)", CsvExport.cell("=SUM(A1)"))
        assertEquals("'+10", CsvExport.cell("+10"))
        assertEquals("'-5", CsvExport.cell("-5"))
        assertEquals("'@cmd", CsvExport.cell("@cmd"))
        assertEquals("'\tindent", CsvExport.cell("\tindent"))
        assertEquals("'  =hidden", CsvExport.cell("  =hidden"))
        // A formula character that does not open the cell is left alone.
        assertEquals("a=b", CsvExport.cell("a=b"))
        assertEquals("2026-11-01", CsvExport.cell("2026-11-01"))
    }

    @Test fun `log rows carry the schedule and goal effective on the log date`() {
        val habit = habit(
            goalHistory = listOf(
                GoalRevision(start, 1, "times"),
                GoalRevision(start.plusDays(10), 5, "pages")
            ),
            scheduleHistory = listOf(
                ScheduleRevision(start, (1..7).toList()),
                ScheduleRevision(start.plusDays(10), listOf(1))
            )
        )
        val text = export(listOf(habit), listOf(
            log(date = start.plusDays(3)),
            log(date = start.plusDays(12), progress = 5)
        ))
        val early = fields(rows(text)[1])
        val late = fields(rows(text)[2])
        assertEquals("Mon Tue Wed Thu Fri Sat Sun", early[7])
        assertEquals("WEEKDAYS", early[8])
        assertEquals("", early[9]); assertEquals("", early[10]); assertEquals("", early[11])
        assertEquals("1", early[12]); assertEquals("times", early[13])
        assertEquals("WEEKDAYS", late[8])
        assertEquals("5", late[12]); assertEquals("pages", late[13])
        assertEquals("Mon", late[7])
    }

    @Test fun `a habit without logs exports one detail row with empty log fields`() {
        val text = export(listOf(habit(id = 7, name = "Solo")))
        val row = fields(rows(text)[1])
        assertEquals(CsvExport.COLUMNS.size, row.size)
        assertEquals("7", row[0])
        assertEquals("Solo", row[1])
        assertEquals("WEEKDAYS", row[8])
        assertEquals("", row[9]); assertEquals("", row[10]); assertEquals("", row[11])
        assertEquals("FALSE", row[14])
        assertEquals((17..23).all { row[it].isEmpty() }, true)
    }

    @Test fun `rows are grouped by habit and each habit logs are sorted by date`() {
        val text = export(
            listOf(habit(id = 1, name = "Alpha"), habit(id = 2, name = "Beta")),
            listOf(
                log(habitId = 2, date = start.plusDays(2)),
                log(habitId = 1, date = start.plusDays(5)),
                log(habitId = 2, date = start.plusDays(1)),
                log(habitId = 1, date = start.plusDays(3))
            )
        )
        val emitted = rows(text).drop(1).map { row -> val f = fields(row); f[0].toLong() to f[17] }
        assertEquals(listOf(
            1L to start.plusDays(3).toString(),
            1L to start.plusDays(5).toString(),
            2L to start.plusDays(1).toString(),
            2L to start.plusDays(2).toString()
        ), emitted)
    }

    @Test fun `timestamps use the requested zone`() {
        val text = export(listOf(habit(createdAt = 0, updatedAt = 0)), zone = ZoneId.of("Asia/Kolkata"))
        assertTrue(text.contains("1970-01-01T05:30:00+05:30"))
    }

    @Test fun `a multiline note stays inside one quoted cell`() {
        val text = export(listOf(habit()), listOf(log(date = start, note = "Day 1\nDay 2")))
        assertTrue(text.contains("\"Day 1\nDay 2\""))
        assertEquals(2, text.split("\r\n").size - 1)
    }

    @Test fun `a frozen skip with a comma note quotes cleanly`() {
        val text = export(listOf(habit()), listOf(
            log(date = start, status = LogStatus.SKIPPED, progress = 0, isFrozen = true, note = "Away, on holiday")
        ))
        val row = fields(rows(text)[1])
        assertTrue(text.contains("\"Away, on holiday\""))
        assertEquals("TRUE", row[20])
        assertEquals("SKIPPED", row[18])
        assertEquals("0", row[19])
    }

    @Test fun `interval habit rows carry the grid parameters and an empty weekly target`() {
        val habit = habit(scheduleMode = ScheduleMode.EVERY_N_DAYS, intervalDays = 3, intervalAnchor = start)
        val text = export(listOf(habit), listOf(
            log(date = start.plusDays(3), note = "Grid day, with a note"),
            log(date = start.plusDays(4))
        ))
        val onGrid = fields(rows(text)[1])
        val offGrid = fields(rows(text)[2])
        listOf(onGrid, offGrid).forEach { row ->
            assertEquals("EVERY_N_DAYS", row[8])
            assertEquals("3", row[9])
            assertEquals(start.toString(), row[10])
            assertEquals("", row[11])
        }
        // The comma note keeps its RFC 4180 quoting through the new columns.
        assertTrue(text.contains("\"Grid day, with a note\""))
    }

    @Test fun `quota habit rows carry the weekly target and empty interval columns`() {
        val habit = habit(scheduleMode = ScheduleMode.WEEKLY_QUOTA, weeklyTarget = 3)
        val text = export(listOf(habit), listOf(log(date = start)))
        val row = fields(rows(text)[1])
        assertEquals("WEEKLY_QUOTA", row[8])
        assertEquals("", row[9])
        assertEquals("", row[10])
        assertEquals("3", row[11])
    }

    @Test fun `no-log detail rows mirror the stored schedule fields`() {
        val interval = habit(id = 2, name = "Interval", scheduleMode = ScheduleMode.EVERY_N_DAYS,
            intervalDays = 3, intervalAnchor = start)
        val quota = habit(id = 3, name = "Quota", scheduleMode = ScheduleMode.WEEKLY_QUOTA, weeklyTarget = 3)
        val text = export(listOf(interval, quota))
        val intervalRow = fields(rows(text)[1])
        val quotaRow = fields(rows(text)[2])
        assertEquals("EVERY_N_DAYS", intervalRow[8]); assertEquals("3", intervalRow[9])
        assertEquals(start.toString(), intervalRow[10]); assertEquals("", intervalRow[11])
        assertEquals("WEEKLY_QUOTA", quotaRow[8]); assertEquals("", quotaRow[9])
        assertEquals("", quotaRow[10]); assertEquals("3", quotaRow[11])
    }
}
