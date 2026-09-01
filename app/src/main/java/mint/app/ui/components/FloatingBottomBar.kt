package mint.app.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.filled.Home as HomeFilled
import io.github.lyxnx.compose.ui.tablericons.filled.Settings as SettingsFilled
import io.github.lyxnx.compose.ui.tablericons.outline.Download as DownloadOutline
import io.github.lyxnx.compose.ui.tablericons.outline.Home as HomeOutline
import io.github.lyxnx.compose.ui.tablericons.outline.Settings as SettingsOutline
import mint.app.ui.Screen

private data class BottomBarItem(
    val screen: Screen,
    val iconOutline: ImageVector,
    val iconFilled: ImageVector,
)

private val bottomBarItems = listOf(
    BottomBarItem(Screen.Home, TablerIcons.Outline.HomeOutline, TablerIcons.Filled.HomeFilled),
    BottomBarItem(Screen.Downloads, TablerIcons.Outline.DownloadOutline, TablerIcons.Outline.DownloadOutline),
    BottomBarItem(Screen.Settings, TablerIcons.Outline.SettingsOutline, TablerIcons.Filled.SettingsFilled),
)

private val ItemWidth = 56.dp
private val ItemHeight = 44.dp

@Composable
fun FloatingBottomBar(
    selectedScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeCount by mint.app.data.DownloadManager.activeCount.collectAsState()
    val selectedIndex = bottomBarItems.indexOfFirst { it.screen == selectedScreen }
    val indicatorOffset by animateDpAsState(
        targetValue = ItemWidth * selectedIndex,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "indicatorOffset",
    )

    Surface(
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 6.dp,
    ) {
        Box(modifier = Modifier.padding(6.dp)) {
            Box(
                modifier = Modifier
                    .offset(x = indicatorOffset)
                    .width(ItemWidth)
                    .height(ItemHeight)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = CircleShape,
                    ),
            )
            Row {
                bottomBarItems.forEach { item ->
                    FloatingBottomBarItem(
                        item = item,
                        selected = item.screen == selectedScreen,
                        badgeCount = if (item.screen == Screen.Downloads) activeCount else 0,
                        onClick = { onScreenSelected(item.screen) },
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingBottomBarItem(
    item: BottomBarItem,
    selected: Boolean,
    badgeCount: Int,
    onClick: () -> Unit,
) {
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1.15f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "iconScale",
    )
    val iconTint by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "iconTint",
    )

    Box(
        modifier = Modifier
            .width(ItemWidth)
            .height(ItemHeight)
            .selectable(
                selected = selected,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = selected,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "iconCrossfade",
        ) { isSelected ->
            Icon(
                imageVector = if (isSelected) item.iconFilled else item.iconOutline,
                contentDescription = item.screen.label,
                tint = iconTint,
                modifier = Modifier
                    .size(22.dp)
                    .scale(iconScale),
            )
        }
        if (badgeCount > 0) {
            val badgeScale = remember { Animatable(1f) }
            LaunchedEffect(badgeCount) {
                badgeScale.snapTo(1f)
                badgeScale.animateTo(
                    targetValue = 1.5f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessHigh,
                    ),
                )
                badgeScale.animateTo(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 5.dp)
                    .size(15.dp)
                    .scale(badgeScale.value)
                    .background(
                        color = MaterialTheme.colorScheme.error,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = badgeCount.toString(),
                    color = MaterialTheme.colorScheme.onError,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 9.sp,
                )
            }
        }
    }
}
