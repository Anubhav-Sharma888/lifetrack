package com.lifetrack.app.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lifetrack.app.data.DailyReflection
import com.lifetrack.app.ui.components.ReflectionEditorCard
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The reflection card: save gating, empty-day invitation, and the confirm-before-remove flow. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ReflectionUiTest {
    private val date = LocalDate.of(2026, 11, 17)
    private var savedMood: Int? = null
    private var savedJournal: String? = null
    private var removed = 0

    @get:Rule val compose = createComposeRule()

    private fun setContent(reflection: DailyReflection?) {
        compose.setContent {
            MaterialTheme {
                ReflectionEditorCard(
                    date = date, today = date,
                    reflection = reflection,
                    onSave = { mood, journal -> savedMood = mood; savedJournal = journal },
                    onRemove = { removed++ },
                    enabled = true
                )
            }
        }
    }

    @Test fun anEmptyDayInvitesEntryAndSavingAMoodOnlyReflectionFiresOnce() {
        setContent(null)
        compose.onNodeWithText("How was the day?").assertExists()
        compose.onNodeWithText("Add a reflection").performClick()
        compose.onNodeWithContentDescription("Mood 4 of 5: Good").performClick()
        compose.onNodeWithText("Save reflection").performClick()
        assertEquals(0, removed) // guard: remove must not have fired
        assertEquals(4, savedMood)
        assertNull(savedJournal)
    }

    @Test fun cancelOnAnEmptyDayReturnsToTheInvitationWithoutSaving() {
        setContent(null)
        compose.onNodeWithText("Add a reflection").performClick()
        compose.onNodeWithContentDescription("Mood 5 of 5: Great").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Add a reflection").assertIsDisplayed()
        assertNull(savedMood)
        assertNull(savedJournal)
    }

    @Test fun aSavedReflectionReadsBackAndRemovingItRequiresConfirmation() {
        setContent(DailyReflection(date = date, mood = 3, journal = "a quiet day", createdAt = 100, updatedAt = 150))
        compose.onNodeWithText("Neutral", substring = true).assertExists()
        compose.onNodeWithText("a quiet day").assertExists()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("This cannot be undone.", substring = true).assertExists()
        assertEquals(0, removed) // no removal before confirmation
        compose.onNodeWithText("Remove reflection").performClick()
        assertEquals(1, removed)
    }

    @Test fun editingShowsTheSavedValuesAndCancelRestoresThem() {
        setContent(DailyReflection(date = date, mood = 2, journal = "kept words", createdAt = 100, updatedAt = 150))
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("kept words").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("😕 Low").assertIsDisplayed()
        assertNull(savedMood) // cancel never saves
    }
}
