package com.lifetrack.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.HabitPause
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.ui.components.CheckInButton
import com.lifetrack.app.ui.components.CompletionTrend
import com.lifetrack.app.ui.components.HabitCard
import com.lifetrack.app.ui.components.LocalUiPreferences
import com.lifetrack.app.ui.components.UiPreferences
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class UpgradedComponentsTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 26)

    @Test fun `numeric habit exposes exact partial progress and increment action`() {
        var increments = 0
        val habit = Habit(id = 1, name = "Drink water", startDate = today, targetCount = 8, targetUnit = "glasses")
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(320.dp)) {
                    HabitCard(habit, "Day 1", LogStatus.IN_PROGRESS, null, {}, { increments++ }, {}, progress = 3)
                }
            }
        }
        compose.onNodeWithText("3 / 8 glasses", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Drink water", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "In progress, 37 percent"))
            .assert(SemanticsMatcher("Offers one-unit progress action") {
                it.config[SemanticsActions.OnClick].label == "Add 1 glasses"
            }).performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, increments) }
    }

    @Test fun `disabled haptics preserves tap and long press behavior without feedback`() {
        var feedback = 0
        var taps = 0
        var holds = 0
        val fakeHaptic = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { feedback++ }
        }
        compose.setContent {
            CompositionLocalProvider(LocalUiPreferences provides UiPreferences(false, false), LocalHapticFeedback provides fakeHaptic) {
                MaterialTheme {
                    CheckInButton(null, true, { taps++ }, { holds++ }, modifier = Modifier.testTag("feedback-free"))
                }
            }
        }
        compose.onNodeWithTag("feedback-free").performTouchInput { click() }
        compose.onNodeWithTag("feedback-free").performTouchInput { longClick() }
        compose.runOnIdle { assertEquals(1, taps); assertEquals(1, holds); assertEquals(0, feedback) }
    }

    @Test fun `trend provides keyboard and screen-reader accessible exact date inspection`() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(2))
        val logs = listOf(HabitLog(id = 1, habitId = 1, date = today.minusDays(2), status = LogStatus.COMPLETED))
        compose.setContent {
            MaterialTheme { CompletionTrend(habit, logs, today) }
        }
        compose.onNodeWithContentDescription("Inspect completion trend day")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        compose.onNodeWithText("2026-10-24 · Day 1 · 100% · 1/1 completed over 7 days").assertIsDisplayed()
    }

    @Test fun `analytics excludes archive intervals from todays opportunity count`() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(3))
        val pause = HabitPause(id = 1, habitId = 1, startDate = today, endDate = today.plusDays(1))
        compose.setContent {
            MaterialTheme {
                Surface { Box(Modifier.width(320.dp).height(600.dp)) { AnalyticsScreen(listOf(habit), emptyList(), today, listOf(pause)) } }
            }
        }
        compose.onNodeWithContentDescription("Today: no unskipped habits due").performScrollTo().assertIsDisplayed()
    }

    @Test fun `large font analytics retains readable rings and category legend`() {
        val habit = Habit(id = 1, name = "A long but meaningful ritual", category = "Learning and reflection", startDate = today)
        val logs = listOf(HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.COMPLETED))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    Surface { Box(Modifier.width(320.dp).height(650.dp)) { AnalyticsScreen(listOf(habit), logs, today) } }
                }
            }
        }
        compose.onNodeWithContentDescription("Today: 100 percent, 1 of 1 completed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Learning and reflection").performScrollTo().assertIsDisplayed()
    }
}
