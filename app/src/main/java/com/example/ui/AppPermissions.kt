package com.example.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

fun enableMaxRefreshRate(activity: ComponentActivity) {
    runCatching {
        val activeDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay
        } ?: return

        val currentMode = activeDisplay.mode
        val sameResModes = activeDisplay.supportedModes.filter {
            it.physicalWidth == currentMode.physicalWidth &&
                it.physicalHeight == currentMode.physicalHeight
        }
        val bestMode = (sameResModes.ifEmpty { activeDisplay.supportedModes.toList() })
            .maxByOrNull { it.refreshRate } ?: currentMode

        val attrs = activity.window.attributes
        attrs.preferredDisplayModeId = bestMode.modeId
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attrs.preferredRefreshRate = bestMode.refreshRate
        }
        activity.window.attributes = attrs

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            activity.window.frameRateBoostOnTouchEnabled = true
            activity.window.isFrameRatePowerSavingsBalanced = false
            activity.window.decorView.requestedFrameRate = bestMode.refreshRate
        }
    }
}

@Composable
fun rememberNotificationPermissionRequester(): () -> Unit {
    val context = LocalContext.current
    var requestedNotificationPerm by remember { mutableStateOf(false) }
    val notificationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    return remember(context, notificationPermLauncher) {
        {
            if (!requestedNotificationPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    requestedNotificationPerm = true
                    notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
    }
}
