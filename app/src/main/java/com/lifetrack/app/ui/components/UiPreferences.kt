package com.lifetrack.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

/** Optional feedback never changes check-in behavior; users can reduce motion and haptics. */
data class UiPreferences(val hapticsEnabled: Boolean = true, val animationsEnabled: Boolean = true)

val LocalUiPreferences = staticCompositionLocalOf { UiPreferences() }
