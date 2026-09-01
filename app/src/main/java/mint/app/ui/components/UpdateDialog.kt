package mint.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import mint.app.BuildConfig
import mint.app.core.update.AppUpdater
import mint.app.core.update.UpdateUiState
import java.util.Locale

@Composable
fun UpdateDialog() {
    val phase = UpdateUiState.phase
    if (phase is UpdateUiState.Phase.Idle) return

    Dialog(onDismissRequest = { UpdateUiState.dismiss() }) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (phase) {
                    is UpdateUiState.Phase.Checking -> CheckingContent()
                    is UpdateUiState.Phase.UpToDate -> UpToDateContent()
                    is UpdateUiState.Phase.Available -> AvailableContent(phase.info, phase.fromAuto)
                    is UpdateUiState.Phase.Downloading -> DownloadingContent(phase.info)
                    is UpdateUiState.Phase.InstallPermission -> InstallPermissionContent(phase.info)
                    is UpdateUiState.Phase.Error -> ErrorContent(phase.message)
                    is UpdateUiState.Phase.Idle -> {}
                }
            }
        }
    }
}

@Composable
private fun CheckingContent() {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.height(28.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Checking for updates",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Looking for a newer version than v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun UpToDateContent() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "You're up to date",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "v${BuildConfig.VERSION_NAME} is the latest version",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Button(
        onClick = { UpdateUiState.dismiss() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("OK")
    }
}

@Composable
private fun AvailableContent(info: AppUpdater.ReleaseInfo, fromAuto: Boolean) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Update available",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${info.name} (${info.tagName}) is available. You're on v${BuildConfig.VERSION_NAME}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (info.body.isNotBlank()) {
            Text(
                text = info.body.take(400),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .verticalScroll(rememberScrollState()),
            )
        }
        if (info.apkSize > 0) {
            Text(
                text = "Size: ${formatSize(info.apkSize)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            onClick = {
                if (fromAuto) {
                    UpdateUiState.remindLater(context)
                } else {
                    UpdateUiState.dismiss()
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            Text(if (fromAuto) "Remind me later" else "Cancel")
        }
        Button(
            onClick = { UpdateUiState.startUpdate(context) },
            modifier = Modifier.weight(1f),
        ) {
            Text("Update")
        }
    }
}

@Composable
private fun DownloadingContent(info: AppUpdater.ReleaseInfo) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Downloading update",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "${info.name} (${info.tagName})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val percent = (UpdateUiState.downloadProgress * 100).toInt()
        if (UpdateUiState.downloadProgress > 0f) {
            LinearProgressIndicator(
                progress = { UpdateUiState.downloadProgress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.height(28.dp))
            Text(
                text = "Preparing download...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstallPermissionContent(info: AppUpdater.ReleaseInfo) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Install permission required",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Mint needs permission to install apps from this source to update to ${info.tagName}. Tap the button below, then allow it and come back to tap Update again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(
            onClick = { UpdateUiState.dismiss() },
            modifier = Modifier.weight(1f),
        ) {
            Text("Cancel")
        }
        Button(
            onClick = { UpdateUiState.openInstallSettings(context) },
            modifier = Modifier.weight(1f),
        ) {
            Text("Open settings")
        }
    }
}

@Composable
private fun ErrorContent(message: String) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "Update failed",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    Button(
        onClick = { UpdateUiState.dismiss() },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Close")
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val kb = 1024.0
    val mb = kb * 1024
    return if (bytes >= mb) {
        String.format(Locale.US, "%.1f MB", bytes / mb)
    } else {
        String.format(Locale.US, "%.0f KB", bytes / kb)
    }
}
