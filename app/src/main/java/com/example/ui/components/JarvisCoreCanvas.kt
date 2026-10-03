package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.example.model.JarvisState
import com.example.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun JarvisCoreCanvas(
  state: JarvisState,
  modifier: Modifier = Modifier,
  sizeDp: Int = 260
) {
  val infiniteTransition = rememberInfiniteTransition(label = "jarvis_core")

  val rotationAngle1 by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(8000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "rot1"
  )

  val rotationAngle2 by infiniteTransition.animateFloat(
    initialValue = 360f,
    targetValue = 0f,
    animationSpec = infiniteRepeatable(
      animation = tween(12000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "rot2"
  )

  val pulseScale by infiniteTransition.animateFloat(
    initialValue = 0.92f,
    targetValue = 1.08f,
    animationSpec = infiniteRepeatable(
      animation = tween(1500, easing = FastOutSlowInEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "pulse"
  )

  val coreColor = when (state) {
    JarvisState.READY -> JarvisPrimary
    JarvisState.LISTENING -> JarvisSecondary
    JarvisState.THINKING -> Color(0xFFBD00FF) // Purple
    JarvisState.PROCESSING -> Color(0xFF00FFD1) // Cyan-green
    JarvisState.SPEAKING -> Color(0xFF00FF9D) // Neon green
    JarvisState.INTERRUPTED -> Color(0xFFFFB020) // Amber / Warning
    JarvisState.EXECUTING -> Color(0xFF00FF9D)
    JarvisState.VERIFYING -> Color(0xFF00E5FF)
    JarvisState.COMPLETED -> Color(0xFF00FF9D)
    JarvisState.ERROR -> JarvisError
    JarvisState.OFFLINE -> Color(0xFF64748B)
  }

  Box(
    modifier = modifier.size(sizeDp.dp),
    contentAlignment = Alignment.Center
  ) {
    Canvas(modifier = Modifier.matchParentSize()) {
      val center = Offset(size.width / 2f, size.height / 2f)
      val radius = size.minDimension / 2f * 0.85f * (if (state == JarvisState.LISTENING || state == JarvisState.SPEAKING) pulseScale else 1f)

      // 1. Outer Atmospheric Glow
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(coreColor.copy(alpha = 0.25f), Color.Transparent),
          center = center,
          radius = radius * 1.3f
        ),
        radius = radius * 1.3f,
        center = center
      )

      // 2. Outer Technical Dashed / Segmented Ring
      drawCircle(
        color = coreColor.copy(alpha = 0.3f),
        radius = radius,
        center = center,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f))
      )

      // 3. Rotating Inner Tech Ring 1
      drawContext.canvas.save()
      drawContext.canvas.rotate(rotationAngle1, center.x, center.y)
      drawCircle(
        color = coreColor.copy(alpha = 0.5f),
        radius = radius * 0.8f,
        center = center,
        style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 40f, 10f, 20f), 0f))
      )
      for (i in 0..3) {
        val angle = Math.toRadians((i * 90).toDouble())
        val nodeX = center.x + (radius * 0.8f * cos(angle)).toFloat()
        val nodeY = center.y + (radius * 0.8f * sin(angle)).toFloat()
        drawCircle(color = coreColor, radius = 4.dp.toPx(), center = Offset(nodeX, nodeY))
      }
      drawContext.canvas.restore()

      // 4. Rotating Inner Tech Ring 2 (Counter-rotation)
      drawContext.canvas.save()
      drawContext.canvas.rotate(rotationAngle2, center.x, center.y)
      drawCircle(
        color = JarvisSecondary.copy(alpha = 0.4f),
        radius = radius * 0.6f,
        center = center,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 15f), 0f))
      )
      drawContext.canvas.restore()

      // 5. Solid HUD Ring with Gradient
      drawCircle(
        brush = Brush.sweepGradient(
          listOf(coreColor.copy(alpha = 0.1f), coreColor, coreColor.copy(alpha = 0.1f)),
          center
        ),
        radius = radius * 0.45f,
        center = center,
        style = Stroke(width = 3.dp.toPx())
      )

      // 6. Center Luminous Core
      drawCircle(
        brush = Brush.radialGradient(
          colors = listOf(Color.White, coreColor, JarvisBackground),
          center = center,
          radius = radius * 0.3f
        ),
        radius = radius * 0.3f,
        center = center
      )

      // 7. Micro crosshairs / technical axis lines
      drawLine(
        color = coreColor.copy(alpha = 0.3f),
        start = Offset(center.x - radius * 1.1f, center.y),
        end = Offset(center.x - radius * 0.9f, center.y),
        strokeWidth = 1.dp.toPx()
      )
      drawLine(
        color = coreColor.copy(alpha = 0.3f),
        start = Offset(center.x + radius * 0.9f, center.y),
        end = Offset(center.x + radius * 1.1f, center.y),
        strokeWidth = 1.dp.toPx()
      )
      drawLine(
        color = coreColor.copy(alpha = 0.3f),
        start = Offset(center.x, center.y - radius * 1.1f),
        end = Offset(center.x, center.y - radius * 0.9f),
        strokeWidth = 1.dp.toPx()
      )
      drawLine(
        color = coreColor.copy(alpha = 0.3f),
        start = Offset(center.x, center.y + radius * 0.9f),
        end = Offset(center.x, center.y + radius * 1.1f),
        strokeWidth = 1.dp.toPx()
      )
    }
  }
}
