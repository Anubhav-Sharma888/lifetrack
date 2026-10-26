package com.lifetrack.app.ui

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelectionDialog(
    selected: LocalDate,
    latest: LocalDate? = null,
    onDismiss: () -> Unit,
    onSelect: (LocalDate) -> Unit
) {
    val picker = rememberDatePickerState(
        initialSelectedDateMillis = selected.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        yearRange = 1900..maxOf(LocalDate.now().year + 100, selected.year),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean = latest == null ||
                !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isAfter(latest)
        }
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(enabled = picker.selectedDateMillis != null, onClick = {
            picker.selectedDateMillis?.let { onSelect(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
        }) { Text("Choose date") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(picker, modifier = Modifier, showModeToggle = true) }
}
