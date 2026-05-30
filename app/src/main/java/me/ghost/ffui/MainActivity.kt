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
   * Debug-only fast re-seed of saved printers from an adb intent extra (see [DebugPrinterSeeder]
   * and `scripts/seed_printers.py`). No-op in release builds and when the extra is absent.
   */
  private fun handleSeedIntent(intent: Intent?) {
    if (!BuildConfig.DEBUG) return
    val b64 = intent?.getStringExtra(DebugPrinterSeeder.EXTRA_SEED_B64) ?: return
    lifecycleScope.launch {
      val count = runCatching { DebugPrinterSeeder.seedFromBase64(applicationContext, b64) }
        .getOrElse { e -> Log.e("DebugSeed", "seed failed", e); -1 }
      val msg = if (count >= 0) "Seeded $count printer(s)" else "Seed failed (see logcat)"
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
