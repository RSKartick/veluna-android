package com.example.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * Motion tokens tuned for high-refresh-rate displays (90Hz / 120Hz / 144Hz+).
 * Physics-based springs naturally interpolate per hardware VSYNC with zero fixed-FPS stepping.
 */
object VelunaMotion {
    fun <T> expressiveSpatialSpring(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = 0.90f,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = visibilityThreshold
        )

    fun expressiveIntOffsetSpring(): FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = 0.92f,
            stiffness = Spring.StiffnessMedium,
            visibilityThreshold = IntOffset(1, 1)
        )

    fun <T> snappySpring(visibilityThreshold: T? = null): FiniteAnimationSpec<T> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
            visibilityThreshold = visibilityThreshold
        )

    fun snappyIntOffsetSpring(): FiniteAnimationSpec<IntOffset> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh,
            visibilityThreshold = IntOffset(1, 1)
        )

    fun bouncyScaleSpring(): FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = 0.72f,
            stiffness = Spring.StiffnessMedium
        )

    fun smoothFloatSpring(): FiniteAnimationSpec<Float> =
        spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )

    fun fastFade(): FiniteAnimationSpec<Float> = tween(durationMillis = 120)
}
