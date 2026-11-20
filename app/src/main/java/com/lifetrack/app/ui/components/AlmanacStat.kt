package com.lifetrack.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lifetrack.app.ui.AlmanacType

/** Almanac stat well: sunken parchment with a mono metric figure and a caps label.
 *  Shared by Habit Detail stat cards and the activity history dialog; replaces the
 *  per-file private copies so every metric well stays visually identical.
 *  NOTE the argument order — value FIRST, label second. AnalyticsScreen's MetricCard
 *  is a different (ruled-panel) variant and deliberately takes (label, value); when
 *  adding call sites double-check which component you are feeding. */
@Composable
fun AlmanacStat(value: String, label: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(12.dp)) {
            Text(value, style = AlmanacType.monoMetric, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(4.dp))
            Text(label, style = AlmanacType.labelCaps, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
