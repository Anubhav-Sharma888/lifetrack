package com.lifetrack.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.ui.components.CheckInButton
import com.lifetrack.app.ui.components.HabitCard
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Exercises real Compose semantics and touch dispatch on the JVM, without a device or Hilt graph. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class HabitComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun checkInTapInvokesCompletionExactlyOnce() {
        var completed = 0
        var skipped = 0
        compose.setContent {
            MaterialTheme {
                CheckInButton(null, true, { completed++ }, { skipped++ },
                    modifier = Modifier.testTag("check-in"), label = "Read")
            }
        }

        compose.onNodeWithTag("check-in").assertIsEnabled().assertHasClickAction().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, completed)
            assertEquals(0, skipped)
        }
    }

    @Test fun holdingCheckInOpensSkipInsteadOfCompleting() {
        var completed = 0
        var skipped = 0
        compose.setContent {
            MaterialTheme {
                CheckInButton(null, true, { completed++ }, { skipped++ },
                    modifier = Modifier.testTag("check-in"), label = "Meditate")
            }
        }

        compose.onNodeWithTag("check-in").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(0, completed)
            assertEquals(1, skipped)
        }
    }

    @Test fun disabledFutureCheckInIgnoresTouch() {
        var changes = 0
        compose.setContent {
            MaterialTheme {
                CheckInButton(null, false, { changes++ }, { changes++ },
                    modifier = Modifier.testTag("future-check-in"), label = "Future habit")
            }
        }

        compose.onNodeWithTag("future-check-in").assertIsNotEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun nestedCheckInDoesNotOpenHabitAndCardStillOpensDetail() {
        var opened = 0
        var completed = 0
        val habit = Habit(id = 1, name = "Read a chapter", category = "Mind",
            startDate = LocalDate.of(2026, 10, 26))
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp)) {
                        HabitCard(habit, "Day 1", null, null,
                            onOpen = { opened++ }, onCheckIn = { completed++ }, onLongCheckIn = {},
                            modifier = Modifier.testTag("habit-card"),
                            dragHandle = { IconButton(onClick = {}) { Text("↕") } })
                    }
                }
            }
        }

        compose.onNode(hasContentDescription(habit.name) and hasAnyAncestor(hasTestTag("habit-card")),
            useUnmergedTree = true).assertIsDisplayed().performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(1, completed)
            assertEquals(0, opened)
        }
        compose.onNodeWithTag("habit-card").performClick()
        compose.runOnIdle {
            assertEquals(1, completed)
            assertEquals(1, opened)
        }
        compose.onNodeWithText("Day 1 · Mind", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun completedCheckInExposesUndoActionAndNamedState() {
        compose.setContent {
            MaterialTheme {
                CheckInButton(LogStatus.COMPLETED, true, {}, {},
                    modifier = Modifier.testTag("completed-check-in"), label = "Drink water")
            }
        }

        compose.onNodeWithTag("completed-check-in")
            .assert(hasContentDescription("Drink water"))
            .assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Completed"))
            .assertHasClickAction()
            .assert(SemanticsMatcher("Offers undo for a completion") {
                it.config[androidx.compose.ui.semantics.SemanticsActions.OnClick].label == "Undo completion"
            })
    }
}
