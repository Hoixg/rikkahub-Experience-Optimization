package me.rerere.rikkahub.ui.components.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import me.rerere.ai.core.ReasoningLevel
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Idea
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ToggleSurface
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningHigh
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningLow
import me.rerere.rikkahub.ui.components.ui.icons.ReasoningMedium
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val levels = ReasoningLevel.entries
private val levelCount = levels.size

@Composable
fun ReasoningButton(
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    reasoningLevel: ReasoningLevel,
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }

    if (showPicker) {
        ReasoningPicker(
            reasoningLevel = reasoningLevel,
            onDismissRequest = { showPicker = false },
            onUpdateReasoningLevel = onUpdateReasoningLevel
        )
    }

    ToggleSurface(
        checked = reasoningLevel.isEnabled,
        onClick = { showPicker = true },
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                ReasoningIcon(reasoningLevel)
            }
            if (!onlyIcon) Text(stringResource(R.string.setting_provider_page_reasoning))
        }
    }
}

@Composable
fun ReasoningPicker(
    reasoningLevel: ReasoningLevel,
    onDismissRequest: () -> Unit = {},
    onUpdateReasoningLevel: (ReasoningLevel) -> Unit,
) {
    val currentIndex = levels.indexOf(reasoningLevel).coerceAtLeast(0)
    val sliderState = remember {
        SliderState(
            value = currentIndex.toFloat(),
            trackRange = 0f..(levelCount - 1).toFloat(),
            // Keep the thumb continuous while dragging; snap to the nearest level on release.
            steps = 0,
        )
    }
    var targetSliderValue by remember { mutableStateOf(currentIndex.toFloat()) }
    val animatedSliderValue by animateFloatAsState(
        targetValue = targetSliderValue,
        animationSpec = spring(dampingRatio = 0.88f, stiffness = 160f),
        label = "reasoning_thumb_follow",
    )
    var motionImpulse by remember { mutableStateOf(0f) }
    var lastMotionNanos by remember { mutableStateOf(0L) }
    var lastDirection by remember { mutableStateOf(1f) }
    val animatedImpulse by animateFloatAsState(
        targetValue = motionImpulse,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 250f),
        label = "reasoning_thumb_elasticity",
    )
    val animatedFill by animateFloatAsState(
        targetValue = animatedSliderValue,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = 105f),
        label = "reasoning_track_follow",
    )
    val displayedLevel = levels[animatedSliderValue.roundToInt().coerceIn(0, levelCount - 1)]

    SideEffect {
        sliderState.value = animatedSliderValue
    }

    LaunchedEffect(currentIndex) {
        targetSliderValue = currentIndex.toFloat()
        motionImpulse = 0f
        lastMotionNanos = 0L
    }
    LaunchedEffect(lastMotionNanos) {
        if (lastMotionNanos != 0L) {
            delay(80)
            motionImpulse = 0f
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 标题
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.reasoning_picker_title),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = stringResource(R.string.reasoning_picker_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            AnimatedContent(
                targetState = displayedLevel,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    val direction = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                    val enterDuration = if (direction > 0) 240 else 180
                    val exitDuration = if (direction > 0) 130 else 110
                    (
                        fadeIn(tween(enterDuration, delayMillis = 15)) +
                            slideInVertically(tween(enterDuration, easing = FastOutSlowInEasing)) {
                                direction * it / 7
                            } +
                            scaleIn(tween(enterDuration, easing = FastOutSlowInEasing), initialScale = 0.88f)
                        ).togetherWith(
                        fadeOut(tween(exitDuration)) +
                            slideOutVertically(tween(exitDuration)) { -direction * it / 8 } +
                            scaleOut(tween(exitDuration), targetScale = 0.92f)
                    ).using(SizeTransform(clip = false))
                },
                label = "reasoning_level_switch",
            ) {
                level ->
                val glow = remember(level) { Animatable(0f) }
                val tilt = remember(level) { Animatable(-5f * lastDirection) }
                LaunchedEffect(level) {
                    launch {
                        tilt.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 420f))
                    }
                    glow.animateTo(1f, tween(90))
                    glow.animateTo(0f, tween(190))
                }
                val iconColor = if (level.isEnabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(36.dp)) {
                            drawCircle(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        iconColor.copy(alpha = 0.25f * glow.value),
                                        Color.Transparent,
                                    ),
                                    radius = size.minDimension * 0.65f,
                                ),
                                radius = size.minDimension * 0.65f,
                            )
                        }
                        ReasoningIcon(
                            level = level,
                            modifier = Modifier.size(32.dp).graphicsLayer {
                                rotationZ = tilt.value
                                scaleX = 1f + 0.07f * glow.value
                                scaleY = 1f + 0.07f * glow.value
                            },
                            tint = iconColor,
                        )
                    }
                    Text(level.label(), style = MaterialTheme.typography.titleMedium)
                }
            }

            Slider(
                state = sliderState,
                onValueChange = { value ->
                    val delta = value - targetSliderValue
                    if (abs(delta) > 0.0001f) {
                        val now = System.nanoTime()
                        val elapsed = if (lastMotionNanos == 0L) 0.016f
                        else ((now - lastMotionNanos) / 1_000_000_000f).coerceAtLeast(0.008f)
                        val direction = if (delta > 0f) 1f else -1f
                        lastDirection = direction
                        val speed = abs(delta) / elapsed
                        motionImpulse = direction * (speed / (speed + 4f))
                        lastMotionNanos = now
                    }
                    targetSliderValue = value
                },
                onValueChangeFinished = {
                    val snappedIndex = targetSliderValue.roundToInt().coerceIn(0, levelCount - 1)
                    targetSliderValue = snappedIndex.toFloat()
                    motionImpulse = 0f
                    lastMotionNanos = 0L
                    onUpdateReasoningLevel(levels[snappedIndex])
                },
                modifier = Modifier.fillMaxWidth(),
                thumb = {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .graphicsLayer {
                                val strength = abs(animatedImpulse).coerceAtMost(1f)
                                scaleX = 1f + 0.12f * strength
                                scaleY = 1f - 0.06f * strength
                            }
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onPrimary)
                        )
                    }
                },
                track = {
                    ElasticReasoningTrack(
                        value = sliderState.value,
                        fillValue = animatedFill,
                    )
                }
            )
        }
    }
}

@Composable
private fun ElasticReasoningTrack(value: Float, fillValue: Float) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeTickColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
    val inactiveTickColor = activeColor.copy(alpha = 0.85f)
    Canvas(Modifier.fillMaxWidth().height(24.dp)) {
        val left = 0f
        val width = size.width
        val centerY = size.height / 2f
        val halfHeight = 7.dp.toPx()
        val tickRadius = 2.dp.toPx()
        // The first and last tick centers coincide with the thumb's travel limits.
        // Extend the capsule slightly past those centers so the end ticks sit fully on it.
        val trackLeft = left - tickRadius
        val trackRight = left + width + tickRadius
        val corner = CornerRadius(halfHeight)
        val currentX = left + width * (value / (levelCount - 1)).coerceIn(0f, 1f)
        val followingValue = fillValue.coerceIn(value - 0.35f, value + 0.35f)
        val fillX = left + width * (followingValue / (levelCount - 1)).coerceIn(0f, 1f)

        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(trackLeft, centerY - halfHeight),
            size = Size(trackRight - trackLeft, halfHeight * 2f),
            cornerRadius = corner,
        )
        val activeTrackRight = (fillX + tickRadius).coerceAtMost(trackRight)
        if (activeTrackRight > trackLeft) {
            drawRoundRect(
                color = activeColor,
                topLeft = Offset(trackLeft, centerY - halfHeight),
                size = Size(activeTrackRight - trackLeft, halfHeight * 2f),
                cornerRadius = corner,
            )
        }
        if (currentX > fillX + 1f) {
            val neckStart = (fillX - halfHeight).coerceAtLeast(left)
            val gap = currentX - neckStart
            val neck = Path().apply {
                moveTo(neckStart, centerY - halfHeight)
                cubicTo(
                    neckStart + gap * 0.35f, centerY - halfHeight,
                    currentX - gap * 0.2f, centerY - halfHeight * 0.6f,
                    currentX, centerY - halfHeight * 0.6f,
                )
                lineTo(currentX, centerY + halfHeight * 0.6f)
                cubicTo(
                    currentX - gap * 0.2f, centerY + halfHeight * 0.6f,
                    neckStart + gap * 0.35f, centerY + halfHeight,
                    neckStart, centerY + halfHeight,
                )
                close()
            }
            drawPath(neck, activeColor)
        }
        repeat(levelCount) { index ->
            val tickX = left + width * index / (levelCount - 1)
            drawCircle(
                color = if (tickX <= fillX) activeTickColor else inactiveTickColor,
                radius = tickRadius,
                center = Offset(tickX, centerY),
            )
        }
    }
}

@Composable
private fun ReasoningIcon(
    level: ReasoningLevel,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val icon = when (level) {
        ReasoningLevel.OFF -> HugeIcons.Idea
        ReasoningLevel.AUTO -> HugeIcons.Idea01
        ReasoningLevel.LOW -> ReasoningLow
        ReasoningLevel.MEDIUM -> ReasoningMedium
        ReasoningLevel.HIGH -> ReasoningHigh
        ReasoningLevel.XHIGH -> ReasoningHigh
        ReasoningLevel.MAX -> ReasoningHigh
    }
    Icon(icon, contentDescription = null, modifier = modifier, tint = tint ?: LocalContentColor.current)
}

@Composable
private fun ReasoningLevel.label(): String = when (this) {
    ReasoningLevel.OFF -> stringResource(R.string.reasoning_off)
    ReasoningLevel.AUTO -> stringResource(R.string.reasoning_auto)
    ReasoningLevel.LOW -> stringResource(R.string.reasoning_light)
    ReasoningLevel.MEDIUM -> stringResource(R.string.reasoning_medium)
    ReasoningLevel.HIGH -> stringResource(R.string.reasoning_heavy)
    ReasoningLevel.XHIGH -> stringResource(R.string.reasoning_xhigh)
    ReasoningLevel.MAX -> stringResource(R.string.reasoning_max)
}

@Composable
@Preview(showBackground = true)
private fun ReasoningPickerPreview() {
    MaterialTheme {
        var level by remember { mutableStateOf(ReasoningLevel.AUTO) }
        ReasoningPicker(
            reasoningLevel = level,
            onUpdateReasoningLevel = { level = it }
        )
    }
}
