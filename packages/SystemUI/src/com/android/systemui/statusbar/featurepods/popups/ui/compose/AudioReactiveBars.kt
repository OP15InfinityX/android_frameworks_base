/*
 * Copyright (C) 2026 The Android Open Source Project
 * Copyright (C) 2026 Project Infinity X
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.featurepods.popups.ui.compose

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun AudioReactiveBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 1.5.dp,
    maxBarHeight: Dp = 12.dp,
    spacing: Dp = 2.dp,
    startPadding: Dp = 0.dp,
) {
    val totalWidth = startPadding + (barWidth * 5) + (spacing * 4)

    if (!isPlaying) {
        Canvas(
            modifier = modifier.size(width = totalWidth, height = maxBarHeight)
        ) {
            val barWidthPx = barWidth.toPx()
            val maxBarHeightPx = maxBarHeight.toPx()
            val spacingPx = spacing.toPx()
            val startPaddingPx = startPadding.toPx()
            val cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)
            val staticHeights = floatArrayOf(0.3f, 0.5f, 0.7f, 0.5f, 0.3f)

            for (i in 0 until 5) {
                val barHeight = maxBarHeightPx * staticHeights[i]
                val x = startPaddingPx + i * (barWidthPx + spacingPx)
                val y = (maxBarHeightPx - barHeight) / 2f
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, y),
                    size = Size(barWidthPx, barHeight),
                    cornerRadius = cornerRadius
                )
            }
        }
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "audio_reactive_bars")

    val bar1HeightMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar1"
    )

    val bar2HeightMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar2"
    )

    val bar3HeightMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar3"
    )

    val bar4HeightMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar4"
    )

    val bar5HeightMultiplier by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bar5"
    )

    Canvas(
        modifier = modifier.size(width = totalWidth, height = maxBarHeight)
    ) {
        val barWidthPx = barWidth.toPx()
        val maxBarHeightPx = maxBarHeight.toPx()
        val spacingPx = spacing.toPx()
        val startPaddingPx = startPadding.toPx()
        val cornerRadius = CornerRadius(barWidthPx / 2f, barWidthPx / 2f)

        for (i in 0 until 5) {
            val multiplier = when (i) {
                0 -> bar1HeightMultiplier
                1 -> bar2HeightMultiplier
                2 -> bar3HeightMultiplier
                3 -> bar4HeightMultiplier
                else -> bar5HeightMultiplier
            }
            val barHeight = maxBarHeightPx * multiplier
            val x = startPaddingPx + i * (barWidthPx + spacingPx)
            val y = (maxBarHeightPx - barHeight) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidthPx, barHeight),
                cornerRadius = cornerRadius
            )
        }
    }
}
