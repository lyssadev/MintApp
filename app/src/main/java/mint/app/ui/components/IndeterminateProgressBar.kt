package mint.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun IndeterminateProgressBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "indeterminateProgress")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
        ),
        label = "indeterminateProgressFraction",
    )

    val barWidthFraction = 0.35f
    val offsetFraction = -barWidthFraction + progress * (1f + barWidthFraction)

    BoxWithConstraints(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(50)),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(MaterialTheme.colorScheme.surfaceContainer),
        )
        Box(
            modifier = Modifier
                .offset(x = maxWidth * offsetFraction)
                .width(maxWidth * barWidthFraction)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}
