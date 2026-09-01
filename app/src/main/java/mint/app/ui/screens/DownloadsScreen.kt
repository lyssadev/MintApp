package mint.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.Folder
import io.github.lyxnx.compose.ui.tablericons.outline.Trash
import io.github.lyxnx.compose.ui.tablericons.outline.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mint.app.core.manager.DownloadManager
import mint.app.core.model.DownloadItem
import mint.app.core.model.DownloadStatus
import mint.app.data.DownloadService
import mint.app.ui.components.formatBytes
import mint.app.ui.theme.RobotoMonoMedium
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DownloadsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val items by DownloadManager.items.collectAsState()
    var pendingDelete by remember { mutableStateOf<DownloadItem?>(null) }

    LaunchedEffect(Unit) { DownloadManager.load(context) }

    val activeItems = items.filter { it.isActive }
    val completedItems = items.filter { it.status == DownloadStatus.COMPLETED }
    val failedItems = items.filter { it.status == DownloadStatus.FAILED }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Spacer(modifier = Modifier.height(56.dp))
        Text(
            text = "Downloads",
            style = MaterialTheme.typography.titleLarge.copy(
                fontFamily = RobotoMonoMedium,
                fontWeight = FontWeight.Medium,
                fontSize = 32.sp,
            ),
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(modifier = Modifier.height(28.dp))

        if (activeItems.isNotEmpty()) {
            SectionHeader("In progress")
            Spacer(modifier = Modifier.height(12.dp))
            activeItems.forEach { item ->
                ActiveDownloadCard(item = item)
                Spacer(modifier = Modifier.height(12.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (failedItems.isNotEmpty()) {
            SectionHeader("Failed")
            Spacer(modifier = Modifier.height(12.dp))
            failedItems.forEach { item ->
                FailedDownloadCard(
                    item = item,
                    onDelete = { pendingDelete = item },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        SectionHeader("Completed")
        Spacer(modifier = Modifier.height(12.dp))
        if (completedItems.isEmpty()) {
            Text(
                text = "No downloads yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            completedItems.forEach { item ->
                CompletedDownloadCard(
                    item = item,
                    onDelete = { pendingDelete = item },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        Spacer(modifier = Modifier.height(120.dp))
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete download?") },
            text = { Text("\"${item.fileName}\" will be permanently removed from your device.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteEntry(context, item)
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Thumbnail(url: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                shape = RoundedCornerShape(10.dp),
            ),
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp)),
        )
    }
}

@Composable
private fun ActiveDownloadCard(item: DownloadItem) {
    val context = LocalContext.current
    val smoothProgress by animateFloatAsState(
        targetValue = item.progress / 100f,
        label = "downloadProgress",
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                url = item.thumbnailUrl,
                modifier = Modifier
                    .width(96.dp)
                    .height(56.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when (item.status) {
                        DownloadStatus.PREPARING -> "Preparing..."
                        DownloadStatus.PROCESSING -> "Processing..."
                        DownloadStatus.DOWNLOADING ->
                            "${item.progress}% · ${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (item.status == DownloadStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { smoothProgress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            IconButton(onClick = { DownloadService.cancelBroadcast(context, item.id) }) {
                Icon(
                    imageVector = TablerIcons.Outline.X,
                    contentDescription = "Cancel download",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun FailedDownloadCard(
    item: DownloadItem,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                url = item.thumbnailUrl,
                modifier = Modifier
                    .width(96.dp)
                    .height(56.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.error ?: "Download failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = TablerIcons.Outline.Trash,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CompletedDownloadCard(
    item: DownloadItem,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var fileExists by remember(item.id, item.uri, item.savedPath) { mutableStateOf(true) }
    LaunchedEffect(item.id, item.uri, item.savedPath) {
        fileExists = withContext(Dispatchers.IO) {
            DownloadManager.fileExists(context, item)
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(
                url = item.thumbnailUrl,
                modifier = Modifier
                    .width(96.dp)
                    .height(56.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (fileExists) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!fileExists) {
                    Text(
                        text = "File deleted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${formatBytes(item.downloadedBytes)} · ${
                            SimpleDateFormat("MMM d · HH:mm", Locale.getDefault())
                                .format(Date(item.timestamp))
                        }",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            if (fileExists) {
                Row {
                    IconButton(onClick = { locateFile(context, item) }) {
                        Icon(
                            imageVector = TablerIcons.Outline.Folder,
                            contentDescription = "Locate download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = TablerIcons.Outline.Trash,
                            contentDescription = "Delete download",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun locateFile(context: Context, item: DownloadItem) {
    val uri = item.uri?.let(Uri::parse)
    val intent = if (uri != null && uri.scheme == "content") {
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, item.mime.ifBlank { "*/*" })
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    } else {
        Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, "Locate download"))
    }.onFailure {
        runCatching {
            context.startActivity(
                Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

private fun deleteEntry(context: Context, item: DownloadItem) {
    runCatching {
        when {
            item.uri?.startsWith("content://") == true ->
                context.contentResolver.delete(Uri.parse(item.uri), null, null)
            item.savedPath.isNotBlank() -> File(item.savedPath).delete()
        }
    }
    DownloadManager.remove(item.id)
}