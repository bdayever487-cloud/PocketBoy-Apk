package com.example.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.emulator.EmulatorButtons
import com.example.ui.theme.AtomicIndigo
import com.example.ui.theme.ConsoleOutline
import com.example.ui.theme.ConsoleSurfaceHigh
import com.example.ui.theme.ConsoleSurfaceVariant
import com.example.ui.theme.CoralButton
import com.example.ui.theme.ElectricLime
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun MultiTouchDPad(
    size: Dp = 152.dp,
    opacity: Float = 0.9f,
    hapticEnabled: Boolean = true,
    onDirectionMaskChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var activeMask by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .size(size)
            .alpha(opacity)
            .testTag("virtual_dpad")
            .pointerInput(hapticEnabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        var nextMask = 0
                        val w = this.size.width.toFloat().coerceAtLeast(1f)
                        val h = this.size.height.toFloat().coerceAtLeast(1f)
                        val cx = w / 2f
                        val cy = h / 2f
                        val deadZone = minOf(w, h) * 0.14f

                        for (change in event.changes) {
                            if (change.pressed && event.type != PointerEventType.Release) {
                                val dx = change.position.x - cx
                                val dy = change.position.y - cy
                                if (dx < -deadZone) nextMask = nextMask or EmulatorButtons.LEFT
                                if (dx > deadZone) nextMask = nextMask or EmulatorButtons.RIGHT
                                if (dy < -deadZone) nextMask = nextMask or EmulatorButtons.UP
                                if (dy > deadZone) nextMask = nextMask or EmulatorButtons.DOWN
                            }
                        }

                        if (nextMask != activeMask) {
                            if (hapticEnabled && (nextMask and activeMask.inv()) != 0) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            activeMask = nextMask
                            onDirectionMaskChanged(nextMask)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val armThick = this.size.width * 0.34f
            val armLen = this.size.width * 0.92f
            val centerX = this.size.width / 2f
            val centerY = this.size.height / 2f
            val left = centerX - armLen / 2f
            val top = centerY - armLen / 2f
            val corner = CornerRadius(armThick * 0.24f, armThick * 0.24f)

            // Outer housing ring
            drawCircle(
                color = Color(0xFF141824).copy(alpha = 0.72f),
                radius = this.size.width * 0.48f
            )

            // Horizontal bar
            drawRoundRect(
                color = Color(0xFF283044),
                topLeft = Offset(left, centerY - armThick / 2f),
                size = Size(armLen, armThick),
                cornerRadius = corner
            )
            // Vertical bar
            drawRoundRect(
                color = Color(0xFF283044),
                topLeft = Offset(centerX - armThick / 2f, top),
                size = Size(armThick, armLen),
                cornerRadius = corner
            )

            // Highlight pressed directions
            if ((activeMask and EmulatorButtons.UP) != 0) {
                drawRoundRect(
                    color = ElectricLime.copy(alpha = 0.55f),
                    topLeft = Offset(centerX - armThick / 2f, top),
                    size = Size(armThick, armLen * 0.36f),
                    cornerRadius = corner
                )
            }
            if ((activeMask and EmulatorButtons.DOWN) != 0) {
                drawRoundRect(
                    color = ElectricLime.copy(alpha = 0.55f),
                    topLeft = Offset(centerX - armThick / 2f, centerY + armLen * 0.14f),
                    size = Size(armThick, armLen * 0.36f),
                    cornerRadius = corner
                )
            }
            if ((activeMask and EmulatorButtons.LEFT) != 0) {
                drawRoundRect(
                    color = ElectricLime.copy(alpha = 0.55f),
                    topLeft = Offset(left, centerY - armThick / 2f),
                    size = Size(armLen * 0.36f, armThick),
                    cornerRadius = corner
                )
            }
            if ((activeMask and EmulatorButtons.RIGHT) != 0) {
                drawRoundRect(
                    color = ElectricLime.copy(alpha = 0.55f),
                    topLeft = Offset(centerX + armLen * 0.14f, centerY - armThick / 2f),
                    size = Size(armLen * 0.36f, armThick),
                    cornerRadius = corner
                )
            }

            // Center pivot dimple
            drawCircle(
                color = Color(0xFF181E2C),
                radius = armThick * 0.32f,
                center = Offset(centerX, centerY)
            )
        }
    }
}

@Composable
fun TactileActionButton(
    label: String,
    accentColor: Color,
    size: Dp = 64.dp,
    opacity: Float = 0.9f,
    hapticEnabled: Boolean = true,
    testTag: String,
    onPressedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var pressed by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .size(size)
            .alpha(opacity)
            .clip(CircleShape)
            .background(
                brush = Brush.verticalGradient(
                    colors = if (pressed != 0) {
                        listOf(accentColor, accentColor.copy(alpha = 0.85f))
                    } else {
                        listOf(ConsoleSurfaceHigh, ConsoleSurfaceVariant)
                    }
                )
            )
            .border(
                width = 2.dp,
                color = if (pressed != 0) Color.White.copy(alpha = 0.7f) else accentColor.copy(alpha = 0.65f),
                shape = CircleShape
            )
            .testTag(testTag)
            .pointerInput(hapticEnabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isDown = event.changes.any { it.pressed }
                        val next = if (isDown) 1 else 0
                        if (next != pressed) {
                            if (next == 1 && hapticEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            pressed = next
                            onPressedChanged(isDown)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            ),
            color = if (pressed != 0) Color(0xFF0E1017) else TextPrimary
        )
    }
}

@Composable
fun PillControlButton(
    label: String,
    opacity: Float = 0.9f,
    hapticEnabled: Boolean = true,
    isShoulder: Boolean = false,
    testTag: String,
    onPressedChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    var pressed by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier
            .height(48.dp)
            .width(if (isShoulder) 88.dp else 74.dp)
            .alpha(opacity)
            .clip(RoundedCornerShape(if (isShoulder) 14.dp else 24.dp))
            .background(if (pressed != 0) AtomicIndigo else ConsoleSurfaceVariant)
            .border(
                width = 1.5.dp,
                color = if (pressed != 0) Color.White.copy(alpha = 0.6f) else ConsoleOutline,
                shape = RoundedCornerShape(if (isShoulder) 14.dp else 24.dp)
            )
            .testTag(testTag)
            .pointerInput(hapticEnabled) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val isDown = event.changes.any { it.pressed }
                        val next = if (isDown) 1 else 0
                        if (next != pressed) {
                            if (next == 1 && hapticEnabled) {
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            }
                            pressed = next
                            onPressedChanged(isDown)
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            ),
            color = if (pressed != 0) TextPrimary else TextSecondary
        )
    }
}

@Composable
fun ActionCluster(
    opacity: Float,
    hapticEnabled: Boolean,
    onAChanged: (Boolean) -> Unit,
    onBChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Spacer(modifier = Modifier.height(22.dp))
            TactileActionButton(
                label = "B",
                accentColor = CoralButton,
                opacity = opacity,
                hapticEnabled = hapticEnabled,
                testTag = "btn_b",
                onPressedChanged = onBChanged
            )
        }
        Column {
            TactileActionButton(
                label = "A",
                accentColor = ElectricLime,
                opacity = opacity,
                hapticEnabled = hapticEnabled,
                testTag = "btn_a",
                onPressedChanged = onAChanged
            )
            Spacer(modifier = Modifier.height(22.dp))
        }
    }
}
