package com.cleanyourtv.launcher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleanyourtv.launcher.data.AppEntry

private val TileShape = RoundedCornerShape(16.dp)

private fun KeyEvent.isSelect() =
    key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * A 16:9 app tile. Focus lifts it (scale + shadow + glass shine), like tvOS.
 * Remote: OK = open, long-press OK or ☰ Menu = options.
 */
@Composable
fun AppTile(
    app: AppEntry,
    modifier: Modifier = Modifier,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val longFired = remember { booleanArrayOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tileScale",
    )

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = if (isFocused) 36f else 0f
                    shape = TileShape
                    clip = true
                }
                .background(app.accent)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .onKeyEvent { e ->
                    when {
                        e.key == Key.Menu && e.type == KeyEventType.KeyDown -> {
                            onLongClick(); true
                        }
                        e.isSelect() && e.type == KeyEventType.KeyDown -> {
                            val repeat = e.nativeKeyEvent.repeatCount
                            if (repeat == 0) longFired[0] = false
                            if (repeat == 1) {
                                longFired[0] = true
                                onLongClick()
                            }
                            true
                        }
                        e.isSelect() && e.type == KeyEventType.KeyUp -> {
                            if (!longFired[0]) onClick()
                            longFired[0] = false
                            true
                        }
                        else -> false
                    }
                }
                .focusable(),
        ) {
            val banner = app.banner
            if (banner != null) {
                Image(
                    bitmap = banner,
                    contentDescription = app.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Image(
                    bitmap = app.icon,
                    contentDescription = app.label,
                    modifier = Modifier.size(64.dp).align(Alignment.Center),
                )
            }
            if (isFocused) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.22f), Color.Transparent, Color.Transparent)
                            )
                        )
                )
            }
        }
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 12.dp, start = 4.dp)
                .graphicsLayer { alpha = if (isFocused) 1f else 0.5f },
        )
    }
}

/** Plain text tile for launcher actions (Settings, Unhide…). */
@Composable
fun ActionTile(title: String, subtitle: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (isFocused) 1.08f else 1f, label = "actionScale")
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (isFocused) 36f else 0f
                shape = TileShape
                clip = true
            }
            .background(if (isFocused) Color(0xFFF5F5F7) else Color(0x33FFFFFF))
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(20.dp),
    ) {
        Column(Modifier.align(Alignment.BottomStart)) {
            Text(title, color = if (isFocused) Color.Black else Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = if (isFocused) Color.DarkGray else Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
        }
    }
}

@Composable
fun MenuButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) Color(0xFFF5F5F7) else Color.Transparent)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text, color = if (isFocused) Color.Black else Color.White, fontSize = 19.sp)
    }
}
