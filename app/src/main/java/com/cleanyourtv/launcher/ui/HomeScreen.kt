package com.cleanyourtv.launcher.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.cleanyourtv.launcher.data.AppEntry
import com.cleanyourtv.launcher.data.LauncherPrefs
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DefaultAccent = Color(0xFF1C1C1E)

@Composable
fun HomeScreen(
    apps: List<AppEntry>,
    prefs: LauncherPrefs,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val hidden = prefs.hidden
    val favorites = prefs.favorites
    val visible = remember(apps, hidden) { apps.filter { it.packageName !in hidden } }
    val shelf = remember(visible, favorites) {
        favorites.mapNotNull { pkg -> visible.firstOrNull { it.packageName == pkg } }
    }
    val others = remember(visible, shelf) {
        val onShelf = shelf.map { it.packageName }.toSet()
        visible.filter { it.packageName !in onShelf }
    }

    var focused by remember { mutableStateOf<AppEntry?>(null) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    val hero = focused ?: shelf.firstOrNull() ?: visible.firstOrNull()
    val backdrop by animateColorAsState(hero?.accent ?: DefaultAccent, tween(700), label = "backdrop")
    val firstTile = remember { FocusRequester() }

    // Home screen: Back does nothing (never exit to the stock launcher)
    BackHandler(enabled = true) {}

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Dynamic ambient backdrop that follows the focused app's colours
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to backdrop,
                        0.5f to backdrop.copy(alpha = 0.35f),
                        1f to Color.Black,
                    )
                )
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(220.dp),
            contentPadding = PaddingValues(start = 64.dp, end = 64.dp, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { TopShelf(hero) }

            if (shelf.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        contentPadding = PaddingValues(vertical = 18.dp, horizontal = 6.dp),
                    ) {
                        itemsIndexed(shelf, key = { _, a -> a.packageName }) { index, app ->
                            AppTile(
                                app = app,
                                modifier = Modifier
                                    .width(300.dp)
                                    .then(if (index == 0) Modifier.focusRequester(firstTile) else Modifier),
                                onFocus = { focused = app },
                                onClick = { onLaunch(app) },
                                onLongClick = { menuFor = app },
                            )
                        }
                    }
                }
            }

            if (others.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("All Apps") }
                items(others, key = { it.packageName }) { app ->
                    AppTile(
                        app = app,
                        onFocus = { focused = app },
                        onClick = { onLaunch(app) },
                        onLongClick = { menuFor = app },
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("Tools") }
            item(key = "settings") {
                ActionTile("Settings", "System settings", onClick = onOpenSettings)
            }
            if (hidden.isNotEmpty()) {
                item(key = "unhide") {
                    ActionTile("Unhide apps", "${hidden.size} hidden", onClick = { prefs.unhideAll() })
                }
            }
        }

        Clock(Modifier.align(Alignment.TopEnd).padding(top = 36.dp, end = 64.dp))
    }

    LaunchedEffect(shelf.isNotEmpty()) {
        if (shelf.isNotEmpty()) {
            delay(150)
            runCatching { firstTile.requestFocus() }
        }
    }

    menuFor?.let { app ->
        AppMenu(
            app = app,
            prefs = prefs,
            onDismiss = { menuFor = null },
            onAppInfo = {
                menuFor = null
                onAppInfo(app)
            },
        )
    }
}

/** Big hero area at the top that changes with focus, like the tvOS Top Shelf. */
@Composable
private fun TopShelf(app: AppEntry?) {
    Box(Modifier.fillMaxWidth().height(360.dp)) {
        Crossfade(targetState = app, animationSpec = tween(450), label = "hero") { a ->
            if (a == null) return@Crossfade
            Box(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .fillMaxWidth(0.58f)
                        .fillMaxHeight(0.78f)
                        .clip(RoundedCornerShape(24.dp))
                        .background(a.accent),
                ) {
                    val banner = a.banner
                    if (banner != null) {
                        Image(
                            bitmap = banner,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Image(
                            bitmap = a.icon,
                            contentDescription = null,
                            modifier = Modifier.size(140.dp).align(Alignment.Center),
                        )
                    }
                    // Fade the hero art into the backdrop on the left
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    0f to a.accent.copy(alpha = 0.85f),
                                    0.35f to Color.Transparent,
                                )
                            )
                    )
                }
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(0.4f)
                        .padding(bottom = 24.dp),
                ) {
                    Text(
                        a.label,
                        color = Color.White,
                        fontSize = 46.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 50.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (a.isTvApp) "Press OK to open · hold OK for options" else "Phone app · may need a mouse",
                        color = Color.White.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 22.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 18.dp, start = 4.dp),
    )
}

@Composable
private fun Clock(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(10_000)
        }
    }
    val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
    Column(modifier, horizontalAlignment = Alignment.End) {
        Text(
            SimpleDateFormat(timePattern, Locale.getDefault()).format(now),
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Light,
        )
        Text(
            SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(now),
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 15.sp,
        )
    }
}

@Composable
private fun AppMenu(
    app: AppEntry,
    prefs: LauncherPrefs,
    onDismiss: () -> Unit,
    onAppInfo: () -> Unit,
) {
    val pkg = app.packageName
    val favorites = prefs.favorites
    val isFav = pkg in favorites
    val index = favorites.indexOf(pkg)
    val actions = buildList<Pair<String, () -> Unit>> {
        add((if (isFav) "Remove from Top Shelf" else "Add to Top Shelf") to {
            prefs.toggleFavorite(pkg); onDismiss()
        })
        if (isFav && index > 0) add("Move left" to { prefs.move(pkg, -1) })
        if (isFav && index < favorites.lastIndex) add("Move right" to { prefs.move(pkg, 1) })
        add("Hide app" to { prefs.hide(pkg); onDismiss() })
        add("App info" to onAppInfo)
    }
    val first = remember { FocusRequester() }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF22C2C2E))
                .padding(24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(app.icon, null, Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)))
                Spacer(Modifier.width(16.dp))
                Text(app.label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Spacer(Modifier.height(16.dp))
            actions.forEachIndexed { i, (label, action) ->
                MenuButton(
                    text = label,
                    onClick = action,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                )
            }
        }
        LaunchedEffect(Unit) {
            delay(50)
            runCatching { first.requestFocus() }
        }
    }
}
