package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.navigation.VelunaTopTab
import com.example.ui.theme.VelunaMotion
import com.example.ui.theme.velunaNavRailItemColors

@Composable
fun VelunaBottomNavBar(
    selectedTab: VelunaTopTab,
    onSelectTab: (VelunaTopTab) -> Unit
) {
    val capsuleShape = RoundedCornerShape(30.dp)
    val pillShape = RoundedCornerShape(24.dp)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            shape = capsuleShape,
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            border = BorderStroke(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.outline.copy(alpha = 0.36f),
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                    )
                )
            ),
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 440.dp)
                .testTag("bottom_nav_bar")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp)
            ) {
                VelunaTopTab.entries.forEach { tab ->
                    val selected = selectedTab == tab
                    val icon = tabIcon(tab, selected)
                    val label = tabLabel(tab)

                    val slotWeight by animateFloatAsState(
                        targetValue = if (selected) 2.05f else 1f,
                        animationSpec = VelunaMotion.expressiveSpatialSpring(),
                        label = "nav_slot_weight_${tab.name}"
                    )
                    val pillColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0f)
                        },
                        animationSpec = VelunaMotion.snappySpring(),
                        label = "nav_pill_bg_${tab.name}"
                    )
                    val contentTint by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = VelunaMotion.snappySpring(),
                        label = "nav_pill_fg_${tab.name}"
                    )
                    val iconScale by animateFloatAsState(
                        targetValue = if (selected) 1.06f else 1f,
                        animationSpec = VelunaMotion.bouncyScaleSpring(),
                        label = "nav_icon_scale_${tab.name}"
                    )

                    Surface(
                        color = pillColor,
                        contentColor = contentTint,
                        shape = pillShape,
                        border = if (selected) {
                            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f))
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(slotWeight)
                            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                            .height(48.dp)
                            .clip(pillShape)
                            .semantics {
                                this.selected = selected
                                role = Role.Tab
                            }
                            .clickable(onClick = { onSelectTab(tab) })
                            .testTag("nav_item_${tab.name.lowercase()}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = if (selected) 10.dp else 4.dp)
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = contentTint,
                                modifier = Modifier
                                    .size(21.dp)
                                    .scale(iconScale)
                            )
                            AnimatedVisibility(
                                visible = selected,
                                enter = expandHorizontally(
                                    animationSpec = VelunaMotion.expressiveSpatialSpring(),
                                    expandFrom = Alignment.Start
                                ) + fadeIn(VelunaMotion.fastFade()),
                                exit = shrinkHorizontally(
                                    animationSpec = VelunaMotion.snappySpring(),
                                    shrinkTowards = Alignment.Start
                                ) + fadeOut(VelunaMotion.fastFade())
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelMedium.copy(
                                            fontWeight = FontWeight.SemiBold
                                        ),
                                        color = contentTint,
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VelunaNavRail(
    selectedTab: VelunaTopTab,
    onSelectTab: (VelunaTopTab) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.testTag("side_nav_rail")
    ) {
        val railColors = velunaNavRailItemColors()
        VelunaTopTab.entries.forEach { tab ->
            val selected = selectedTab == tab
            val icon = tabIcon(tab, selected)
            val label = tabLabel(tab)
            NavigationRailItem(
                selected = selected,
                onClick = { onSelectTab(tab) },
                colors = railColors,
                icon = { Icon(icon, contentDescription = label) },
                label = { Text(label, maxLines = 1, softWrap = false) },
                modifier = Modifier.testTag("rail_item_${tab.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun tabLabel(tab: VelunaTopTab): String = when (tab) {
    VelunaTopTab.HOME -> stringResource(R.string.nav_home)
    VelunaTopTab.SEARCH -> stringResource(R.string.nav_search)
    VelunaTopTab.LIBRARY -> stringResource(R.string.nav_library)
    VelunaTopTab.STATS -> stringResource(R.string.nav_stats)
    VelunaTopTab.SETTINGS -> stringResource(R.string.nav_settings)
}

private fun tabIcon(tab: VelunaTopTab, selected: Boolean) = when (tab) {
    VelunaTopTab.HOME -> if (selected) Icons.Rounded.Home else Icons.Outlined.Home
    VelunaTopTab.SEARCH -> if (selected) Icons.Rounded.Search else Icons.Outlined.Search
    VelunaTopTab.LIBRARY -> if (selected) Icons.Rounded.LibraryMusic else Icons.Outlined.LibraryMusic
    VelunaTopTab.STATS -> if (selected) Icons.Rounded.BarChart else Icons.Outlined.BarChart
    VelunaTopTab.SETTINGS -> if (selected) Icons.Rounded.Settings else Icons.Outlined.Settings
}
