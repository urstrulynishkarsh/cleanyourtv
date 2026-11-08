package com.cleanyourtv.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.cleanyourtv.launcher.data.AppEntry
import com.cleanyourtv.launcher.data.AppRepository
import com.cleanyourtv.launcher.data.LauncherPrefs
import com.cleanyourtv.launcher.ui.HomeScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private lateinit var repo: AppRepository
    private lateinit var prefs: LauncherPrefs
    private val apps = mutableStateOf<List<AppEntry>>(emptyList())

    // Install / uninstall / update → refresh the home screen live
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = reload()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = AppRepository(this)
        prefs = LauncherPrefs(this)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                HomeScreen(
                    apps = apps.value,
                    prefs = prefs,
                    onLaunch = ::launch,
                    onAppInfo = ::openAppInfo,
                    onOpenSettings = ::openSettings,
                )
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }

    private fun reload() {
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { repo.load() }
            prefs.seedIfNeeded(loaded)
            apps.value = loaded
        }
    }

    private fun launch(app: AppEntry) {
        val intent = repo.launchIntent(app.packageName)
        if (intent == null) {
            toast("Can't open ${app.label}")
            return
        }
        runCatching { startActivity(intent) }.onFailure { toast("Can't open ${app.label}") }
    }

    private fun openAppInfo(app: AppEntry) {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { toast("App info isn't available on this TV") }
    }

    private fun openSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { toast("Settings isn't available") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
