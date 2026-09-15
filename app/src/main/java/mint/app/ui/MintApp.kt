package mint.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import mint.app.core.update.UpdateUiState
import mint.app.ui.components.UpdateDialog
import mint.app.resolution.EngineSetup
import mint.app.ui.components.FloatingBottomBar
import mint.app.ui.screens.DownloadsScreen
import mint.app.ui.screens.HomePage
import mint.app.ui.screens.InitScreen
import mint.app.ui.screens.SettingsPage

@Composable
fun MintApp(modifier: Modifier = Modifier) {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Home) }
    val showInit = EngineSetup.setupRequired && !EngineSetup.ready

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.screenHeightDp < configuration.screenWidthDp

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                while (true) {
                    UpdateUiState.autoCheck(context)
                    delay(60 * 60 * 1000L)
                }
            }

            AnimatedContent(
                targetState = showInit,
                transitionSpec = {
                    if (targetState) {
                        fadeIn(animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing))
                    } else {
                        fadeIn(animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing)) +
                            scaleOut(targetScale = 0.96f, animationSpec = tween(durationMillis = 500, easing = FastOutSlowInEasing))
                    }
                },
                label = "initSwitch",
            ) { init ->
                if (init) {
                    InitScreen(modifier = Modifier.fillMaxSize())
                } else {
                    if (isLandscape) {
                        LandscapeDualPane(
                            currentScreen = currentScreen,
                            onScreenSelected = { currentScreen = it },
                        )
                    } else {
                        Crossfade(
                            targetState = currentScreen,
                            animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                            label = "screenSwitch",
                        ) { screen ->
                            when (screen) {
                                Screen.Home -> HomePage(modifier = Modifier.fillMaxSize())
                                Screen.Downloads -> DownloadsScreen(modifier = Modifier.fillMaxSize())
                                Screen.Settings -> SettingsPage(modifier = Modifier.fillMaxSize())
                            }
                        }
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = !showInit,
                enter = fadeIn(animationSpec = tween(durationMillis = 450, easing = FastOutSlowInEasing)),
                exit = fadeOut(animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                FloatingBottomBar(
                    selectedScreen = currentScreen,
                    onScreenSelected = { currentScreen = it },
                )
            }
            UpdateDialog()
        }
    }
}

@Composable
private fun LandscapeDualPane(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
) {
    val sideVisible = currentScreen != Screen.Home
    val homeWeight by animateFloatAsState(
        targetValue = if (sideVisible) 1f else 1.6f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "homePaneWeight",
    )
    val sideWeight by animateFloatAsState(
        targetValue = if (sideVisible) 1f else 0.001f,
        animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
        label = "sidePaneWeight",
    )

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(homeWeight)
                .fillMaxHeight(),
        ) {
            HomePage(
                modifier = Modifier.fillMaxSize(),
                compact = sideVisible,
            )
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = sideVisible,
            enter = androidx.compose.animation.expandHorizontally(
                animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            ) + androidx.compose.animation.fadeIn(
                animationSpec = tween(durationMillis = 400),
            ),
            exit = androidx.compose.animation.shrinkHorizontally(
                animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
            ) + androidx.compose.animation.fadeOut(
                animationSpec = tween(durationMillis = 400),
            ),
        ) {
            Column(
                modifier = Modifier.fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(160.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(sideWeight)
                .fillMaxHeight(),
        ) {
            Crossfade(
                targetState = currentScreen,
                animationSpec = tween(durationMillis = 400, easing = FastOutSlowInEasing),
                label = "sideScreenSwitch",
                modifier = Modifier.fillMaxSize(),
            ) { screen ->
                when (screen) {
                    Screen.Home -> Spacer(modifier = Modifier.fillMaxSize())
                    Screen.Downloads -> DownloadsScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    )
                    Screen.Settings -> SettingsPage(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}
