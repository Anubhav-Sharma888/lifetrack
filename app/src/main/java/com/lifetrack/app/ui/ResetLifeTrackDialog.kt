package com.lifetrack.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun ResetLifeTrackDialog(
    confirmation: String,
    busy: Boolean,
    error: String?,
    onConfirmationChange: (String) -> Unit,
    onExport: (Boolean) -> Unit,
    onErase: () -> Unit,
    onCancel: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val confirmed = confirmation == "RESET"
    val actionSize = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(
        dismissOnBackPress = !busy, dismissOnClickOutside = false, usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
    )) {
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            Column(
                Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ResetPanel {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        SettingsIcon(Icons.Outlined.WarningAmber, destructive = true)
                        Column {
                            Text("Irreversible action", style = AlmanacType.labelCaps, color = colors.error)
                            Text("Reset LifeTrack?", style = MaterialTheme.typography.headlineLarge)
                        }
                    }
                    Surface(shape = RoundedCornerShape(8.dp), color = colors.surface) {
                        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("This permanently erases active and archived habits, check-ins and notes, dated goals, workouts and logged activities, reminders, and app preferences on this device.")
                            Text("Previously exported backups remain available.", color = colors.onSurfaceVariant)
                        }
                    }
                }
                ResetPanel {
                    Text("Back up your data", style = AlmanacType.labelCaps, color = colors.onSurfaceVariant)
                    Text("Save a plain JSON or password-protected backup before erasing your data.")
                    OutlinedButton(onClick = { onExport(false) }, enabled = !busy, modifier = actionSize) { Text("Export a backup first") }
                    OutlinedButton(onClick = { onExport(true) }, enabled = !busy, modifier = actionSize) { Text("Export an encrypted backup") }
                }
                ResetPanel {
                    Text("Confirm the reset", style = AlmanacType.labelCaps, color = colors.onSurfaceVariant)
                    OutlinedTextField(
                        value = confirmation, onValueChange = onConfirmationChange, enabled = !busy,
                        label = { Text("Type RESET to confirm") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = AlmanacType.monoMetric, colors = almanacFieldColors(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false)
                    )
                    if (confirmed) Text("Confirmation matches.", style = MaterialTheme.typography.bodySmall, color = colors.secondary,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                error?.let {
                    Text(it, color = colors.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Button(onClick = onErase, enabled = confirmed && !busy, modifier = actionSize,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError)) {
                    Icon(Icons.Outlined.DeleteForever, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Erase all local data")
                }
                FilledTonalButton(onClick = onCancel, enabled = !busy, modifier = actionSize) { Text("Cancel") }
            }
        }
    }
}

@Composable
private fun ResetPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}
