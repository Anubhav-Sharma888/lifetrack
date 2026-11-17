package com.lifetrack.app.ui

// ---------------------------------------------------------------------------
// Milestones & Achievements — the Almanac register over the derived engine
// state in domain/Achievements.kt. Nothing here stores anything: the screen
// reads evaluated Progress (already derived by HabitViewModel) plus the durable
// acknowledgment record, and distinguishes locked from achieved with icon
// tint, text state and a progress line — never colour alone.
// ---------------------------------------------------------------------------

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lifetrack.app.data.AchievementAcknowledgment
import com.lifetrack.app.domain.AchievementCategory
import com.lifetrack.app.domain.AchievementDefinition
import com.lifetrack.app.domain.AchievementIds
import com.lifetrack.app.domain.Achievements
import kotlin.math.roundToInt

/** Fixed catalogue presentation order for the category sections. */
private val sectionOrder = listOf(
    AchievementCategory.HABIT_COMPLETION,
    AchievementCategory.CONSISTENCY,
    AchievementCategory.ACTIVITY,
    AchievementCategory.REFLECTION
)

/** Plain running header per section (labelCaps keeps existing casing). */
private fun categoryTitle(category: AchievementCategory): String = when (category) {
    AchievementCategory.HABIT_COMPLETION -> "Habit completion"
    AchievementCategory.CONSISTENCY -> "Consistency"
    AchievementCategory.ACTIVITY -> "Activity progress"
    AchievementCategory.REFLECTION -> "Daily reflections"
}

/** One quiet seal icon per category; state is carried by tint, not by a different glyph. */
private fun categoryIcon(category: AchievementCategory): ImageVector = when (category) {
    AchievementCategory.HABIT_COMPLETION -> Icons.Outlined.TaskAlt
    AchievementCategory.CONSISTENCY -> Icons.Outlined.LocalFireDepartment
    AchievementCategory.ACTIVITY -> Icons.Outlined.DirectionsRun
    AchievementCategory.REFLECTION -> Icons.Outlined.EditNote
}

/**
 * The full achievements register: earned summary header, then the catalogue grouped into
 * fixed category sections. [acknowledgments] must be supplied by the caller (the durable
 * `achievement_acknowledgments` records — LifeTrackState itself does not carry them); only
 * their recency ordering is used here, for the "Recently unlocked" caption.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AchievementsScreen(
    state: LifeTrackState,
    achievements: List<Achievements.Progress>,
    acknowledgments: List<AchievementAcknowledgment>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val earned = achievements.count { it.achieved }
    // The engine flow emits after first collection; falling back to the catalogue size keeps
    // the summary truthful ("0 of 13") during that first frame instead of flashing "of 0".
    val total = achievements.size.takeIf { it > 0 } ?: Achievements.catalogue.size
    val percent = if (total == 0) 0 else ((earned * 100.0) / total).roundToInt()
    val summaryFraction = if (total == 0) 0f else earned.toFloat() / total
    val titlesById = Achievements.catalogue.associateBy { it.id }
    val recentTitles = acknowledgments.asSequence()
        .sortedByDescending { it.acknowledgedAt }
        .take(3)
        .mapNotNull { titlesById[it.achievementId]?.title }
        .toList()
    val blankSlate = state.habits.isEmpty() && state.activities.isEmpty() && state.reflections.isEmpty()
    var openDetailId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(modifier = modifier, topBar = {
        TopAppBar(
            title = { Text("Achievements") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!blankSlate) item(key = "summary") {
                EarnedSummary(earned, total, percent, summaryFraction, recentTitles)
            }
            when {
                blankSlate -> item(key = "blank-slate") { BlankSlateCard() }
                achievements.isEmpty() -> item(key = "counting") {
                    Text(
                        "Counting your milestones…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> sectionOrder.forEach { category ->
                    val cards = achievements.filter { it.definition.category == category }
                    if (cards.isNotEmpty()) {
                        item(key = "header-${category.name}") { SectionHeader(categoryTitle(category)) }
                        items(cards, key = { it.definition.id }) { progress ->
                            AchievementCard(progress, onOpen = { openDetailId = progress.definition.id })
                        }
                    }
                }
            }
        }
    }
    openDetailId?.let { id ->
        achievements.firstOrNull { it.definition.id == id }?.let { selected ->
            AchievementDetailDialog(progress = selected, onDismiss = { openDetailId = null })
        }
    }
}

/** Large earned summary: "X of Y achieved", the percentage in mono, the sage progress line. */
@Composable
private fun EarnedSummary(earned: Int, total: Int, percent: Int, fraction: Float, recentTitles: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, almanacColors().rule)
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "$earned of $total achieved",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("$percent%", style = AlmanacType.monoMetric, color = MaterialTheme.colorScheme.onSurface)
            LinearProgressIndicator(
                progress = { fraction.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
            if (recentTitles.isNotEmpty()) {
                Text("Recently unlocked", style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    recentTitles.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Friendly invitation shown while no habits, activities and reflections exist at all. */
@Composable
private fun BlankSlateCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, almanacColors().rule)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "Milestones appear as you build your routines",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center
            )
            Text(
                "Add a habit, record an activity or save a reflection — every milestone on this page is " +
                    "counted from your own data, on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Ruled section header: hairline measurement rule, then the label-caps running title. */
@Composable
private fun SectionHeader(title: String) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(thickness = 1.dp, color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(10.dp))
        Text(title, style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One catalogue entry as a sunken parchment card. Locked vs achieved is expressed three ways
 * (icon tint, text state, progress line) so no state relies on colour alone. The whole card
 * opens the detail dialog; the seal column is height-filled under IntrinsicSize.Min so cards
 * in a section align, and the two-line minimum description keeps their heights consistent.
 */
@Composable
private fun AchievementCard(progress: Achievements.Progress, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val definition = progress.definition
    val iconTint = if (progress.achieved) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, almanacColors().rule)
    ) {
        Row(
            Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 48.dp)
                .clickable(onClickLabel = "View details", role = Role.Button) { onOpen() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(Modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        categoryIcon(definition.category),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = iconTint
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    definition.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    definition.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                AchievementStateLine(progress)
            }
        }
    }
}

/**
 * The card's state line. TalkBack hears the achievement state here
 * ("Achieved" / "Locked, 4 of 7") inside the card's merged announcement, which already
 * carries the title; the visible bar is decorative because the numbers are already
 * spoken and shown as text.
 */
@Composable
private fun AchievementStateLine(progress: Achievements.Progress) {
    val target = progress.definition.target
    if (progress.achieved) {
        Text(
            "✓ Achieved",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.semantics { contentDescription = "Achieved" }
        )
    } else {
        val fraction = if (target > 0L) (progress.current.toFloat() / target).coerceIn(0f, 1f) else 0f
        Row(
            Modifier.fillMaxWidth().heightIn(min = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "${progress.current} / $target",
                style = AlmanacType.monoData,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "Locked, ${progress.current} of $target"
                }
            )
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.weight(1f).height(4.dp).clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}

/**
 * Plain-words restatement of the engine rule for one achievement, prefixed with
 * "Requirement:" in the dialog. The per-id branches mirror the catalogue exactly; the
 * fallback derives from the category (exhaustive) so a future catalogue id degrades to
 * a correct category-level rule instead of a wrong specific one.
 */
private fun requirementPhrase(definition: AchievementDefinition): String = when (definition.id) {
    AchievementIds.FIRST_COMPLETION -> "fully complete one habit day"
    AchievementIds.COMPLETIONS_7 -> "reach 7 fully completed habit days in total"
    AchievementIds.COMPLETIONS_30 -> "reach 30 fully completed habit days in total"
    AchievementIds.COMPLETIONS_100 -> "reach 100 fully completed habit days in total"
    AchievementIds.STREAK_DAYS_7 -> "reach a 7-day qualifying streak on any habit"
    AchievementIds.STREAK_DAYS_30 -> "reach a 30-day qualifying streak on any habit"
    AchievementIds.STREAK_DAYS_90 -> "reach a 90-day qualifying streak on any habit"
    AchievementIds.FIRST_ACTIVITY_SESSION -> "record your first activity entry"
    AchievementIds.FIRST_PB_IMPROVEMENT -> "beat your own best in any activity"
    AchievementIds.FIRST_TARGET_ATTAINED -> "reach the goal of a directional activity"
    AchievementIds.FIRST_REFLECTION -> "save your first mood or journal entry"
    AchievementIds.REFLECTION_DATES_7 -> "reflect on 7 different days"
    AchievementIds.REFLECTION_DATES_30 -> "reflect on 30 different days"
    else -> when (definition.category) {
        AchievementCategory.HABIT_COMPLETION -> "reach ${definition.target} fully completed habit days in total"
        AchievementCategory.CONSISTENCY -> "reach a ${definition.target}-day qualifying streak on any habit"
        AchievementCategory.ACTIVITY -> "complete the qualifying activity milestone"
        AchievementCategory.REFLECTION -> "reflect on ${definition.target} different days"
    }
}

/**
 * Read-only detail sheet for one achievement: the requirement in plain words, the current
 * standing, what remains, and — only where the engine's rules have a nuance worth naming —
 * the Smart Streaks / protected-partial clarifications.
 */
@Composable
fun AchievementDetailDialog(progress: Achievements.Progress, onDismiss: () -> Unit) {
    val definition = progress.definition
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(definition.title) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(definition.description, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Requirement: ${requirementPhrase(definition)}.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Progress: ${progress.current} of ${definition.target}",
                    style = AlmanacType.monoData,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!progress.achieved) {
                    Text(
                        "${definition.target - progress.current} more to go",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                when (definition.category) {
                    AchievementCategory.CONSISTENCY -> Text(
                        "Days kept alive by Smart Streaks or freezes count toward the chain, " +
                            "but only fully completed days count toward the total.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AchievementCategory.HABIT_COMPLETION -> {
                        if (!progress.achieved && progress.current > 0L) {
                            Text(
                                "Protected partial days never count as full completions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    AchievementCategory.ACTIVITY -> { }
                    AchievementCategory.REFLECTION -> { }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Close") } }
    )
}

/**
 * Compact one-line preview of the next locked milestone for Today: title, current/target in
 * mono, and the thin sage line. The caller hides it when no milestone is outstanding.
 */
@Composable
fun NextMilestoneCard(progress: Achievements.Progress, modifier: Modifier = Modifier) {
    val definition = progress.definition
    val target = definition.target
    val fraction = if (target > 0L) (progress.current.toFloat() / target).coerceIn(0f, 1f) else 0f
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, almanacColors().rule)
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Next milestone · ${definition.title}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${progress.current} / $target",
                    style = AlmanacType.monoData,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp).clearAndSetSemantics { },
                color = MaterialTheme.colorScheme.secondary,
                trackColor = MaterialTheme.colorScheme.outlineVariant
            )
        }
    }
}
