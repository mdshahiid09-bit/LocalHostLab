package com.localhostlab.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import kotlin.random.Random

private data class Particle(
    val initialX: Float,
    val initialY: Float,
    val size: Float,
    val color: Color,
    val speedX: Float,
    val speedY: Float,
    val rotation: Float
)

@Composable
fun ConfettiView(
    modifier: Modifier = Modifier,
    durationMs: Int = 3000,
    onFinished: () -> Unit = {}
) {
    val progress = remember { Animatable(0f) }

    val colors = listOf(
        Color(0xFF38BDF8),
        Color(0xFFA855F7),
        Color(0xFFEC4899),
        Color(0xFFEAB308),
        Color(0xFF22C55E),
        Color(0xFFF97316)
    )

    val particles = remember {
        List(60) {
            Particle(
                initialX = Random.nextFloat(),
                initialY = Random.nextFloat() * -0.3f,
                size = Random.nextFloat() * 12f + 8f,
                color = colors[Random.nextInt(colors.size)],
                speedX = (Random.nextFloat() - 0.5f) * 0.4f,
                speedY = Random.nextFloat() * 0.8f + 0.5f,
                rotation = Random.nextFloat() * 360f
            )
        }
    }

    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = durationMs, easing = LinearEasing)
        )
        onFinished()
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val t = progress.value

        if (t < 1f) {
            particles.forEach { p ->
                val x = (p.initialX + p.speedX * t) * width
                val y = (p.initialY + p.speedY * t) * height
                val alpha = (1f - t * 0.8f).coerceIn(0f, 1f)

                drawRect(
                    color = p.color.copy(alpha = alpha),
                    topLeft = Offset(x, y),
                    size = Size(p.size, p.size * 0.6f)
                )
            }
        }
    }
}
