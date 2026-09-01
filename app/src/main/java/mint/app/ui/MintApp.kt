package mint.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import mint.app.core.update.UpdateUiState
import mint.app.ui.components.UpdateDialog
import mint.app.ui.components.FloatingBottomBar
import mint.app.ui.screens.DownloadsScreen
import mint.app.ui.screens.HomePage
import mint.app.ui.screens.SettingsPage

@Composable
fun MintApp(modifier: Modifier = Modifier) {
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Home) }

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
            FloatingBottomBar(
                selectedScreen = currentScreen,
                onScreenSelected = { currentScreen = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            UpdateDialog()
        }
    }
}
