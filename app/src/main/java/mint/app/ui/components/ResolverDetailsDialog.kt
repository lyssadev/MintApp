package mint.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.BrandInstagram
import io.github.lyxnx.compose.ui.tablericons.outline.BrandPinterest
import io.github.lyxnx.compose.ui.tablericons.outline.BrandReddit
import io.github.lyxnx.compose.ui.tablericons.outline.BrandTiktok
import io.github.lyxnx.compose.ui.tablericons.outline.BrandX
import io.github.lyxnx.compose.ui.tablericons.outline.BrandYoutube
import io.github.lyxnx.compose.ui.tablericons.outline.Gif
import io.github.lyxnx.compose.ui.tablericons.outline.Music
import io.github.lyxnx.compose.ui.tablericons.outline.Photo
import io.github.lyxnx.compose.ui.tablericons.outline.Video
import mint.app.R

private data class ResolverCapability(
    val labelRes: Int,
    val icon: ImageVector,
)

private data class ResolverDetail(
    val nameRes: Int,
    val icon: ImageVector,
    val capabilities: List<ResolverCapability>,
)

private val capVideo = ResolverCapability(R.string.capability_video, TablerIcons.Outline.Video)
private val capAudio = ResolverCapability(R.string.capability_audio, TablerIcons.Outline.Music)
private val capImage = ResolverCapability(R.string.capability_image, TablerIcons.Outline.Photo)
private val capGif = ResolverCapability(R.string.capability_gif, TablerIcons.Outline.Gif)

private val resolverDetails = listOf(
    ResolverDetail(
        R.string.platform_youtube,
        TablerIcons.Outline.BrandYoutube,
        listOf(capVideo, capAudio),
    ),
    ResolverDetail(
        R.string.platform_instagram,
        TablerIcons.Outline.BrandInstagram,
        listOf(capVideo, capImage),
    ),
    ResolverDetail(
        R.string.platform_tiktok,
        TablerIcons.Outline.BrandTiktok,
        listOf(capVideo, capImage),
    ),
    ResolverDetail(
        R.string.platform_pinterest,
        TablerIcons.Outline.BrandPinterest,
        listOf(capVideo, capImage, capGif),
    ),
    ResolverDetail(
        R.string.platform_reddit,
        TablerIcons.Outline.BrandReddit,
        listOf(capVideo, capImage, capGif),
    ),
    ResolverDetail(
        R.string.platform_x,
        TablerIcons.Outline.BrandX,
        listOf(capVideo),
    ),
)

@Composable
fun ResolverDetailsDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.home_resolvers_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    resolverDetails.forEachIndexed { index, resolver ->
                        ResolverRow(resolver)
                        if (index != resolverDetails.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(start = 44.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.action_close))
                }
            }
        }
    }
}

@Composable
private fun ResolverRow(resolver: ResolverDetail) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = resolver.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = stringResource(resolver.nameRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                resolver.capabilities.forEach { capability ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = capability.icon,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(12.dp),
                            )
                            Text(
                                text = stringResource(capability.labelRes),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
