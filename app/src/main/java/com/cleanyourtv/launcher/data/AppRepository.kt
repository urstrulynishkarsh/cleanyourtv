package com.cleanyourtv.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

data class AppEntry(
    val packageName: String,
    val label: String,
    val banner: ImageBitmap?,
    val icon: ImageBitmap,
    val accent: Color,
    val isTvApp: Boolean,
)

/**
 * Finds every launchable app (TV apps first, then phone-style apps that were sideloaded),
 * renders their banner/icon once and extracts an accent colour used for the dynamic backdrop.
 */
class AppRepository(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val cache = HashMap<String, Pair<Long, AppEntry>>()

    fun load(): List<AppEntry> {
        val result = LinkedHashMap<String, AppEntry>()
        val categories = listOf(
            Intent.CATEGORY_LEANBACK_LAUNCHER to true,
            Intent.CATEGORY_LAUNCHER to false,
        )
        for ((category, isTv) in categories) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(intent, 0)) {
                val activity = info.activityInfo ?: continue
                val pkg = activity.packageName
                if (pkg == context.packageName || result.containsKey(pkg)) continue
                runCatching { entryFor(activity, isTv) }.getOrNull()?.let { result[pkg] = it }
            }
        }
        cache.keys.retainAll(result.keys)
        return result.values.sortedWith(
            compareByDescending<AppEntry> { it.isTvApp }.thenBy { it.label.lowercase() }
        )
    }

    fun launchIntent(packageName: String): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: pm.getLaunchIntentForPackage(packageName))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    private fun entryFor(activity: ActivityInfo, isTv: Boolean): AppEntry {
        val pkg = activity.packageName
        val updated = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L)
        cache[pkg]?.let { (stamp, entry) -> if (stamp == updated) return entry }

        val label = activity.loadLabel(pm).toString()
        val bannerDrawable = activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm)
        val banner = bannerDrawable?.toBitmap(480, 270)
        val icon = activity.loadIcon(pm).toBitmap(192, 192)

        val palette = Palette.from(banner ?: icon).generate()
        val fallback = 0xFF1C1C1E.toInt()
        val accent = palette.getDarkVibrantColor(
            palette.getDarkMutedColor(palette.getDominantColor(fallback))
        )

        val entry = AppEntry(
            packageName = pkg,
            label = label,
            banner = banner?.asImageBitmap(),
            icon = icon.asImageBitmap(),
            accent = Color(accent),
            isTvApp = isTv,
        )
        cache[pkg] = updated to entry
        return entry
    }
}
