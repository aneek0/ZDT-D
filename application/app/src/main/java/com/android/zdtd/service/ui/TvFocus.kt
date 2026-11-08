package com.android.zdtd.service.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Shared TV/D-pad focus treatment for root screens. */
internal fun Modifier.tvFocusHighlight(
  shape: Shape,
  enabled: Boolean = true,
): Modifier = composed {
  var focused by remember { mutableStateOf(false) }
  val focusScale by animateFloatAsState(
    targetValue = if (focused && enabled) 1.03f else 1f,
    animationSpec = tween(150, easing = FastOutSlowInEasing),
    label = "rootDpadFocusScale",
  )
  val primaryFocusBorder by animateColorAsState(
    targetValue = if (focused && enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.96f) else Color.Transparent,
    animationSpec = tween(150),
    label = "rootDpadFocusPrimaryBorder",
  )
  val contrastFocusBorder by animateColorAsState(
    targetValue = if (focused && enabled) Color.White.copy(alpha = 0.98f) else Color.Transparent,
    animationSpec = tween(150),
    label = "rootDpadFocusContrastBorder",
  )
  this
    .onFocusChanged { focused = it.isFocused }
    .scale(focusScale)
    .border(
      width = if (focused && enabled) 4.dp else 0.dp,
      color = contrastFocusBorder,
      shape = shape,
    )
    .border(
      width = if (focused && enabled) 2.dp else 0.dp,
      color = primaryFocusBorder,
      shape = shape,
    )
}
