package com.lifetrack.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The DST and timezone-correctness policy (release-critical): planned wall-clock times resolve
 * deterministically in the session's own recorded zone — gaps jump forward, overlaps take the
 * earlier instant — and actual times stay independent UTC facts.
 */
class SessionTimePolicyTest {
    private fun session(startDate: LocalDate, startTime: LocalTime, endDate: LocalDate, endTime: LocalTime,
                        timezoneId: String) = GoalSession(
        uuid = "00000000-0000-4000-8000-000000000001", title = "S",
        plannedStartDate = startDate, plannedStartTime = startTime,
        plannedEndDate = endDate, plannedEndTime = endTime, timezoneId = timezoneId
    )

    // ---- Ordinary local time: one unambiguous instant ----

    @Test fun `an ordinary local time resolves to the exact zone instant`() {
        val session = session(LocalDate.of(2026, 7, 15), LocalTime.of(17, 0),
            LocalDate.of(2026, 7, 15), LocalTime.of(18, 30), "Europe/Berlin")
        val expected = LocalDateTime.of(2026, 7, 15, 17, 0).atZone(ZoneId.of("Europe/Berlin")).toInstant()
        assertEquals(expected, SessionTimePolicy.startInstant(session))
    }

    // ---- DST gap (spring forward): the nonexistent local time jumps forward ----

    @Test fun `a gap start resolves to the instant after the gap`() {
        // America/New_York: 2026-03-08 02:30 does not exist (clocks jump 02:00 → 03:00).
        val session = session(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30),
            LocalDate.of(2026, 3, 8), LocalTime.of(4, 0), "America/New_York")
        val resolved = SessionTimePolicy.startInstant(session)
        val expected = LocalDateTime.of(2026, 3, 8, 3, 30).atZone(ZoneId.of("America/New_York")).toInstant()
        assertEquals(expected, resolved)
        // The window's real duration shrinks across the gap: 02:30 resolves to 03:30, so the
        // planned 90 wall-clock minutes are 30 real minutes between the two resolved instants.
        val end = SessionTimePolicy.endInstant(session)
        assertEquals(30L * 60_000, java.time.Duration.between(resolved, end).toMillis())
    }

    // ---- DST overlap (fall back): the earlier of the two instants wins ----

    @Test fun `an overlap start resolves to the earlier occurrence`() {
        // America/New_York: 2026-11-01 01:30 happens twice (EDT then EST).
        val session = session(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30),
            LocalDate.of(2026, 11, 1), LocalTime.of(3, 0), "America/New_York")
        val resolved = SessionTimePolicy.startInstant(session)
        val earlier = LocalDateTime.of(2026, 11, 1, 1, 30).atZone(ZoneId.of("America/New_York")).toInstant()
        assertEquals(earlier, resolved)
        val offset = resolved.atZone(ZoneId.of("America/New_York")).offset
        assertEquals("-04:00", offset.id)
    }

    // ---- Zone change preservation: planned fields stay, resolution follows the recorded zone ----

    @Test fun `the same wall clock resolves differently per recorded zone without rewriting the session`() {
        val newYork = session(LocalDate.of(2026, 7, 15), LocalTime.of(17, 0),
            LocalDate.of(2026, 7, 15), LocalTime.of(18, 0), "America/New_York")
        val berlin = newYork.copy(timezoneId = "Europe/Berlin")
        // Both sessions keep identical planned wall-clock fields; only the recorded zone differs.
        assertEquals(newYork.plannedStartTime, berlin.plannedStartTime)
        val nyInstant = SessionTimePolicy.startInstant(newYork)
        val berlinInstant = SessionTimePolicy.startInstant(berlin)
        // 17:00 EDT = 21:00 UTC and 17:00 CEST = 15:00 UTC: the same wall clock is six hours
        // later in absolute time in New York than in Berlin.
        assertEquals(6L * 3_600_000, java.time.Duration.between(berlinInstant, nyInstant).toMillis())
    }

    // ---- Actual time independence ----

    @Test fun `actual instants are plain UTC facts independent of the session zone`() {
        val session = session(LocalDate.of(2026, 7, 15), LocalTime.of(17, 0),
            LocalDate.of(2026, 7, 15), LocalTime.of(18, 0), "Europe/Berlin")
        val startedAt = 1_783_000_000_000L
        val completed = session.copy(actualStartAt = startedAt, actualEndAt = startedAt + 3_600_000,
            status = GoalSessionStatus.COMPLETED.name)
        assertEquals(startedAt, completed.actualStartAt)
        GoalSessionValidator.validateStatusPair(completed.status, completed.actualStartAt, completed.actualEndAt)
    }

    @Test fun `an actual end before its start is rejected`() {
        try {
            GoalSessionValidator.validateStatusPair(GoalSessionStatus.COMPLETED.name, 5_000L, 1_000L)
            fail("An inverted actual pair must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("cannot end before it started"))
        }
    }

    @Test fun `an invalid stored status pair is rejected on import`() {
        // COMPLETED must carry an end instant; IN_PROGRESS must not carry one.
        try {
            GoalSessionValidator.validateStatusPair(GoalSessionStatus.COMPLETED.name, null, null)
            fail("A completed session without an end instant must be rejected.")
        } catch (error: IllegalArgumentException) { /* expected */ }
        try {
            GoalSessionValidator.validateStatusPair(GoalSessionStatus.IN_PROGRESS.name, null, null)
            fail("A started session without a start instant must be rejected.")
        } catch (error: IllegalArgumentException) { /* expected */ }
    }

    // ---- Leap-year and long-window sanity ----

    @Test fun `a leap-year date resolves normally`() {
        val session = session(LocalDate.of(2028, 2, 29), LocalTime.of(9, 0),
            LocalDate.of(2028, 2, 29), LocalTime.of(10, 0), "UTC")
        val expected = LocalDateTime.of(2028, 2, 29, 9, 0).atZone(ZoneId.of("UTC")).toInstant()
        assertEquals(expected, SessionTimePolicy.startInstant(session))
    }

    @Test fun `cross-midnight under a zone with a half-hour offset stays wall-clock faithful`() {
        val session = session(LocalDate.of(2026, 7, 15), LocalTime.of(23, 30),
            LocalDate.of(2026, 7, 16), LocalTime.of(1, 0), "Asia/Kolkata")
        val start = SessionTimePolicy.startInstant(session)
        val end = SessionTimePolicy.endInstant(session)
        assertEquals(90L * 60_000, java.time.Duration.between(start, end).toMillis())
    }

    // ---- Validator: planned window guards ----

    @Test fun `the validator accepts a legitimate long session and rejects beyond the cap`() {
        val today = LocalDate.of(2026, 11, 22)
        val valid = session(today, LocalTime.of(9, 0), today.plusDays(1), LocalTime.of(8, 59), "UTC")
        GoalSessionValidator.validatePlannedWindow(valid, today) // 23h59m — legal
        val tooLong = session(today, LocalTime.of(9, 0), today.plusDays(2), LocalTime.of(9, 1), "UTC")
        try {
            GoalSessionValidator.validatePlannedWindow(tooLong, today)
            fail("Beyond-cap windows must be rejected.")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message!!.contains("24 hours"))
        }
    }
}

