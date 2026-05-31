package me.ghost.ffui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import me.ghost.ffui.data.DebugPrinterSeeder
import me.ghost.ffui.ui.FlasherApp
import me.ghost.ffui.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: alerts are best-effort */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    maybeRequestNotificationPermission()
    handleSeedIntent(intent)
    setContent {
      MyApplicationTheme {
        FlasherApp()
      }
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleSeedIntent(intent)
  }

  /**
   * Debug-only fast re-seed (or selective teardown) of saved printers from an adb intent extra
   * (see [DebugPrinterSeeder] and `scripts/seed_printers.py` / `scripts/emulator-printers.ps1`).
   * No-op in release builds and when neither extra is present.
   */
  private fun handleSeedIntent(intent: Intent?) {
    if (!BuildConfig.DEBUG) return
    val seedB64 = intent?.getStringExtra(DebugPrinterSeeder.EXTRA_SEED_B64)
    val unseedB64 = intent?.getStringExtra(DebugPrinterSeeder.EXTRA_UNSEED_B64)
    if (seedB64 == null && unseedB64 == null) return
    lifecycleScope.launch {
      val msg = runCatching {
        when {
          unseedB64 != null -> "Removed ${DebugPrinterSeeder.unseedFromBase64(applicationContext, unseedB64)} printer(s)"
          else -> "Seeded ${DebugPrinterSeeder.seedFromBase64(applicationContext, seedB64!!)} printer(s)"
        }
      }.getOrElse { e -> Log.e("DebugSeed", "seed/unseed failed", e); "Seed failed (see logcat)" }
      Log.i("DebugSeed", msg)
      Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
    }
  }

  /** Ask for POST_NOTIFICATIONS (Android 13+) so per-printer status alerts can be shown. */
  private fun maybeRequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
      != PackageManager.PERMISSION_GRANTED
    ) {
      requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
  }
}
