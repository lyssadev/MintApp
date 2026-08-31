package mint.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import mint.app.ui.theme.RobotoMonoMedium
import mint.app.ui.theme.logo_green
import mint.app.ui.theme.medium_gray

private const val TYPED_TEXT = "int"
private const val LETTER_START_FRAMES = 6
private const val LETTER_FADE_FRAMES = 6
private const val TOTAL_TYPE_FRAMES =
    (TYPED_TEXT.length - 1) * LETTER_START_FRAMES + LETTER_FADE_FRAMES
private const val TYPING_FPS = 60
private const val LOGO_HEIGHT_DP = 64
private const val LOGO_ASPECT_RATIO = 385f / 311f
private const val WORD_FONT_SIZE_SP = 90
private const val WORD_BASELINE_OFFSET_DP = 25

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                Activity.OVERRIDE_TRANSITION_OPEN,
                android.R.anim.fade_in,
                android.R.anim.fade_out,
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
        setContent {
            mint.app.ui.theme.MintTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SplashScreen(
                        onFinished = { navigateToMain() },
                    )
                }
            }
        }
    }

    private fun navigateToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
        finish()
    }
}

@Composable
private fun SplashScreen(onFinished: () -> Unit) {
    val splashState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    var typeFrames by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        val typing = launch {
            val frameNs = 1_000_000_000L / TYPING_FPS
            var last = withFrameNanos { it }
            var frames = 0
            while (frames <= TOTAL_TYPE_FRAMES) {
                withFrameNanos { now ->
                    val elapsed = now - last
                    if (elapsed >= frameNs) {
                        val steps = (elapsed / frameNs).toInt()
                        frames += steps
                        last += steps * frameNs
                    }
                }
                typeFrames = frames
            }
        }
        delay(3000)
        splashState.targetState = false
        delay(500)
        onFinished()
    }

    AnimatedVisibility(
        visibleState = splashState,
        enter = fadeIn(animationSpec = tween(durationMillis = 600, easing = FastOutSlowInEasing)),
        exit = fadeOut(animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing)),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Image(
                    painter = painterResource(R.drawable.logo_m),
                    contentDescription = "Mint logo",
                    modifier = Modifier
                        .height(LOGO_HEIGHT_DP.dp)
                        .aspectRatio(LOGO_ASPECT_RATIO),
                )
                Box {
                    Text(
                        text = TYPED_TEXT,
                        fontFamily = RobotoMonoMedium,
                        fontSize = WORD_FONT_SIZE_SP.sp,
                        color = Color.Transparent,
                    )
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .offset(y = WORD_BASELINE_OFFSET_DP.dp),
                    ) {
                        TYPED_TEXT.forEachIndexed { index, letter ->
                            val letterAlpha = (
                                (typeFrames - index * LETTER_START_FRAMES).toFloat() /
                                    LETTER_FADE_FRAMES
                                ).coerceIn(0f, 1f)
                            Text(
                                text = letter.toString(),
                                fontFamily = RobotoMonoMedium,
                                fontSize = WORD_FONT_SIZE_SP.sp,
                                color = logo_green.copy(alpha = letterAlpha),
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            SplashProgressBar(
                modifier = Modifier.fillMaxWidth(0.5f),
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = medium_gray,
            )
        }
    }
}

@Composable
private fun SplashProgressBar(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "splashProgress")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
        ),
        label = "splashProgressFraction",
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
