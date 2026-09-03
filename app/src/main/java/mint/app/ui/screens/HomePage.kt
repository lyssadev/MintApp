package mint.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.Clipboard
import io.github.lyxnx.compose.ui.tablericons.outline.Download
import io.github.lyxnx.compose.ui.tablericons.outline.X
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mint.app.core.manager.DownloadManager
import mint.app.core.model.DownloadItem
import mint.app.core.model.DownloadStatus
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.service.DownloadService
import mint.app.resolution.ResolverRegistry
import mint.app.ui.components.StreamInfoCard
import mint.app.ui.components.formatBytes
import mint.app.ui.theme.RobotoMonoMedium

sealed interface ResolveState {
    data object Idle : ResolveState
    data object Loading : ResolveState
    data class Success(val info: MediaItem) : ResolveState
    data class Error(val message: String) : ResolveState
}

object HomeSession {
    var link by mutableStateOf("")
    var state by mutableStateOf<ResolveState>(ResolveState.Idle)
    var activeDownloadId by mutableStateOf<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun resolveUrl(url: String) {
        link = url
        activeDownloadId = null
        state = ResolveState.Loading
        scope.launch {
            state = try {
                ResolveState.Success(ResolverRegistry.resolve(url))
            } catch (e: Exception) {
                ResolveState.Error(e.message ?: "Couldn't resolve link")
            }
        }
    }

    fun reset() {
        activeDownloadId = null
        state = ResolveState.Idle
    }
}

@Composable
fun HomePage(modifier: Modifier = Modifier) {
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }

    val context = LocalContext.current

    val startDownload: (MediaFormat, Int?) -> Unit = { option, index ->
        val info = (HomeSession.state as? ResolveState.Success)?.info
        if (info != null) {
            val title = if (index != null) "${info.title} (${index + 1})" else info.title
            val directUrl = if (info.platform == "youtube") null else option.url
            DownloadService.start(
                context,
                info.originalUrl,
                option.formatId,
                title,
                option.format,
                option.estimatedSizeBytes,
                option.hasAudio,
                info.thumbnailUrl,
                directUrl,
                option.httpHeaders,
            )
        }
    }

    val resolve: (String) -> Unit = { url ->
        val trimmed = url.trim()
        if (trimmed.isNotEmpty()) {
            HomeSession.resolveUrl(trimmed)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (HomeSession.link.isNotBlank()) {
                            IconButton(onClick = {
                                HomeSession.link = ""
                                HomeSession.reset()
                            }) {
                                Icon(
                                    imageVector = TablerIcons.Outline.X,
                                    contentDescription = "Clear link",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
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
                    val info = current.info
                    val allItems = info.imageOptions + info.gifOptions + info.videoOptions
                    when {
                        info.platform != "youtube" && (info.imageOptions.isNotEmpty() || info.gifOptions.isNotEmpty() || allItems.size > 1) -> MediaOptionsSection(
                            info = info,
                            onDownloadItem = { option -> startDownload(option, null) },
                            onDownloadAll = {
                                allItems.forEachIndexed { index, option ->
                                    startDownload(option, index)
                                }
                            },
                        )
                        info.platform == "youtube" -> StreamInfoCard(
                            info = info,
                            downloading = false,
                            onOptionClick = { option -> startDownload(option, null) },
                        )
                        else -> AutoDownloadFlow(info = info)
                    }
                }
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
private fun MediaOptionsSection(
    info: MediaItem,
    onDownloadItem: (MediaFormat) -> Unit,
    onDownloadAll: () -> Unit,
) {
    val allItems = info.imageOptions + info.gifOptions + info.videoOptions
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (allItems.size > 1) {
                Surface(
                    onClick = onDownloadAll,
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Text(
                        text = "Download all (${allItems.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
        allItems.forEach { option ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = if (option in info.imageOptions || option in info.gifOptions) {
                            option.url
                        } else {
                            info.thumbnailUrl
                        },
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (option.estimatedSizeBytes > 0) {
                            Text(
                                text = formatBytes(option.estimatedSizeBytes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Surface(
                        onClick = { onDownloadItem(option) },
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = TablerIcons.Outline.Download,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Download",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoDownloadFlow(info: MediaItem) {
    val context = LocalContext.current
    val items by DownloadManager.items.collectAsState()
    val activeItem = items.firstOrNull { it.id == HomeSession.activeDownloadId }
    val activeStatus = activeItem?.status
    var sawActive by remember { mutableStateOf(false) }

    LaunchedEffect(info.originalUrl) {
        if (HomeSession.activeDownloadId == null) {
            val bestFormat = info.videoOptions.firstOrNull()
                ?: info.imageOptions.firstOrNull()
                ?: info.gifOptions.firstOrNull()
                ?: info.audioOptions.firstOrNull()
            val bestUrl = bestFormat?.url
            val id = DownloadService.start(
                context,
                info.originalUrl,
                if (bestUrl != null) "" else "best",
                info.title,
                "mp4",
                hasAudio = true,
                thumbnail = info.thumbnailUrl,
                imageUrl = bestUrl,
                httpHeaders = bestFormat?.httpHeaders ?: emptyMap(),
            )
            HomeSession.activeDownloadId = id
        }
    }

    LaunchedEffect(HomeSession.activeDownloadId, activeStatus) {
        val trackedId = HomeSession.activeDownloadId ?: return@LaunchedEffect
        when (activeStatus) {
            DownloadStatus.PREPARING,
            DownloadStatus.DOWNLOADING,
            DownloadStatus.PROCESSING,
            -> sawActive = true
            DownloadStatus.COMPLETED,
            DownloadStatus.FAILED,
            -> {
                if (sawActive) delay(5000)
                if (HomeSession.activeDownloadId == trackedId &&
                    HomeSession.state is ResolveState.Success
                ) {
                    HomeSession.reset()
                }
            }
            null -> {
                delay(1500)
                val itemMissing = DownloadManager.items.value.none { it.id == trackedId }
                if (itemMissing &&
                    HomeSession.activeDownloadId == trackedId &&
                    HomeSession.state is ResolveState.Success
                ) {
                    HomeSession.reset()
                }
            }
        }
    }

    when (activeStatus) {
        null -> {
            val tracked = HomeSession.activeDownloadId
            if (tracked == null || items.any { it.id == tracked }) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        DownloadStatus.COMPLETED -> AutoDownloadCard(activeItem!!, completed = true)
        DownloadStatus.FAILED -> Text(
            text = activeItem!!.error ?: "Download failed",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        else -> AutoDownloadCard(activeItem!!, completed = false)
    }
}

@Composable
private fun AutoDownloadCard(item: DownloadItem, completed: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = if (completed) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
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
                    imageVector = if (completed) TablerIcons.Outline.Download else TablerIcons.Outline.Download,
                    contentDescription = null,
                    tint = if (completed) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when {
                        completed -> "Download complete"
                        item.status == DownloadStatus.PREPARING -> "Preparing..."
                        item.status == DownloadStatus.PROCESSING -> "Processing..."
                        else -> "Downloading ${item.progress}%"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (completed) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!completed) {
                    IconButton(
                        onClick = { DownloadService.cancel(item.id) },
                        modifier = Modifier.size(22.dp),
                    ) {
                        Icon(
                            imageVector = TablerIcons.Outline.X,
                            contentDescription = "Cancel download",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            if (completed) {
                Text(
                    text = item.fileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                if (item.status == DownloadStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { item.progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (item.totalBytes > 0) {
                    Text(
                        text = "${formatBytes(item.downloadedBytes)} / ${formatBytes(item.totalBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
