package com.lifetrack.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.HabitLog
import com.lifetrack.app.data.LogStatus
import com.lifetrack.app.data.ScheduleMode
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28], qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
class AnalyticsScreenTest {
    @get:Rule val compose = createComposeRule()
    private val today = LocalDate.of(2026, 10, 26)

    @Test fun emptyNarrowScreenShowsUsefulStatesAndCanReachEverySection() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp).height(600.dp)) {
                        AnalyticsScreen(emptyList(), emptyList(), today)
                    }
                }
            }
        }

        compose.onNodeWithText("Analytics").assertIsDisplayed()
        compose.onNodeWithText("No habits scheduled").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All-time rate").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Today: no unskipped habits due").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("You vs. you").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your first check-in starts the picture.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your next check-in can start a new streak.").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("0%").assertCountEquals(0)
    }

    @Test fun futureHabitDoesNotShowAsMissedInTodayRing() {
        val planned = Habit(id = 1, name = "Learn piano", startDate = today.plusDays(7))
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp).height(600.dp)) {
                        AnalyticsScreen(listOf(planned), emptyList(), today)
                    }
                }
            }
        }

        compose.onNodeWithContentDescription("Today: no unskipped habits due").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("This week: no unskipped habits due").assertExists()
        compose.onAllNodesWithText("0%").assertCountEquals(0)
    }

    @Test fun quotaHabitsStayOutOfDailyRatesAndRenderWithoutCrash() {
        val quota = Habit(id = 1, name = "Gym", scheduleMode = ScheduleMode.WEEKLY_QUOTA.name,
            weeklyTarget = 3, startDate = today.minusDays(10))
        val logs = listOf(HabitLog(id = 1, habitId = 1, date = today, status = LogStatus.COMPLETED))
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.width(320.dp).height(600.dp)) { AnalyticsScreen(listOf(quota), logs, today) }
                }
            }
        }

        compose.onNodeWithText("Analytics").assertIsDisplayed()
        // The quota completion never enters a daily ring: nothing is due on any fixed day.
        compose.onNodeWithContentDescription("Today: no unskipped habits due").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("This week: no unskipped habits due").assertExists()
        compose.onAllNodesWithText("0%").assertCountEquals(0)
    }
}
