package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JarvisPrimary
import com.example.ui.theme.JarvisSecondary

@Composable
fun AudioWaveformView(
  isStreaming: Boolean,
  audioLevel: Float,
  modifier: Modifier = Modifier
) {
  val infiniteTransition = rememberInfiniteTransition(label = "waveform")
  val phase by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(2000, easing = LinearEasing),
      repeatMode = RepeatMode.Restart
    ),
    label = "phase"
  )

  Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
    val width = size.width
    val height = size.height
    val centerY = height / 2f
    val barCount = 32
    val barWidth = width / (barCount * 1.5f)
    val spacing = barWidth * 0.5f

    for (i in 0 until barCount) {
      val x = i * (barWidth + spacing) + spacing
      val distanceFromCenter = kotlin.math.abs(i - barCount / 2f) / (barCount / 2f)
      val heightFactor = if (isStreaming) {
        (1f - distanceFromCenter * 0.5f) * (0.2f + (0.8f * kotlin.math.sin((phase + i * 20).toDouble()).toFloat().let { if (it < 0) -it else it }) * audioLevel)
      } else {
        0.1f + 0.05f * kotlin.math.sin((phase * 0.5f + i * 15).toDouble()).toFloat().let { if (it < 0) -it else it }
      }

      val barHeight = height * heightFactor
      val topY = centerY - barHeight / 2f
      val bottomY = centerY + barHeight / 2f

      drawRoundRect(
        brush = Brush.verticalGradient(
          colors = listOf(JarvisPrimary, JarvisSecondary)
        ),
        topLeft = Offset(x, topY),
        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f, barWidth / 2f)
      )
    }
  }
}
