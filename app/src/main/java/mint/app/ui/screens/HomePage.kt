package mint.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.Check
import io.github.lyxnx.compose.ui.tablericons.outline.Clipboard
import io.github.lyxnx.compose.ui.tablericons.outline.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mint.app.data.DownloadManager
import mint.app.data.DownloadPhase
import mint.app.data.DownloadService
import mint.app.data.StreamInfo
import mint.app.data.StreamOption
import mint.app.data.YouTubeResolver
import mint.app.ui.components.StreamInfoCard
import mint.app.ui.components.formatBytes
import mint.app.ui.theme.RobotoMonoMedium

private sealed interface ResolveState {
    data object Idle : ResolveState
    data object Loading : ResolveState
    data class Success(val info: StreamInfo) : ResolveState
    data class Error(val message: String) : ResolveState
}

private object HomeSession {
    var link by mutableStateOf("")
    var state by mutableStateOf<ResolveState>(ResolveState.Idle)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
}

@Composable
fun HomePage(modifier: Modifier = Modifier) {
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    val context = LocalContext.current
    val downloadState by DownloadManager.state.collectAsState()

    LaunchedEffect(downloadState.isComplete) {
        if (downloadState.isComplete) {
            delay(5000)
            DownloadManager.reset()
        }
    }

    val startDownload: (StreamOption) -> Unit = { option ->
        val info = (HomeSession.state as? ResolveState.Success)?.info
        if (info != null) {
            DownloadManager.reset()
            DownloadService.start(
                context,
                info.originalUrl,
                option.formatId,
                info.title,
                option.format,
                option.estimatedSizeBytes,
                option.hasAudio,
                info.thumbnailUrl,
            )
        }
    }

    val resolve: (String) -> Unit = { url ->
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            HomeSession.state = ResolveState.Loading
            HomeSession.scope.launch {
                HomeSession.state = try {
                    ResolveState.Success(YouTubeResolver.resolve(trimmed))
                } catch (e: Exception) {
                    ResolveState.Error(e.message ?: "Couldn't resolve link")
                }
            }
        }
    }

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(
                    rememberScrollState(),
                    overscrollEffect = null,
                )
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top,
        ) {
            Spacer(modifier = Modifier.height(56.dp))
            ShimmerTitle()
            Spacer(modifier = Modifier.height(40.dp))
            OutlinedTextField(
                value = HomeSession.link,
                onValueChange = { HomeSession.link = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "Paste link here...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { resolve(HomeSession.link) }),
                trailingIcon = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                        if (!text.isNullOrBlank()) {
                            HomeSession.link = text
                            resolve(text)
                        }
                    }) {
                        Icon(
                            imageVector = TablerIcons.Outline.Clipboard,
                            contentDescription = "Paste from clipboard",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "We support YouTube, Instagram & TikTok. More soon!",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(20.dp))
            when (val current = HomeSession.state) {
                ResolveState.Idle -> Unit
                ResolveState.Loading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                is ResolveState.Error -> {
                    Text(
                        text = current.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                is ResolveState.Success -> {
                    AnimatedVisibility(
                        visible = !downloadState.isDownloading && !downloadState.isComplete,
                        exit = fadeOut(
                            animationSpec = tween(250, easing = FastOutSlowInEasing),
                        ) + scaleOut(
                            targetScale = 0.95f,
                            animationSpec = tween(250, easing = FastOutSlowInEasing),
                        ),
                    ) {
                        StreamInfoCard(
                            info = current.info,
                            downloading = false,
                            onOptionClick = startDownload,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = downloadState.isDownloading,
                enter = fadeIn(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + scaleIn(
                    initialScale = 0.95f,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ),
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                DownloadProgressCard()
            }
            AnimatedVisibility(
                visible = downloadState.isComplete,
                enter = fadeIn(
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ) + scaleIn(
                    initialScale = 0.95f,
                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                ),
            ) {
                Spacer(modifier = Modifier.height(12.dp))
                DownloadCompleteCard()
            }
            downloadState.error?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Spacer(modifier = Modifier.height(120.dp))
        }
    }
}

@Composable
private fun ShimmerTitle() {
    val shimmerTransition = rememberInfiniteTransition(label = "mintShimmer")
    val shimmerProgress by shimmerTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 5000, easing = LinearEasing),
        ),
        label = "mintShimmerProgress",
    )
    var textWidthPx by remember { mutableStateOf(0f) }

    val titleStyle = if (textWidthPx > 0f) {
        val bandWidth = textWidthPx * 2f
        val center = -bandWidth + shimmerProgress * (textWidthPx + 2f * bandWidth)
        val base = MaterialTheme.colorScheme.onBackground
        val sheen = if (base.luminance() < 0.5f) {
            Color(0xFF6E6E6E)
        } else {
            Color(0xFF9E9E9E)
        }
        TextStyle(
            fontFamily = RobotoMonoMedium,
            fontWeight = FontWeight.Medium,
            fontSize = 52.sp,
            brush = Brush.linearGradient(
                colors = listOf(base, base, sheen, base, base),
                start = Offset(center - bandWidth / 2f, 0f),
                end = Offset(center + bandWidth / 2f, 0f),
            ),
        )
    } else {
        TextStyle(
            fontFamily = RobotoMonoMedium,
            fontWeight = FontWeight.Medium,
            fontSize = 52.sp,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }

    Text(
        text = "Mint",
        style = titleStyle,
        onTextLayout = { textWidthPx = it.size.width.toFloat() },
    )
}

@Composable
private fun DownloadProgressCard() {
    val downloadState by DownloadManager.state.collectAsState()
    val smoothProgress by animateFloatAsState(
        targetValue = downloadState.progress / 100f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ),
        label = "smoothDownloadProgress",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = TablerIcons.Outline.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when (downloadState.phase) {
                        DownloadPhase.PREPARING -> "Preparing download..."
                        DownloadPhase.PROCESSING -> "Processing..."
                        DownloadPhase.DOWNLOADING -> "Downloading ${downloadState.progress}%"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = formatSpeed(downloadState.speedBytesPerSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloadState.phase != DownloadPhase.DOWNLOADING) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(
                    progress = { smoothProgress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (downloadState.totalBytes > 0) {
                Text(
                    text = "${formatBytes(downloadState.downloadedBytes)} / ${formatBytes(downloadState.totalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Surface(
                onClick = { DownloadService.cancelActive() },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun DownloadCompleteCard() {
    val downloadState by DownloadManager.state.collectAsState()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = TablerIcons.Outline.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "Download complete",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                text = downloadState.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )
            Text(
                text = downloadState.savedPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
            )
        }
    }
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 B/s"
    val kb = bytesPerSec / 1024.0
    val mb = kb / 1024.0
    return when {
        mb >= 1 -> String.format("%.1f MB/s", mb)
        kb >= 1 -> String.format("%.0f KB/s", kb)
        else -> "$bytesPerSec B/s"
    }
}
