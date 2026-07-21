// SPDX-FileCopyrightText: 2026 深圳哇蓝文化科技有限公司 (ShenZhen wowblue culture and technology CO.,LTD.)
// SPDX-License-Identifier: AGPL-3.0-or-later

package com.mengpaw.shell.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mengpaw.design.tokens.ArcoColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * WowBlue splash screen with playful staggered animations.
 *
 * "WOW" letters pop in one-by-one with spring physics,
 * "BLUE" slides up, particles orbit, then transitions out.
 */
@Composable
fun WowBlueSplash(onFinished: () -> Unit) {
    val bgBlue = ArcoColors.Blue6  // brand primary

    // ── Animation state machines ──
    var phase by remember { mutableIntStateOf(0) }  // 0=enter, 1=hold, 2=exit

    // Per-letter scales for W·O·W (3 letters, staggered)
    val w1Scale = remember { Animatable(0f) }
    val oScale = remember { Animatable(0f) }
    val w2Scale = remember { Animatable(0f) }
    val blueAlpha = remember { Animatable(0f) }
    val blueOffsetY = remember { Animatable(60f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val exitAlpha = remember { Animatable(1f) }

    // ── Orbiting dot particles ──
    val particleCount = 12
    val orbitProgress = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        // Phase 0 — staggered entrance
        // Orbit starts spinning immediately
        launch {
            orbitProgress.animateTo(
                1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                )
            )
        }

        // W·O·W letters spring in with stagger
        launch {
            w1Scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
        }
        delay(90)
        launch {
            oScale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
        }
        delay(90)
        launch {
            w2Scale.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 400f))
        }

        // BLUE slides up + fades in
        delay(80)
        launch { blueAlpha.animateTo(1f, tween(500, easing = FastOutSlowInEasing)) }
        launch { blueOffsetY.animateTo(0f, tween(500, easing = FastOutSlowInEasing)) }

        // Subtitle
        delay(200)
        subtitleAlpha.animateTo(1f, tween(600))

        // Phase 1 — hold
        phase = 1
        delay(900)

        // Phase 2 — exit
        phase = 2
        exitAlpha.animateTo(0f, tween(350))
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(bgBlue)
            .alpha(exitAlpha.value),
        contentAlignment = Alignment.Center
    ) {
        // ── Orbiting particles ──
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            val orbitRx = size.width * 0.28f
            val orbitRy = size.height * 0.16f

            for (i in 0 until particleCount) {
                val baseAngle = (2.0 * PI * i / particleCount).toFloat()
                val angle = baseAngle + orbitProgress.value * 2f * PI.toFloat()
                val px = cx + orbitRx * cos(angle)
                val py = cy + orbitRy * sin(angle) * 0.55f

                // Dot size pulses with orbit position
                val dotAlpha = 0.25f + 0.35f * ((cos(angle) + 1f) / 2f)
                val dotR = 3f + 3f * ((sin(angle * 2) + 1f) / 2f)

                drawCircle(
                    color = Color.White.copy(alpha = dotAlpha),
                    radius = dotR,
                    center = Offset(px, py)
                )
            }

            // Inner ring of smaller dots, counter-rotating
            for (i in 0 until 8) {
                val baseAngle = (2.0 * PI * i / 8).toFloat()
                val angle = baseAngle - orbitProgress.value * 1.7f * PI.toFloat()
                val r = minOf(orbitRx, orbitRy) * 0.45f
                val px = cx + r * cos(angle)
                val py = cy + r * sin(angle) * 0.75f
                drawCircle(
                    color = Color.White.copy(alpha = 0.15f + 0.2f * ((cos(angle + 1f) + 1f) / 2f)),
                    radius = 2f,
                    center = Offset(px, py)
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // ── W O W  (three letters, staggered spring) ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                LetterBox("W", scale = w1Scale.value, size = 56.sp)
                Spacer(Modifier.width(4.dp))
                LetterBox("O", scale = oScale.value, size = 56.sp)
                Spacer(Modifier.width(4.dp))
                LetterBox("W", scale = w2Scale.value, size = 56.sp)
            }

            Spacer(Modifier.height(2.dp))

            // ── B L U E  (slides up as a unit) ──
            Box(
                Modifier
                    .offset(y = blueOffsetY.value.dp)
                    .alpha(blueAlpha.value)
            ) {
                Text(
                    "BLUE",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 10.sp
                )
            }

            Spacer(Modifier.height(24.dp))

            // ── Subtitle ──
            Box(Modifier.alpha(subtitleAlpha.value)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "MengPaw",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 4.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "深圳哇蓝文化",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        letterSpacing = 3.sp
                    )
                }
            }
        }
    }
}

/** A single letter with spring scale animation. */
@Composable
private fun LetterBox(letter: String, scale: Float, size: androidx.compose.ui.unit.TextUnit) {
    Box(
        Modifier
            .scale(scale)
            .size(56.dp)
            .clip(CircleShape)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        Text(
            letter,
            color = ArcoColors.Blue6,
            fontSize = size,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center
        )
    }
}
