package com.lifetrack.app.data

import android.app.Application
import android.net.Uri
import androidx.room.Room
import java.io.File
import java.time.Duration
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock

/**
 * Daily reflections: save guards, timestamp bookkeeping, clearing, flow emission, reset/import
 * replacement and the version-7 export gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DailyReflectionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: HabitRepository
    private val files = mutableListOf<File>()
    private val today: LocalDate get() = LocalDate.now()
    private val yesterday: LocalDate get() = today.minusDays(1)

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HabitRepository(database, context)
    }

    @After
    fun tearDown() {
        database.close()
        files.forEach { it.delete() }
    }

    // ---- Saving: mood, journal and both ----

    @Test
    fun `a mood only save records the mood without a journal`() = runBlocking {
        repository.setReflection(yesterday, 3, null)
        val saved = repository.reflections.first().single()
        assertEquals(yesterday, saved.date)
        assertEquals(3, saved.mood)
        assertNull(saved.journal)
        assertTrue(saved.updatedAt >= saved.createdAt)
    }

    @Test
    fun `a journal only save trims surrounding whitespace`() = runBlocking {
        repository.setReflection(yesterday, null, "  a quiet day  ")
        val saved = repository.reflections.first().single()
        assertNull(saved.mood)
        assertEquals("a quiet day", saved.journal)
    }

    @Test
    fun `a whitespace only journal with a mood normalizes to a mood only row`() = runBlocking {
        repository.setReflection(yesterday, 4, "   ")
        val saved = repository.reflections.first().single()
        assertEquals(4, saved.mood)
        assertNull(saved.journal)
    }

    @Test
    fun `mood and journal can be saved together`() = runBlocking {
        repository.setReflection(yesterday, 2, "steady but tired")
        val saved = repository.reflections.first().single()
        assertEquals(2, saved.mood)
        assertEquals("steady but tired", saved.journal)
    }

    // ---- Saving: rejections ----

    @Test
    fun `saving neither a mood nor a journal is rejected`() = runBlocking {
        expectRejected { repository.setReflection(yesterday, null, null) }
        expectRejected { repository.setReflection(yesterday, null, "   ") } // blank text normalizes to nothing
        assertTrue(repository.reflections.first().isEmpty())
    }

    @Test
    fun `out of range moods are rejected and never clamped`() = runBlocking {
        expectRejected { repository.setReflection(yesterday, 0, null) }
        expectRejected { repository.setReflection(yesterday, 6, null) }
        expectRejected { repository.setReflection(yesterday, -1, null) }
        expectRejected { repository.setReflection(yesterday, Int.MAX_VALUE, null) }
        assertTrue(repository.reflections.first().isEmpty())
        // A rejected edit leaves the stored row untouched rather than clamping the mood.
        repository.setReflection(yesterday, 3, null)
        expectRejected { repository.setReflection(yesterday, 6, null) }
        assertEquals(3, repository.reflections.first().single().mood)
    }

    @Test
    fun `journals are length checked after trimming at exactly 2000 characters`() = runBlocking {
        expectRejected { repository.setReflection(yesterday, null, "x".repeat(2_001)) }
        assertTrue(repository.reflections.first().isEmpty())
        repository.setReflection(yesterday, null, "x".repeat(2_000))
        assertEquals("x".repeat(2_000), repository.reflections.first().single().journal)
        // Trimming happens before the limit applies: 2,002 raw characters store as exactly 2,000.
        repository.setReflection(yesterday, 1, " ${"x".repeat(2_000)} ")
        val padded = repository.reflections.first().single()
        assertEquals(2_000, padded.journal!!.length)
        assertEquals(1, padded.mood)
        expectRejected { repository.setReflection(yesterday, null, " ${"x".repeat(2_001)} ") }
    }

    @Test
    fun `reflections accept today and 1900 but reject later or earlier dates`() = runBlocking {
        expectRejected { repository.setReflection(today.plusDays(1), 3, null) }
        expectRejected { repository.setReflection(LocalDate.of(1899, 12, 31), 3, null) }
        assertTrue(repository.reflections.first().isEmpty())
        repository.setReflection(LocalDate.of(1900, 1, 1), 3, null)
        repository.setReflection(today, 4, "written today")
        assertEquals(listOf(LocalDate.of(1900, 1, 1), today), repository.reflections.first().map { it.date })
    }

    // ---- Timestamp bookkeeping on the same date ----

    @Test
    fun `overwriting the same date preserves creation and bumps the edit timestamp`() = runBlocking {
        repository.setReflection(yesterday, 2, "first draft")
        val original = repository.reflections.first().single()
        repository.setReflection(yesterday, null, "rewritten")
        val edited = repository.reflections.first().single()
        assertEquals(original.createdAt, edited.createdAt)
        assertTrue(edited.updatedAt > original.updatedAt)
        assertTrue(edited.updatedAt >= edited.createdAt)
        assertNull(edited.mood)
        assertEquals("rewritten", edited.journal)
    }

    @Test
    fun `rapid re-saves keep the edit timestamp strictly increasing`() = runBlocking {
        var previous = -1L
        repeat(5) { attempt ->
            repository.setReflection(yesterday, (attempt % 5) + 1, null)
            val saved = repository.reflections.first().single()
            assertTrue(saved.updatedAt >= saved.createdAt)
            if (attempt > 0) assertTrue(saved.updatedAt > previous)
            previous = saved.updatedAt
        }
    }

    @Test
    fun `re-creating a cleared day starts a fresh creation timestamp`() = runBlocking {
        repository.setReflection(yesterday, 3, "note")
        val original = repository.reflections.first().single()
        repository.clearReflection(yesterday)
        // Robolectric's clock stands still until advanced, so move it forward deterministically.
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        repository.setReflection(yesterday, 3, "note")
        val recreated = repository.reflections.first().single()
        assertTrue(recreated.createdAt > original.createdAt)
        assertTrue(recreated.updatedAt >= recreated.createdAt)
    }

    // ---- Clearing ----

    @Test
    fun `clearing deletes the row and stays idempotent for absent rows`() = runBlocking {
        repository.setReflection(yesterday, 3, null)
        repository.clearReflection(yesterday)
        assertTrue(repository.reflections.first().isEmpty())
        repository.clearReflection(yesterday) // already gone
        repository.clearReflection(today.minusDays(3)) // never existed
        assertTrue(repository.reflections.first().isEmpty())
    }

    @Test
    fun `clearing honors the same date bounds as saving`() = runBlocking {
        expectRejected { repository.clearReflection(today.plusDays(1)) }
        expectRejected { repository.clearReflection(LocalDate.of(1899, 12, 31)) }
    }

    // ---- Flow ----

    @Test
    fun `the reflections flow tracks a save and then a clear`() = runBlocking {
        assertTrue(repository.reflections.first().isEmpty())
        repository.setReflection(yesterday, 4, "a good day")
        val emitted = repository.reflections.first()
        assertEquals(listOf(yesterday), emitted.map { it.date })
        assertEquals(4, emitted.single().mood)
        assertEquals("a good day", emitted.single().journal)
        repository.clearReflection(yesterday)
        assertTrue(repository.reflections.first().isEmpty())
    }

    // ---- Reset and import replacement ----

    @Test
    fun `resetting all data clears reflections but preserves the app lock`() = runBlocking {
        repository.setReflection(yesterday, 2, "gone soon")
        repository.setReflection(today, 5, null)
        repository.updateSettings(AppSettings(appLockEnabled = true))
        repository.resetAllData()
        assertTrue(repository.reflections.first().isEmpty())
        assertTrue(repository.settings.first().appLockEnabled)
        assertEquals("SYSTEM", repository.settings.first().theme) // every other setting returns to defaults
    }

    @Test
    fun `importing replaces existing reflections instead of merging them`() = runBlocking {
        val kept = yesterday
        repository.setReflection(kept, 3, "first day")
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        val expected = repository.reflections.first().single()

        // Mutate the database after exporting: rewrite the kept day and add a second one.
        repository.setReflection(kept, null, "rewritten")
        repository.setReflection(today, 5, null)
        assertEquals(2, repository.reflections.first().size)

        repository.importFrom(Uri.fromFile(output))
        assertEquals(listOf(expected), repository.reflections.first())
    }

    // ---- Export version selection ----

    @Test
    fun `a single reflection forces version seven ahead of smart streaks`() = runBlocking {
        val habit = repository.saveHabit(Habit(name = "Read", startDate = today.minusDays(35), targetCount = 4))
        repository.setProgress(habit, yesterday, 3)
        repository.setStreakProtection(habit, yesterday, true) // alone this exports as version 6

        val plain = backupFile()
        repository.exportTo(Uri.fromFile(plain))
        val plainText = plain.readText()
        assertEquals(6, BackupValidator.decode(plainText, today).formatVersion)
        assertFalse(plainText.contains("dailyReflections")) // key-free export for older apps

        repository.setReflection(today, 4, "a good day")
        val withReflections = backupFile()
        repository.exportTo(Uri.fromFile(withReflections))
        val text = withReflections.readText()
        assertEquals(7, BackupValidator.decode(text, today).formatVersion)
        assertTrue(text.contains("\"dailyReflections\""))
    }

    // ---- Helpers ----

    private fun backupFile(content: String = ""): File = File.createTempFile(
        "lifetrack-reflections-test-", ".json", RuntimeEnvironment.getApplication().cacheDir,
    ).also {
        files += it
        it.writeText(content)
    }

    private suspend fun expectRejected(action: suspend () -> Unit) {
        try {
            action()
            fail("Expected the repository to reject this operation")
        } catch (expected: IllegalArgumentException) {
            assertFalse(expected.message.isNullOrBlank())
        }
    }

    @Test
    fun `importing an older reflections free backup clears local reflections`() = runBlocking {
        // Export BEFORE any reflection exists: the backup carries no dailyReflections key at all.
        val output = backupFile()
        repository.exportTo(Uri.fromFile(output))
        repository.setReflection(yesterday, 3, "will not survive")
        repository.setReflection(today, null, "also gone")
        assertEquals(2, repository.reflections.first().size)
        repository.importFrom(Uri.fromFile(output))
        assertTrue(repository.reflections.first().isEmpty())
    }
}
