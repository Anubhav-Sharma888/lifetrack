package com.lifetrack.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.fragment.app.FragmentActivity
import com.lifetrack.app.ui.LifeTrackApp
import dagger.hilt.android.AndroidEntryPoint

/** A FragmentActivity so the opt-in app lock can present BiometricPrompt. */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val settingsRequest = mutableStateOf(0)
    private val habitRequest = mutableStateOf<Long?>(null)
    private val sessionRequest = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (intent.getBooleanExtra("open_settings", false)) settingsRequest.value++
        habitRequest.value = intent.getLongExtra("open_habit", -1).takeIf { it > 0 }
        sessionRequest.value = intent.getStringExtra("open_session")
        setContent { LifeTrackApp(settingsRequest.value, habitRequest = habitRequest.value, sessionRequest = sessionRequest.value) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_settings", false)) settingsRequest.value++
        habitRequest.value = intent.getLongExtra("open_habit", -1).takeIf { it > 0 }
        sessionRequest.value = intent.getStringExtra("open_session")
    }
}
