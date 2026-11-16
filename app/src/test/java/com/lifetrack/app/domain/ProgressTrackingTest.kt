package com.lifetrack.app.domain

import com.lifetrack.app.data.ActivityEntry
import com.lifetrack.app.data.ActivityMode
import com.lifetrack.app.data.WorkoutActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Pure rules for directional activities: positions, measurements and personal records. */
class ProgressTrackingTest {
    private val start = LocalDate.of(2026, 11, 1)

    private fun activity(mode: ActivityMode, startingValue: Double? = null, target: Double? = null, unit: String = "pages") =
        WorkoutActivity(id = 1, name = "Book", unit = unit, target = target, trackingMode = mode.name, startingValue = startingValue)

    private fun entry(date: LocalDate, quantity: Double, activityId: Long = 1) =
        ActivityEntry(id = date.toEpochDay(), activityId = activityId, date = date, quantity = quantity)

    @Test fun `increasing and decreasing read the latest dated entry`() {
        val a = activity(ActivityMode.INCREASING)
        val entries = listOf(entry(start, 50.0), entry(start.plusDays(2), 75.0), entry(start.plusDays(1), 60.0))
        assertEquals(75.0, ProgressTracking.current(a, entries)!!, 0.0)
    }

    @Test fun `personal best reads the maximum ever even when a later attempt is lower`() {
        val a = activity(ActivityMode.PERSONAL_BEST)
        val entries = listOf(entry(start, 17.0), entry(start.plusDays(1), 18.0), entry(start.plusDays(2), 15.0))
        assertEquals(18.0, ProgressTracking.current(a, entries)!!, 0.0)
    }

    @Test fun `no entries yields no current`() {
        assertNull(ProgressTracking.current(activity(ActivityMode.DECREASING), emptyList()))
    }

    @Test fun `explicit starting value wins over the earliest entry`() {
        val a = activity(ActivityMode.INCREASING, startingValue = 50.0)
        assertEquals(50.0, ProgressTracking.baseline(a, listOf(entry(start, 40.0)))!!, 0.0)
    }

    @Test fun `baseline falls back to the earliest entry`() {
        val a = activity(ActivityMode.INCREASING)
        val entries = listOf(entry(start.plusDays(2), 75.0), entry(start, 50.0))
        assertEquals(50.0, ProgressTracking.baseline(a, entries)!!, 0.0)
    }

    @Test fun `increasing resumes one past the last position`() {
        val a = activity(ActivityMode.INCREASING, startingValue = 50.0)
        val entries = listOf(entry(start, 50.0), entry(start.plusDays(2), 75.0))
        assertEquals(76.0, ProgressTracking.continuation(a, entries, start.plusDays(3))!!, 0.0)
    }

    @Test fun `editing a same-day entry suggests its recorded value`() {
        val a = activity(ActivityMode.INCREASING)
        val entries = listOf(entry(start, 75.0))
        assertEquals(75.0, ProgressTracking.continuation(a, entries, start)!!, 0.0)
    }

    @Test fun `backdated positions continue from the latest entry before the requested date`() {
        val a = activity(ActivityMode.INCREASING)
        val entries = listOf(entry(start.plusDays(10), 120.0), entry(start.plusDays(12), 130.0))
        // Filling day 11 continues from day 10's position, not the newest one.
        assertEquals(121.0, ProgressTracking.continuation(a, entries, start.plusDays(11))!!, 0.0)
    }

    @Test fun `decreasing continues from the last reading without inventing direction`() {
        val a = activity(ActivityMode.DECREASING, startingValue = 85.0)
        val entries = listOf(entry(start, 85.0), entry(start.plusDays(1), 82.5))
        assertEquals(82.5, ProgressTracking.continuation(a, entries, start.plusDays(2))!!, 0.0)
    }

    @Test fun `personal best suggests one past the best attempt even after a backdated lower entry`() {
        val a = activity(ActivityMode.PERSONAL_BEST)
        val entries = listOf(entry(start, 17.0), entry(start.plusDays(1), 18.0), entry(start.plusDays(2), 15.0))
        assertEquals(19.0, ProgressTracking.continuation(a, entries, start.plusDays(3))!!, 0.0)
    }

    @Test fun `no earlier entries fall back to the starting value`() {
        val a = activity(ActivityMode.DECREASING, startingValue = 85.0)
        assertEquals(85.0, ProgressTracking.continuation(a, emptyList(), start.plusDays(5))!!, 0.0)
    }

    @Test fun `logged mode keeps its legacy prefill newest entry then target`() {
        val a = activity(ActivityMode.LOGGED, target = 20.0)
        val entries = listOf(entry(start, 12.0), entry(start.plusDays(4), 8.0))
        assertEquals(8.0, ProgressTracking.continuation(a, entries, start.plusDays(2))!!, 0.0)
        assertEquals(20.0, ProgressTracking.continuation(a, emptyList(), start)!!, 0.0)
    }

    @Test fun `increasing fraction measures position toward the goal`() {
        val a = activity(ActivityMode.INCREASING, startingValue = 50.0, target = 100.0)
        assertEquals(0.5f, ProgressTracking.progressFraction(a, listOf(entry(start.plusDays(3), 75.0)))!!)
    }

    @Test fun `decreasing fraction measures the countdown`() {
        val a = activity(ActivityMode.DECREASING, startingValue = 85.0, target = 78.0)
        // 85 -> 82.5 with goal 78: 2.5 of the 7.0 gap covered.
        assertEquals((2.5 / 7.0).toFloat(), ProgressTracking.progressFraction(a, listOf(entry(start.plusDays(3), 82.5)))!!, 0.0001f)
    }

    @Test fun `personal best fraction compares the record to the goal`() {
        val a = activity(ActivityMode.PERSONAL_BEST, target = 20.0)
        assertEquals(0.9f, ProgressTracking.progressFraction(a, listOf(entry(start, 18.0)))!!)
    }

    @Test fun `ill-defined fractions are suppressed not guessed`() {
        assertNull(ProgressTracking.progressFraction(activity(ActivityMode.INCREASING, startingValue = 100.0, target = 50.0), listOf(entry(start, 75.0))))
        assertNull(ProgressTracking.progressFraction(activity(ActivityMode.DECREASING, startingValue = 80.0, target = 85.0), listOf(entry(start, 79.0))))
        assertNull(ProgressTracking.progressFraction(activity(ActivityMode.INCREASING), emptyList()))
        assertNull(ProgressTracking.progressFraction(activity(ActivityMode.LOGGED, target = 20.0), listOf(entry(start, 10.0))))
    }

    @Test fun `unknown stored modes fall back to logged`() {
        assertEquals(ActivityMode.LOGGED, ProgressTracking.mode(activity(ActivityMode.LOGGED).copy(trackingMode = "SIDEWAYS")))
    }
}
