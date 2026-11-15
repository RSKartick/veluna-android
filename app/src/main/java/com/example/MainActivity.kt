package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.example.ui.VelunaApp
import com.example.ui.enableMaxRefreshRate

class MainActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        enableMaxRefreshRate(this)
        val container = (application as VelunaApplication).container

        setContent {
            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass
            VelunaApp(
                container = container,
                useNavigationRail = widthSizeClass != WindowWidthSizeClass.Compact
            )
        }
    }

    override fun onResume() {
        super.onResume()
        enableMaxRefreshRate(this)
    }
}
