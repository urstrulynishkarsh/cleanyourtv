package com.cleanyourtv.launcher.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Top Shelf order and hidden apps, observable from Compose. */
class LauncherPrefs(context: Context) {

    private val sp = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    var favorites by mutableStateOf(
        sp.getString(KEY_FAV, "").orEmpty().split(',').filter { it.isNotBlank() }
    )
        private set

    var hidden by mutableStateOf(sp.getStringSet(KEY_HIDDEN, emptySet()).orEmpty().toSet())
        private set

    /** First run: put the first few TV apps on the Top Shelf so it isn't empty. */
    fun seedIfNeeded(apps: List<AppEntry>) {
        if (sp.getBoolean(KEY_SEEDED, false) || apps.isEmpty()) return
        favorites = apps.filter { it.isTvApp }.ifEmpty { apps }.take(6).map { it.packageName }
        sp.edit().putBoolean(KEY_SEEDED, true).apply()
        save()
    }

    fun toggleFavorite(pkg: String) {
        favorites = if (pkg in favorites) favorites - pkg else favorites + pkg
        save()
    }

    fun move(pkg: String, delta: Int) {
        val from = favorites.indexOf(pkg)
        val to = from + delta
        if (from < 0 || to !in favorites.indices) return
        favorites = favorites.toMutableList().apply { add(to, removeAt(from)) }
        save()
    }

    fun hide(pkg: String) {
        hidden = hidden + pkg
        favorites = favorites - pkg
        save()
    }

    fun unhideAll() {
        hidden = emptySet()
        save()
    }

    private fun save() {
        sp.edit()
            .putString(KEY_FAV, favorites.joinToString(","))
            .putStringSet(KEY_HIDDEN, hidden)
            .apply()
    }

    private companion object {
        const val KEY_FAV = "favorites"
        const val KEY_HIDDEN = "hidden"
        const val KEY_SEEDED = "seeded"
    }
}
