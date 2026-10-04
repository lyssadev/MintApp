package mint.app.ui.share

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.Music
import io.github.lyxnx.compose.ui.tablericons.outline.Video
import io.github.lyxnx.compose.ui.tablericons.outline.X
import kotlinx.coroutines.launch
import mint.app.R
import mint.app.core.model.MediaFormat
import mint.app.core.model.MediaItem
import mint.app.resolution.EngineSetup
import mint.app.resolution.ResolverRegistry
import mint.app.service.DownloadService
import mint.app.ui.components.IndeterminateProgressBar
import mint.app.ui.components.formatBytes

private const val OPTION_COLUMNS = 2
private const val SHEET_SHAPE_DP = 28

private sealed interface ShareMenuState {
    data object Loading : ShareMenuState
    data class Success(val info: MediaItem) : ShareMenuState
    data class Error(val message: String) : ShareMenuState
}

@Composable
fun ShareMenu(
    link: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(link) { mutableStateOf<ShareMenuState>(ShareMenuState.Loading) }
    var startingFormatId by remember(link) { mutableStateOf<String?>(null) }

    val sheetState = remember { MutableTransitionState(false).apply { targetState = true } }
    val close: () -> Unit = { sheetState.targetState = false }

    LaunchedEffect(sheetState.isIdle, sheetState.currentState) {
        if (sheetState.isIdle && !sheetState.currentState) onDismiss()
    }

    LaunchedEffect(link) {
        if (!ResolverRegistry.isYouTube(link)) {
            state = ShareMenuState.Error(context.getString(R.string.share_unsupported))
            return@LaunchedEffect
        }
        state = try {
            EngineSetup.await()
            ShareMenuState.Success(ResolverRegistry.resolve(link))
        } catch (e: Exception) {
            ShareMenuState.Error(e.message ?: context.getString(R.string.home_error_resolve))
        }
    }

    BackHandler { close() }

    val startDownload: (MediaFormat) -> Unit = { option ->
        val info = (state as? ShareMenuState.Success)?.info
        if (info != null && startingFormatId == null) {
            val trackStart = info.directDownload
            if (trackStart) startingFormatId = option.formatId
            scope.launch {
                try {
                    val resolved = if (trackStart) {
                        runCatching { ResolverRegistry.resolveDownloadUrl(info, option) }.getOrNull()
                    } else {
                        null
                    }
                    val directUrl = when {
                        resolved != null -> resolved
                        info.platform == "youtube" -> null
                        else -> option.url
                    }
                    DownloadService.start(
                        context,
                        info.originalUrl,
                        if (trackStart) "" else option.formatId,
                        info.title,
                        option.format,
                        option.estimatedSizeBytes,
                        option.hasAudio,
                        info.thumbnailUrl,
                        directUrl,
                        option.httpHeaders,
                    )
                    close()
                } finally {
                    if (trackStart) startingFormatId = null
                }
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val optionsMaxHeight = (maxHeight * 0.45f).coerceAtLeast(160.dp)

        AnimatedVisibility(
            visibleState = sheetState,
            enter = fadeIn(animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing)),
            exit = fadeOut(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { close() },
            )
        }

        AnimatedVisibility(
            visibleState = sheetState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = 640.dp)
                .fillMaxWidth(),
            enter = slideInVertically(
                animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
                initialOffsetY = { it },
            ) + fadeIn(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
            exit = slideOutVertically(
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                targetOffsetY = { it },
            ) + fadeOut(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing)),
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = SHEET_SHAPE_DP.dp, topEnd = SHEET_SHAPE_DP.dp))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
                shape = RoundedCornerShape(topStart = SHEET_SHAPE_DP.dp, topEnd = SHEET_SHAPE_DP.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 16.dp,
            ) {
                Column(
                    modifier = Modifier
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SheetHandle()
                    ShareMenuHeader(onDismiss = close)
                    AnimatedContent(
                        targetState = state,
                        transitionSpec = {
                            fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)) togetherWith
                                fadeOut(animationSpec = tween(160, easing = FastOutSlowInEasing))
                        },
                        label = "shareMenuState",
                    ) { current ->
                        when (current) {
                            is ShareMenuState.Loading -> LoadingBody()
                            is ShareMenuState.Error -> ErrorBody(current.message)
                            is ShareMenuState.Success -> OptionsBody(
                                info = current.info,
                                startingFormatId = startingFormatId,
                                optionsMaxHeight = optionsMaxHeight,
                                onOptionClick = startDownload,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetHandle() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(36.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)),
        )
    }
}

@Composable
private fun ShareMenuHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.share_choose_format),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = TablerIcons.Outline.X,
                contentDescription = stringResource(R.string.cd_close),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LoadingBody() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IndeterminateProgressBar(modifier = Modifier.fillMaxWidth())
        Text(
            text = stringResource(R.string.share_resolving),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorBody(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun OptionsBody(
    info: MediaItem,
    startingFormatId: String?,
    optionsMaxHeight: Dp,
    onOptionClick: (MediaFormat) -> Unit,
) {
    val hasOptions = info.videoOptions.isNotEmpty() || info.audioOptions.isNotEmpty()
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = info.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 96.dp, height = 54.dp)
                    .clip(RoundedCornerShape(12.dp)),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = info.uploader,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (hasOptions) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = optionsMaxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (info.videoOptions.isNotEmpty()) {
                    OptionSection(
                        title = stringResource(R.string.stream_badge_video),
                        icon = TablerIcons.Outline.Video,
                        options = info.videoOptions,
                        startingFormatId = startingFormatId,
                        onOptionClick = onOptionClick,
                    )
                }
                if (info.audioOptions.isNotEmpty()) {
                    OptionSection(
                        title = stringResource(R.string.stream_badge_audio),
                        icon = TablerIcons.Outline.Music,
                        options = info.audioOptions,
                        startingFormatId = startingFormatId,
                        onOptionClick = onOptionClick,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.stream_no_streams),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OptionSection(
    title: String,
    icon: ImageVector,
    options: List<MediaFormat>,
    startingFormatId: String?,
    onOptionClick: (MediaFormat) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionHeader(title = title, icon = icon, count = options.size)
        options.chunked(OPTION_COLUMNS).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                row.forEach { option ->
                    OptionTile(
                        option = option,
                        isStarting = option.formatId == startingFormatId,
                        blocked = startingFormatId != null,
                        onClick = { onOptionClick(option) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(OPTION_COLUMNS - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: ImageVector, count: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "· $count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun OptionTile(
    option: MediaFormat,
    isStarting: Boolean,
    blocked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = !blocked, onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (blocked) {
            MaterialTheme.colorScheme.surfaceContainerHighest
        } else {
            MaterialTheme.colorScheme.primaryContainer
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (blocked) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (option.estimatedSizeBytes > 0) {
                Text(
                    text = formatBytes(option.estimatedSizeBytes),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (blocked) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    },
                )
            }
        }
    }
}
