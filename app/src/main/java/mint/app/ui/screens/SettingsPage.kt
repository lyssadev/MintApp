package mint.app.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.lyxnx.compose.ui.tablericons.TablerIcons
import io.github.lyxnx.compose.ui.tablericons.outline.BrandGithub
import io.github.lyxnx.compose.ui.tablericons.outline.BrandInstagram
import io.github.lyxnx.compose.ui.tablericons.outline.BrandPinterest
import io.github.lyxnx.compose.ui.tablericons.outline.BrandTiktok
import io.github.lyxnx.compose.ui.tablericons.outline.Check
import io.github.lyxnx.compose.ui.tablericons.outline.ChevronRight
import io.github.lyxnx.compose.ui.tablericons.outline.Language
import io.github.lyxnx.compose.ui.tablericons.outline.Folder
import io.github.lyxnx.compose.ui.tablericons.outline.Link
import io.github.lyxnx.compose.ui.tablericons.outline.Moon
import io.github.lyxnx.compose.ui.tablericons.outline.Palette
import io.github.lyxnx.compose.ui.tablericons.outline.Refresh
import io.github.lyxnx.compose.ui.tablericons.outline.Star
import io.github.lyxnx.compose.ui.tablericons.outline.PlugConnectedX
import io.github.lyxnx.compose.ui.tablericons.outline.X
import mint.app.BuildConfig
import mint.app.R
import mint.app.connection.InstagramLoginActivity
import mint.app.connection.PinterestLoginActivity
import mint.app.connection.TikTokLoginActivity
import mint.app.core.prefs.AppLocale
import mint.app.core.prefs.ConnectionPreferences
import mint.app.core.prefs.DownloadPreferences
import mint.app.core.prefs.LanguagePreferences
import mint.app.core.update.UpdateUiState
import mint.app.resolution.impl.InstagramResolver
import mint.app.ui.theme.ThemeController
import mint.app.ui.theme.ThemeMode
import mint.app.ui.theme.ThemePreset
import mint.app.ui.theme.ThemePresets

private const val REPO_URL = "https://github.com/lyssadev/MintApp"

@Composable
fun SettingsPage(modifier: Modifier = Modifier) {
    var showThemePicker by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.settings_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppearanceSection(onOpenThemePicker = { showThemePicker = true })
        LanguageSection()
        ConnectionsSection()
        DownloadsSection()
        AboutSection()
        Spacer(modifier = Modifier.height(120.dp))
    }

    if (showThemePicker) {
        ThemePickerDialog(onDismiss = { showThemePicker = false })
    }
}

@Composable
private fun AppearanceSection(onOpenThemePicker: () -> Unit) {
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val currentPreset = ThemePresets.byId[ThemeController.presetId]
        ?: ThemePresets.byId.getValue(ThemePresets.DEFAULT_ID)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {            Text(
                text = stringResource(R.string.settings_appearance),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(
                            if (ThemeController.dynamicColor || ThemeController.amoled) {
                                0.45f
                            } else {
                                1f
                            }
                        )
                        .then(
                            if (ThemeController.dynamicColor || ThemeController.amoled) {
                                Modifier
                            } else {
                                Modifier.clickable(onClick = onOpenThemePicker)
                            }
                        ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.Outline.Palette,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                ThemeController.dynamicColor -> stringResource(R.string.settings_theme_managed_material_you)
                                ThemeController.amoled -> stringResource(R.string.settings_theme_disable_amoled_first)
                                else -> "${currentPreset.family} · ${currentPreset.name}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        imageVector = TablerIcons.Outline.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (currentPreset.supportsBothModes) {
                            stringResource(R.string.settings_mode)
                        } else {
                            stringResource(R.string.settings_mode_fixed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ThemeModeSegmented(enabled = currentPreset.supportsBothModes && !ThemeController.amoled)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (ThemeController.amoled) 0.45f else 1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.Outline.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_material_you),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (ThemeController.amoled) {
                                stringResource(R.string.settings_material_you_disable_amoled)
                            } else if (dynamicSupported) {
                                stringResource(R.string.settings_material_you_wallpaper)
                            } else {
                                stringResource(R.string.settings_material_you_requires)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ThemeController.dynamicColor && dynamicSupported,
                        onCheckedChange = { checked -> ThemeController.updateDynamicColor(checked) },
                        enabled = dynamicSupported && !ThemeController.amoled,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.Outline.Moon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_amoled),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_amoled_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = ThemeController.amoled,
                        onCheckedChange = { checked -> ThemeController.updateAmoled(checked) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeModeSegmented(enabled: Boolean) {
    val options = listOf(
        ThemeMode.LIGHT to stringResource(R.string.settings_mode_light),
        ThemeMode.DARK to stringResource(R.string.settings_mode_dark),
        ThemeMode.SYSTEM to stringResource(R.string.settings_mode_system),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (mode, label) ->
            val selected = ThemeController.mode == mode
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        }
                    )
                    .clickable(enabled = enabled) { ThemeController.updateMode(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun ThemePickerDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_theme),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_presets_available, ThemePresets.all.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = TablerIcons.Outline.X,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.height(440.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(ThemePresets.all, key = { it.id }) { preset ->
                        ThemePresetCard(
                            preset = preset,
                            selected = preset.id == ThemeController.presetId,
                            onClick = {
                                ThemeController.updatePreset(preset.id)
                                onDismiss()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThemePresetCard(
    preset: ThemePreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val display = preset.light ?: preset.dark
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = if (selected) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThemeSwatch(display.bg)
                ThemeSwatch(display.panelHigh)
                ThemeSwatch(display.accent)
                ThemeSwatch(display.alt)
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preset.family,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = preset.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (preset.light != null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                ) {
                    Text(
                        text = if (preset.light != null) "LIGHT + DARK" else "DARK",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (preset.light != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (selected) {
                    Icon(
                        imageVector = TablerIcons.Outline.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    val activity = context as? Activity
    var pickerOpen by remember { mutableStateOf(false) }
    val current = LanguagePreferences.language(context)
    val currentLabel = if (current == LanguagePreferences.SYSTEM) {
        stringResource(R.string.language_system)
    } else {
        AppLocale.SUPPORTED.firstOrNull { it.first == current }?.second?.displayName ?: current
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.settings_language),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickerOpen = true }
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = TablerIcons.Outline.Language,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_language),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = TablerIcons.Outline.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }

    if (pickerOpen) {
        AlertDialog(
            onDismissRequest = { pickerOpen = false },
            title = { Text(stringResource(R.string.settings_language)) },
            text = {
                Column {
                    AppLocale.SUPPORTED.forEach { (tag, locale) ->
                        val label = if (locale == null) {
                            stringResource(R.string.language_system)
                        } else {
                            locale.displayName
                        }
                        val selected = tag == current
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    pickerOpen = false
                                    if (tag != current) {
                                        LanguagePreferences.setLanguage(context, tag)
                                        activity?.recreate()
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (selected) {
                                Icon(
                                    imageVector = TablerIcons.Outline.Check,
                                    contentDescription = stringResource(R.string.cd_selected),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                Spacer(modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {},
        )
    }
}

@Composable
private fun ConnectionsSection() {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {            Text(
                text = stringResource(R.string.settings_connections),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        ConnectionCard(
            title = stringResource(R.string.platform_instagram),
            subtitle = { linked ->
                if (linked) {
                    context.getString(R.string.settings_connection_linked, context.getString(R.string.platform_instagram))
                } else {
                    context.getString(R.string.settings_connection_login_hint)
                }
            },
            icon = TablerIcons.Outline.BrandInstagram,
            isLinked = { ConnectionPreferences.isInstagramLinked(context) },
            loginActivityClass = InstagramLoginActivity::class.java,
            onClear = {
                ConnectionPreferences.clearInstagram(context)
                InstagramResolver.clearSession()
            },
            unlinkMessage = R.string.settings_connection_unlinked_toast to R.string.platform_instagram,
        )
        ConnectionCard(
            title = stringResource(R.string.platform_tiktok),
            subtitle = { linked ->
                if (linked) {
                    context.getString(R.string.settings_connection_linked, context.getString(R.string.platform_tiktok))
                } else {
                    context.getString(R.string.settings_connection_login_hint)
                }
            },
            icon = TablerIcons.Outline.BrandTiktok,
            isLinked = { ConnectionPreferences.isTikTokLinked(context) },
            loginActivityClass = TikTokLoginActivity::class.java,
            onClear = {
                ConnectionPreferences.clearTikTok(context)
            },
            unlinkMessage = R.string.settings_connection_unlinked_toast to R.string.platform_tiktok,
        )
        ConnectionCard(
            title = stringResource(R.string.platform_pinterest),
            subtitle = { linked ->
                if (linked) {
                    context.getString(R.string.settings_connection_linked, context.getString(R.string.platform_pinterest))
                } else {
                    context.getString(R.string.settings_connection_login_hint)
                }
            },
            icon = TablerIcons.Outline.BrandPinterest,
            isLinked = { ConnectionPreferences.isPinterestLinked(context) },
            loginActivityClass = PinterestLoginActivity::class.java,
            onClear = {
                ConnectionPreferences.clearPinterest(context)
            },
            unlinkMessage = R.string.settings_connection_unlinked_toast to R.string.platform_pinterest,
        )
    }
}

@Composable
private fun ConnectionCard(
    title: String,
    subtitle: (Boolean) -> String,
    icon: ImageVector,
    isLinked: () -> Boolean,
    loginActivityClass: Class<*>,
    onClear: () -> Unit,
    unlinkMessage: Pair<Int, Int>,
) {
    val context = LocalContext.current
    var linked by remember { mutableStateOf(isLinked()) }

    val loginLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (it.resultCode == Activity.RESULT_OK) {
            linked = isLinked()
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = subtitle(linked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (linked) {
                Surface(
                    onClick = {
                        onClear()
                        linked = false
                        Toast.makeText(
                            context,
                            context.getString(unlinkMessage.first, context.getString(unlinkMessage.second)),
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = TablerIcons.Outline.PlugConnectedX,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.action_unlink),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            } else {
                Surface(
                    onClick = {
                        loginLauncher.launch(Intent(context, loginActivityClass))
                    },
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = TablerIcons.Outline.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = stringResource(R.string.action_link),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadsSection() {
    val context = LocalContext.current
    var subfolder by remember { mutableStateOf(DownloadPreferences.subfolder(context)) }
    var videoDir by remember { mutableStateOf(DownloadPreferences.videoDir(context)) }
    var audioDir by remember { mutableStateOf(DownloadPreferences.audioDir(context)) }
    var imageDir by remember { mutableStateOf(DownloadPreferences.imageDir(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {            Text(
                text = stringResource(R.string.settings_downloads_section),
                style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.Outline.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(R.string.settings_download_folder),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.settings_download_folder_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                OutlinedTextField(
                    value = subfolder,
                    onValueChange = { new ->
                        subfolder = new
                        DownloadPreferences.setSubfolder(context, new)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(text = stringResource(R.string.settings_subfolder_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    text = stringResource(R.string.settings_videos_folder),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = videoDir,
                    onValueChange = { new ->
                        videoDir = new
                        DownloadPreferences.setVideoDir(context, new)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(text = stringResource(R.string.settings_videos_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Text(
                    text = stringResource(R.string.settings_audios_folder),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = audioDir,
                    onValueChange = { new ->
                        audioDir = new
                        DownloadPreferences.setAudioDir(context, new)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(text = stringResource(R.string.settings_audios_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Text(
                    text = stringResource(R.string.settings_images_folder),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = imageDir,
                    onValueChange = { new ->
                        imageDir = new
                        DownloadPreferences.setImageDir(context, new)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(text = stringResource(R.string.settings_images_placeholder)) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                )
                Text(
                    text = buildString {
                        val base = subfolder.trim('/')
                        val vid = videoDir.trim('/')
                        val aud = audioDir.trim('/')
                        val img = imageDir.trim('/')
                        append("/sdcard/Download/")
                        append(if (base.isBlank()) "MintApp" else base)
                        append('/')
                        append(if (vid.isBlank()) "videos" else vid)
                        append(" · ")
                        append(if (aud.isBlank()) "audios" else aud)
                        append(" · ")
                        append(if (img.isBlank()) "images" else img)
                        append("/")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {            Text(
                text = stringResource(R.string.settings_about),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Image(
                    painter = painterResource(R.drawable.logo_m),
                    contentDescription = stringResource(R.string.cd_logo),
                    modifier = Modifier
                        .size(44.dp)
                        .aspectRatio(385f / 311f),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column {
                SettingsActionRow(
                    icon = TablerIcons.Outline.BrandGithub,
                    title = stringResource(R.string.settings_repository),
                    subtitle = "github.com/lyssadev/MintApp",
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL)))
                        }
                    },
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 52.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                SettingsActionRow(
                    icon = TablerIcons.Outline.Refresh,
                    title = stringResource(R.string.settings_check_updates),
                    subtitle = stringResource(R.string.settings_check_updates_subtitle, BuildConfig.VERSION_NAME),
                    onClick = {
                        UpdateUiState.manualCheck(context)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingsActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = TablerIcons.Outline.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}
