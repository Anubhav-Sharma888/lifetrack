package com.lifetrack.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lifetrack.app.data.Habit
import com.lifetrack.app.data.LogStatus

fun habitColor(hex: String): Color = runCatching { Color(android.graphics.Color.parseColor(hex)) }
    .getOrDefault(Color(0xFF477A60))

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CheckInButton(
    status: LogStatus?,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    label: String = "Habit",
    actionLabel: String? = null,
    progressFraction: Float? = null
) {
    val haptic = LocalHapticFeedback.current
    val preferences = LocalUiPreferences.current
    val target = when (status) {
        LogStatus.COMPLETED -> accent
        LogStatus.SKIPPED -> MaterialTheme.colorScheme.secondaryContainer
        LogStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
        LogStatus.IN_PROGRESS -> accent.copy(alpha = .12f)
        null -> MaterialTheme.colorScheme.surface
    }
    val background by animateColorAsState(target, animationSpec = if (preferences.animationsEnabled) tween(220) else snap(), label = "checkFill")
    val scale by animateFloatAsState(if (preferences.animationsEnabled && status == LogStatus.COMPLETED) 1.05f else 1f,
        animationSpec = if (preferences.animationsEnabled) tween(180) else snap(), label = "checkScale")
    val foreground = if (!enabled) MaterialTheme.colorScheme.onSurfaceVariant else when (status) {
        LogStatus.COMPLETED -> {
            val ink = Color(0xFF1B1710)
            val paper = Color(0xFFFCF9F0)
            val luminance = accent.luminance() + .05f
            val inkContrast = luminance / (ink.luminance() + .05f)
            val paperContrast = (paper.luminance() + .05f) / luminance
            if (inkContrast >= paperContrast) ink else paper
        }
        LogStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
        LogStatus.SKIPPED -> MaterialTheme.colorScheme.onSecondaryContainer
        LogStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSurface
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier.size(58.dp).scale(scale).clip(CircleShape)
            .background(if (enabled) background else MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, if (status == null) MaterialTheme.colorScheme.outlineVariant else Color.Transparent, CircleShape)
            .semantics {
                contentDescription = label
                stateDescription = when (status) {
                LogStatus.COMPLETED -> "Completed"
                LogStatus.SKIPPED -> "Skipped, streak protected"
                LogStatus.FAILED -> "Missed"
                LogStatus.IN_PROGRESS -> "In progress" + (progressFraction?.let { ", ${(it.coerceIn(0f, 1f) * 100).toInt()} percent" } ?: "")
                null -> if (enabled) "Not checked in" else "Check-in unavailable"
            } }
            .combinedClickable(
                interactionSource = null, indication = LocalIndication.current,
                enabled = enabled, role = Role.Button,
                hapticFeedbackEnabled = false,
                onClickLabel = actionLabel ?: if (status == LogStatus.COMPLETED) "Undo completion" else "Complete habit",
                onLongClickLabel = "Skip with an optional note",
                onClick = { if (preferences.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() },
                onLongClick = { if (preferences.hapticsEnabled) haptic.performHapticFeedback(HapticFeedbackType.LongPress); onLongClick() }
            ), contentAlignment = Alignment.Center
    ) {
        if (status == LogStatus.IN_PROGRESS && progressFraction != null) {
            CircularProgressIndicator(progress = { progressFraction.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxSize().padding(3.dp),
                color = accent, strokeWidth = 2.dp, trackColor = accent.copy(alpha = .15f))
        }
        when (status) {
            LogStatus.COMPLETED -> Icon(Icons.Rounded.Check, "Completed", tint = foreground, modifier = Modifier.size(28.dp))
            LogStatus.SKIPPED -> Icon(Icons.Rounded.Remove, "Skipped", tint = foreground)
            LogStatus.FAILED -> Icon(Icons.Rounded.Close, "Missed", tint = foreground)
            LogStatus.IN_PROGRESS -> Text("+", color = foreground, fontSize = 28.sp, fontWeight = FontWeight.Light)
            null -> Text("+", color = foreground, fontSize = 30.sp, fontWeight = FontWeight.Light)
        }
    }
}

@Composable
fun HabitCard(
    habit: Habit,
    dayLabel: String,
    status: LogStatus?,
    stackLabel: String?,
    onOpen: () -> Unit,
    onCheckIn: () -> Unit,
    onLongCheckIn: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
    quickActions: (@Composable () -> Unit)? = null,
    checkInEnabled: Boolean = true,
    progress: Int = 0,
    nudged: Boolean = false
) {
    val accent = habitColor(habit.colorHex)
    OutlinedCard(onClick = onOpen, modifier = modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(if (nudged) 2.dp else 1.dp,
            if (nudged) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = .65f))) {
        BoxWithConstraints {
            val compact = maxWidth < 360.dp
            Row(Modifier.padding(if (compact) 12.dp else 16.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                if (dragHandle != null) dragHandle()
                if (!compact) Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
                    Text(habit.icon, fontSize = 24.sp)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (compact) Text(habit.icon, fontSize = 20.sp, modifier = Modifier.widthIn(max = 28.dp), maxLines = 1, overflow = TextOverflow.Clip)
                        Text(habit.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("$dayLabel · ${habit.category}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (habit.targetCount > 1) Text("$progress / ${habit.targetCount} ${habit.targetUnit}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!stackLabel.isNullOrBlank()) Text("After $stackLabel", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else if (!habit.description.isNullOrBlank()) Text(habit.description.orEmpty(), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (quickActions != null) quickActions()
                CheckInButton(status, checkInEnabled, onCheckIn, onLongCheckIn, accent = accent, label = habit.name,
                    actionLabel = if (habit.targetCount > 1 && status != LogStatus.COMPLETED) "Add 1 ${habit.targetUnit}" else null,
                    progressFraction = if (habit.targetCount > 1) progress.toFloat() / habit.targetCount else null)
            }
        }
    }
}
