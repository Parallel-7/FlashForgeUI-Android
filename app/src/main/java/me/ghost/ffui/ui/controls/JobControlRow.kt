package me.ghost.ffui.ui.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * At-a-glance job control: a Pause/Resume toggle plus a Stop button. Shared by the dashboard
 * (shown inline during an active job) and the Controls tab so the two never drift. Pause and
 * Resume are mutually exclusive per [isPaused]; Pause is disabled mid-transition ([isPausing]).
 * Stop asks for confirmation first — it kills a multi-hour print and can never be resumed.
 */
@Composable
fun JobControlRow(
    isPrinting: Boolean,
    isPaused: Boolean,
    isPausing: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showStopConfirm by rememberSaveable { mutableStateOf(false) }

    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isPaused) {
            Button(
                onClick = onResume,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                Spacer(Modifier.width(8.dp))
                Text("RESUME")
            }
        } else {
            Button(
                onClick = onPause,
                enabled = isPrinting,
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
                Spacer(Modifier.width(8.dp))
                Text(if (isPausing) "PAUSING…" else "PAUSE")
            }
        }

        Button(
            onClick = { showStopConfirm = true },
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
        ) {
            Icon(Icons.Default.Stop, contentDescription = "Stop")
        }
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Stop this print?") },
            text = { Text("The print cannot resume after you stop it.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    onCancel()
                }) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) { Text("Cancel") }
            }
        )
    }
}
