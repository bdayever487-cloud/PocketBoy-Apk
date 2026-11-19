package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.prefs.EmulatorSettings
import com.example.ui.theme.ConsoleOutline
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceVariant
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.ElectricLimeDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: EmulatorSettings,
    onSelectFolder: () -> Unit,
    onSoundChanged: (Boolean) -> Unit,
    onHapticChanged: (Boolean) -> Unit,
    onFastForwardChanged: (Int) -> Unit,
    onGbPaletteChanged: (Int) -> Unit,
    onPixelFilterChanged: (Boolean) -> Unit,
    onShowFpsChanged: (Boolean) -> Unit,
    onOpacityChanged: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = stringResource(R.string.nav_settings),
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary
            )
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_storage_section)) {
                Text(
                    text = settings.romFolderLabel ?: stringResource(R.string.folder_not_linked),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (settings.romFolderLabel != null) ElectricLime else TextSecondary
                )
                Button(
                    onClick = onSelectFolder,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ElectricLime,
                        contentColor = ElectricLimeDark
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("settings_change_folder_btn")
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (settings.romFolderLabel != null) stringResource(R.string.action_change_folder)
                        else stringResource(R.string.action_select_folder)
                    )
                }
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_audio_haptics)) {
                SettingSwitchRow(
                    title = stringResource(R.string.setting_sound_title),
                    subtitle = stringResource(R.string.setting_sound_desc),
                    checked = settings.soundEnabled,
                    onCheckedChange = onSoundChanged,
                    testTag = "switch_sound"
                )
                SettingSwitchRow(
                    title = stringResource(R.string.setting_haptic_title),
                    subtitle = stringResource(R.string.setting_haptic_desc),
                    checked = settings.hapticEnabled,
                    onCheckedChange = onHapticChanged,
                    testTag = "switch_haptic"
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_video_section)) {
                Text(
                    text = stringResource(R.string.setting_gb_palette_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val palettes = listOf("DMG Verde", "Pocket Cinza", "LCD Claro")
                    palettes.forEachIndexed { idx, label ->
                        FilterChip(
                            selected = settings.gbPalettePreset == idx,
                            onClick = { onGbPaletteChanged(idx) },
                            label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricLime,
                                selectedLabelColor = ElectricLimeDark,
                                containerColor = ConsoleSurfaceVariant,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.setting_fast_forward_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2, 3).forEach { mult ->
                        FilterChip(
                            selected = settings.fastForwardMultiplier == mult,
                            onClick = { onFastForwardChanged(mult) },
                            label = { Text("${mult}x", style = MaterialTheme.typography.labelLarge) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = ElectricLime,
                                selectedLabelColor = ElectricLimeDark,
                                containerColor = ConsoleSurfaceVariant,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }

                SettingSwitchRow(
                    title = stringResource(R.string.setting_pixel_filter_title),
                    subtitle = stringResource(R.string.setting_pixel_filter_desc),
                    checked = settings.pixelFilter,
                    onCheckedChange = onPixelFilterChanged,
                    testTag = "switch_pixel_filter"
                )

                SettingSwitchRow(
                    title = stringResource(R.string.setting_fps_title),
                    subtitle = "Exibir taxa de quadros no topo da tela do emulador",
                    checked = settings.showFps,
                    onCheckedChange = onShowFpsChanged,
                    testTag = "switch_show_fps"
                )
            }
        }

        item {
            SettingsCard(title = stringResource(R.string.settings_controls_section)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.setting_opacity_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary
                    )
                    Text(
                        text = "${(settings.controllerOpacity * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricLime
                    )
                }
                Slider(
                    value = settings.controllerOpacity,
                    onValueChange = onOpacityChanged,
                    valueRange = 0.4f..1.0f,
                    colors = SliderDefaults.colors(
                        thumbColor = ElectricLime,
                        activeTrackColor = ElectricLime
                    )
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = ConsoleSurface),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, ConsoleOutline, RoundedCornerShape(18.dp))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = ElectricLime
            )
            content()
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ElectricLimeDark,
                checkedTrackColor = ElectricLime
            ),
            modifier = Modifier.testTag(testTag)
        )
    }
}
