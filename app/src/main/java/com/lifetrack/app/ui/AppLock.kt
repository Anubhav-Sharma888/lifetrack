package com.lifetrack.app.ui

import android.app.Activity
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Enrolling any screen lock or fingerprint is required before app lock can be turned on. */
internal fun appLockAvailable(context: android.content.Context): Boolean =
    BiometricManager.from(context)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL) ==
        BiometricManager.BIOMETRIC_SUCCESS

/** A real backgrounding re-locks; the biometric sheet itself must not (it briefly stops the host). */
internal fun shouldRelock(event: Lifecycle.Event, promptInFlight: Boolean): Boolean =
    event == Lifecycle.Event.ON_STOP && !promptInFlight

/**
 * Opt-in gate around the whole app. While enabled, every cold start and every return from
 * backgrounding requires the device's enrolled screen lock or fingerprint before any data renders.
 *
 * Safety rules:
 * - The setting can only be enabled when an authenticator is enrolled, and it is stripped from
 *   backups; restoring never silently weakens it because the local flag survives every import.
 * - The biometric sheet itself must not re-lock the app: while a prompt is in flight, ON_STOP is ignored.
 *   A success arriving after the host already stopped keeps the lock, so returning re-prompts.
 * - If Android later reports no screen lock (for example it was removed in system settings), the gate
 *   explains the fix instead of leaving an impossible unlock; the device-credential path stays allowed.
 * - FLAG_SECURE keeps the locked state and private content out of screenshots and the recents preview.
 */
@Composable
fun AppLockGate(enabled: Boolean, content: @Composable () -> Unit) {
    val window = (LocalActivity.current as? Activity)?.window
    DisposableEffect(enabled) {
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    if (!enabled) { content(); return }

    // Plain remember: configuration changes and process death always return to the locked state.
    var locked by remember { mutableStateOf(true) }
    var promptInFlight by remember { mutableStateOf(false) }
    var failureMessage by remember { mutableStateOf<String?>(null) }
    // Every re-lock bumps this so the sheet re-fires after system cancellations and stop-during-prompt races.
    var lockGeneration by remember { mutableIntStateOf(0) }
    val activity = LocalActivity.current as? FragmentActivity
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when {
                shouldRelock(event, promptInFlight) -> { locked = true; lockGeneration++ }
                // Returning from the background always re-offers the sheet once the host is live again.
                event == Lifecycle.Event.ON_RESUME && locked && !promptInFlight -> lockGeneration++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    fun authenticate() {
        if (promptInFlight) return
        val fragmentActivity = activity ?: return
        promptInFlight = true
        try {
            val prompt = BiometricPrompt(fragmentActivity, ContextCompat.getMainExecutor(fragmentActivity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        promptInFlight = false
                        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            failureMessage = null
                            locked = false
                        } else {
                            // The host stopped mid-prompt; stay locked. The sheet re-fires on return,
                            // but only when the host is actually alive again — never while backgrounded.
                            locked = true
                            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) lockGeneration++
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        promptInFlight = false
                        when (errorCode) {
                            BiometricPrompt.ERROR_NEGATIVE_BUTTON, BiometricPrompt.ERROR_USER_CANCELED -> Unit
                            BiometricPrompt.ERROR_CANCELED -> {
                                // The system dismissed the sheet (typical on backgrounding); re-prompt on return.
                                // While the host is backgrounded, the ON_RESUME observer does the re-offering instead.
                                failureMessage = null
                                if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) lockGeneration++
                            }
                            BiometricPrompt.ERROR_HW_NOT_PRESENT, BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL ->
                                failureMessage = "Android reports no screen lock or fingerprint. Set one in Android settings, then unlock LifeTrack."
                            BiometricPrompt.ERROR_HW_UNAVAILABLE ->
                                failureMessage = "Authentication is temporarily unavailable. Unlock in a moment, or restart LifeTrack."
                            else -> failureMessage = errString.toString()
                        }
                    }
                })
            prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock LifeTrack")
                .setSubtitle("Your habits, notes and history are protected by app lock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .setConfirmationRequired(false)
                .build())
        } catch (failure: Exception) {
            // A synchronous failure must never wedge promptInFlight and disable re-locking.
            promptInFlight = false
            failureMessage = "Authentication could not start. Try again, or restart LifeTrack."
        }
    }
    // The sheet appears as soon as the lock engages, so unlocking is one glance or one tap.
    LaunchedEffect(lockGeneration) { if (locked && !promptInFlight) authenticate() }

    if (!locked) { content(); return }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(Icons.Outlined.Lock, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text("LifeTrack is locked", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics { contentDescription = "LifeTrack is locked" })
            Text("Your habits, notes and history stay private.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            failureMessage?.let { message ->
                Spacer(Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            Spacer(Modifier.height(24.dp))
            if (promptInFlight) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
            }
            Button(onClick = ::authenticate, enabled = !promptInFlight, modifier = Modifier.heightIn(min = 48.dp)) { Text("Unlock") }
        }
    }
}
