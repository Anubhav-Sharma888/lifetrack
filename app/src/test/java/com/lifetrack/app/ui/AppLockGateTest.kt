package com.lifetrack.app.ui

import android.app.Application
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import com.lifetrack.app.MainActivity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The gate must hide every byte of content while locked; no device prompt exists on the JVM. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class AppLockGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun lockedGateHidesAllContentAndOffersUnlock() {
        compose.setContent {
            MaterialTheme {
                AppLockGate(enabled = true) { Text("Secret ledger", Modifier.testTag("secret")) }
            }
        }
        compose.onNodeWithTag("secret").assertDoesNotExist()
        compose.onNodeWithText("LifeTrack is locked").assertExists()
        compose.onNodeWithText("Unlock").assertExists()
    }

    @Test fun disabledGateShowsContentImmediately() {
        compose.setContent {
            MaterialTheme {
                AppLockGate(enabled = false) { Text("Secret ledger", Modifier.testTag("secret")) }
            }
        }
        compose.onNodeWithTag("secret").assertExists()
        compose.onNodeWithText("LifeTrack is locked").assertDoesNotExist()
    }

    @Test fun enabledGateBlocksScreenshotsAndRecentsPreview() {
        compose.setContent {
            MaterialTheme { AppLockGate(enabled = true) { Text("Secret ledger") } }
        }
        compose.runOnIdle {
            assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test fun disabledGateLeavesScreenshotsAllowed() {
        compose.setContent {
            MaterialTheme { AppLockGate(enabled = false) { Text("Secret ledger") } }
        }
        compose.runOnIdle {
            assertFalse(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        }
    }

    @Test fun backgroundingRelocksUnlessABiometricPromptIsInFlight() {
        assertTrue(shouldRelock(Lifecycle.Event.ON_STOP, promptInFlight = false))
        assertFalse(shouldRelock(Lifecycle.Event.ON_STOP, promptInFlight = true))
        assertFalse(shouldRelock(Lifecycle.Event.ON_RESUME, promptInFlight = false))
        assertFalse(shouldRelock(Lifecycle.Event.ON_CREATE, promptInFlight = false))
    }

    @Test fun withoutEnrolledAuthenticatorsAppLockCannotBeOffered() {
        // Robolectric's default keyguard has no screen lock or fingerprints enrolled.
        assertFalse(appLockAvailable(compose.activity))
    }

    @Test fun mainActivityRemainsABiometricCapableFragmentActivity() {
        // authenticate() silently no-ops without a FragmentActivity host, so pin the superclass.
        assertTrue(FragmentActivity::class.java.isAssignableFrom(MainActivity::class.java))
    }

    @Test fun routeBackedDraftsSurviveGateUnmountAndRemount() {
        // Mirrors the app structure after the fix: LifeTrackApp hoists a SaveableStateHolder above the
        // gate and wraps its content in SaveableStateProvider, so the gate's unmount hands route state
        // back on remount instead of dropping it.
        compose.setContent {
            MaterialTheme {
                val holder = rememberSaveableStateHolder()
                var gated by remember { mutableStateOf(false) }
                Column {
                    TextButton(onClick = { gated = !gated }, Modifier.testTag("toggle")) { Text("toggle") }
                    if (!gated) holder.SaveableStateProvider("edit/3") {
                        var draft by rememberSaveable { mutableStateOf("") }
                        OutlinedTextField(draft, { draft = it }, Modifier.testTag("draft"))
                    }
                }
            }
        }
        compose.onNodeWithTag("draft").performTextInput("my typed draft")
        compose.onNodeWithTag("toggle").performClick() // lock engages: gate unmounts the content
        compose.onNodeWithTag("draft").assertDoesNotExist()
        compose.onNodeWithTag("toggle").performClick() // unlock: gate recomposes the content
        compose.onNodeWithText("my typed draft").assertExists()
    }
}
