package com.example.ui.screens.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.VelunaUserPrefs
import com.example.model.ThemeMode
import com.example.model.VelunaThemePreset
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.parseHexColorOrNull
import com.example.ui.theme.velunaFilterChipBorder
import com.example.ui.theme.velunaFilterChipColors
import com.example.ui.theme.velunaSliderColors
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AppearanceSection(
    prefs: VelunaUserPrefs,
    onSetThemePreset: (VelunaThemePreset) -> Unit,
    onSetThemeMode: (ThemeMode) -> Unit,
    onSetCustomAccent: (String?) -> Unit,
    onSetAdaptToArtwork: (Boolean) -> Unit,
    onSetClassicPlayerLayout: (Boolean) -> Unit,
    onSetCornerRadius: (Int) -> Unit
) {
    SettingsCard(title = stringResource(R.string.settings_card_appearance)) {
        val previewBg by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.background,
            animationSpec = VelunaMotion.snappySpring(),
            label = "preview_bg"
        )
        val previewPrimary by animateColorAsState(
            targetValue = MaterialTheme.colorScheme.primary,
            animationSpec = VelunaMotion.snappySpring(),
            label = "preview_primary"
        )

        Surface(
            color = previewBg,
            contentColor = MaterialTheme.colorScheme.onBackground,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = prefs.themePreset.label,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Corner radius ${prefs.cornerRadiusDp}dp • ${prefs.themeMode.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(previewPrimary)
                )
            }
        }

        val chipColors = velunaFilterChipColors()
        Text(stringResource(R.string.settings_theme_mode), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            ThemeMode.entries.forEach { mode ->
                val selected = prefs.themeMode == mode
                FilterChip(
                    selected = selected,
                    onClick = { onSetThemeMode(mode) },
                    colors = chipColors,
                    border = velunaFilterChipBorder(selected),
                    label = { Text(mode.label, maxLines = 1, softWrap = false) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("settings_theme_mode_${mode.name.lowercase()}")
                )
            }
        }

        Text(stringResource(R.string.settings_theme_preset), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            VelunaThemePreset.entries.forEach { preset ->
                val selected = prefs.themePreset == preset
                FilterChip(
                    selected = selected,
                    onClick = { onSetThemePreset(preset) },
                    colors = chipColors,
                    border = velunaFilterChipBorder(selected),
                    label = { Text(preset.label, maxLines = 1, softWrap = false) },
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("settings_preset_${preset.id}")
                )
            }
        }

        Text(stringResource(R.string.settings_accent_swatch), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        val swatches = listOf<String?>(null, "#EA580C", "#E11D48", "#8B5CF6", "#0EA5E9", "#10B981", "#F59E0B")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            swatches.forEach { hex ->
                val c = parseHexColorOrNull(hex) ?: MaterialTheme.colorScheme.primary
                val selected = prefs.customAccentHex == hex
                val swatchScale by animateFloatAsState(
                    targetValue = if (selected) 1.08f else 1f,
                    animationSpec = VelunaMotion.bouncyScaleSpring(),
                    label = "swatch_scale"
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable { onSetCustomAccent(hex) }
                        .testTag("settings_accent_${hex ?: "default"}")
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .scale(swatchScale)
                            .clip(CircleShape)
                            .background(if (hex == null) MaterialTheme.colorScheme.surfaceContainerHighest else c)
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (hex == null) {
                            Text(
                                text = "A",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        SettingSwitchRow(
            title = stringResource(R.string.settings_adapt_artwork),
            checked = prefs.adaptToArtwork,
            onCheckedChange = onSetAdaptToArtwork,
            tag = "settings_adapt_art"
        )

        SettingSwitchRow(
            title = stringResource(R.string.settings_classic_player_layout),
            checked = prefs.classicPlayerLayout,
            onCheckedChange = onSetClassicPlayerLayout,
            tag = "settings_classic_player_switch"
        )

        Text(
            text = stringResource(R.string.settings_corner_radius, prefs.cornerRadiusDp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Slider(
            value = prefs.cornerRadiusDp.toFloat(),
            onValueChange = { onSetCornerRadius(it.roundToInt()) },
            valueRange = 4f..24f,
            colors = velunaSliderColors(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("settings_corner_radius_slider")
        )
    }
}
