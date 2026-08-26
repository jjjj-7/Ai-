package dev.autopilot.terminal.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val BootBg = Color(0xFF07070D)

private val bootLines = listOf(
    "$ autopilot --boot",
    "[ ok ] termux bootstrap mounted",
    "[ ok ] pty channel online",
    "[ ok ] llm link established",
    "[ ok ] storage bridge ready",
    "ready."
)

@Composable
fun BootOverlay(onDone: () -> Unit) {
    var shown by remember { mutableIntStateOf(0) }
    val alpha = remember { Animatable(1f) }
    val glow by rememberInfiniteTransition().animateFloat(
        initialValue = 0.75f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse)
    )

    LaunchedEffect(Unit) {
        bootLines.indices.forEach { i ->
            shown = i + 1
            delay(if (i == 0) 420L else 290L)
        }
        delay(560)
        alpha.animateTo(0f, tween(480))
        onDone()
    }

    val progress by animateFloatAsState(
        targetValue = shown / bootLines.size.toFloat(),
        animationSpec = tween(320)
    )

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.value }
            .background(BootBg),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "AUTOPILOT",
                fontFamily = FontFamily.Monospace,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE5E7EB),
                style = TextStyle(
                    shadow = Shadow(
                        color = Cyan.copy(alpha = glow),
                        blurRadius = 30f
                    )
                )
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "A I   T E R M I N A L",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextDim
            )
            Spacer(Modifier.height(28.dp))
            bootLines.take(shown).forEachIndexed { i, line ->
                val lineColor = when {
                    line.startsWith("[ ok ]") -> AccentGreen
                    line == "ready." -> Cyan
                    else -> Color(0xFFB5BDCA)
                }
                Text(
                    if (i == shown - 1 && shown < bootLines.size) "$line ▌" else line,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = lineColor,
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }

        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .width(190.dp)
                .height(2.dp)
                .background(WinBorder)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(Brush.horizontalGradient(listOf(AccentGreen, AccentPurple, Cyan)))
            )
        }
    }
}

@Composable
fun FlowingGradientLine(modifier: Modifier = Modifier) {
    val shift by rememberInfiniteTransition().animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Reverse)
    )
    Canvas(modifier) {
        val x0 = size.width * (shift * 1.6f - 0.8f)
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(AccentGreen, AccentPurple, Cyan, AccentPurple, AccentGreen),
                start = Offset(x0, 0f),
                end = Offset(x0 + size.width * 0.9f, size.height)
            )
        )
    }
}

@Composable
fun Pulsing(modifier: Modifier = Modifier, min: Float = 0.45f, max: Float = 1f): Modifier {
    val pulse by rememberInfiniteTransition().animateFloat(
        initialValue = max,
        targetValue = min,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse)
    )
    return modifier.graphicsLayer { alpha = pulse }
}

@Composable
fun SlideFadeIn(content: @Composable () -> Unit) {
    var visible = remember { androidx.compose.animation.core.MutableTransitionState(false) }
    visible.targetState = true
    AnimatedVisibility(
        visibleState = visible,
        enter = fadeIn(tween(170)) + slideInVertically(tween(190)) { it / 3 }
    ) { content() }
}

@Composable
fun ChipReveal(index: Int, content: @Composable () -> Unit) {
    var shown by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) {
        delay(index * 55L)
        shown = index
    }
    AnimatedVisibility(
        visible = shown == index,
        enter = fadeIn(tween(230)) + slideInHorizontally(tween(230)) { it / 3 }
    ) { content() }
}

@Composable
fun SweepBandOverlay(modifier: Modifier = Modifier) {
    val band by rememberInfiniteTransition().animateFloat(
        initialValue = -0.15f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(5600, easing = LinearEasing))
    )
    Canvas(modifier) {
        val step = 4.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(
                color = Color.Black.copy(alpha = 0.10f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx()
            )
            y += step
        }
        val cy = size.height * band
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, Cyan.copy(alpha = 0.06f), Color.Transparent),
                startY = cy - 90f,
                endY = cy + 90f
            )
        )
    }
}
