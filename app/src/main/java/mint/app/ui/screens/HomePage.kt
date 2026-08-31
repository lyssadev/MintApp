package mint.app.ui.screens

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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.Clipboard
import mint.app.ui.theme.RobotoMonoMedium

@Composable
fun HomePage(modifier: Modifier = Modifier) {
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
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

    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(modifier = Modifier.height(56.dp))
            Text(
                text = "Mint",
                style = titleStyle,
                onTextLayout = { textWidthPx = it.size.width.toFloat() },
            )
            Spacer(modifier = Modifier.height(40.dp))
            var link by remember { mutableStateOf("") }
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "Paste link here...",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                trailingIcon = {
                    IconButton(onClick = {}) {
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
        }
    }
}
