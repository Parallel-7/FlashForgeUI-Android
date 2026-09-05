package me.ghost.ffui.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Label/value row shared by the printer info screen, the Spoolman info dialog, and Settings.
 * Rows with a null [value] render nothing (the caller doesn't need its own null branch).
 */
@Composable
fun InfoRow(label: String, value: String?, modifier: Modifier = Modifier) {
    if (value == null) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Section title shared by the files list and the spool editor. [emphasized] picks the
 * primary-colored form-group look; the default is the compact uppercase-ish list-section look.
 */
@Composable
fun SectionHeader(title: String, emphasized: Boolean = false, modifier: Modifier = Modifier) {
    Text(
        title,
        style = if (emphasized) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelSmall,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Bold,
        color = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (emphasized) modifier else modifier.padding(top = 4.dp)
    )
}
