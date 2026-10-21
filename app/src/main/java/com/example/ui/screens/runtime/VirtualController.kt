package com.example.ui.screens.runtime

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.screens.controllerEditor.ControllerButtonState
import kotlin.math.roundToInt

@Composable
fun VirtualController(
    buttons: List<ControllerButtonState>,
    onButtonPressed: (String) -> Unit,
    onButtonReleased: (String) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        buttons.forEach { buttonState ->
            val baseSize = if (buttonState.label.length > 1) 80.dp else 64.dp
            val scaledSize = baseSize * buttonState.scale

            Box(
                modifier = Modifier
                    .offset { IntOffset(buttonState.initialX.roundToInt(), buttonState.initialY.roundToInt()) }
                    .size(scaledSize)
                    .background(Color.White.copy(alpha = 0.2f), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                onButtonPressed(buttonState.id)
                                tryAwaitRelease()
                                onButtonReleased(buttonState.id)
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = buttonState.label,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
