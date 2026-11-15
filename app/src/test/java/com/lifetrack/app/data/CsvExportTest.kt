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
        createdAt: Long = 1_000, updatedAt: Long = 2_000) = Habit(
        id = id, name = name, startDate = startDate, goalHistory = goalHistory,
        scheduleHistory = scheduleHistory, createdAt = createdAt, updatedAt = updatedAt
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
        assertEquals("1", early[8]); assertEquals("times", early[9])
        assertEquals("Mon Tue Wed Thu Fri Sat Sun", early[7])
        assertEquals("5", late[8]); assertEquals("pages", late[9])
        assertEquals("Mon", late[7])
    }

    @Test fun `a habit without logs exports one detail row with empty log fields`() {
        val text = export(listOf(habit(id = 7, name = "Solo")))
        val row = fields(rows(text)[1])
        assertEquals(CsvExport.COLUMNS.size, row.size)
        assertEquals("7", row[0])
        assertEquals("Solo", row[1])
        assertEquals("FALSE", row[10])
        assertEquals((13..19).all { row[it].isEmpty() }, true)
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
        val emitted = rows(text).drop(1).map { row -> val f = fields(row); f[0].toLong() to f[13] }
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
        assertEquals("TRUE", row[16])
        assertEquals("SKIPPED", row[14])
        assertEquals("0", row[15])
    }
}
