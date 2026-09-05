package me.ghost.ffui

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import me.ghost.ffui.data.DebugPrinterSeeder
import me.ghost.ffui.ui.FlasherApp
import me.ghost.ffui.ui.theme.Theme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  private val requestNotificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op: alerts are best-effort */ }

  /** May be null on devices without NFC hardware; all NFC wiring is then skipped. */
  private val nfcAdapter: NfcAdapter? by lazy { NfcAdapter.getDefaultAdapter(this) }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    maybeRequestNotificationPermission()
    handleSeedIntent(intent)
    setContent {
      Theme {
        FlasherApp()
      }
    }
  }

  override fun onResume() {
    super.onResume()
    // Foreground dispatch keeps NFC tag intents routed to this Activity while it's visible, so the
    // Spools scan/write dialogs receive taps instead of the system tag handler.
    val adapter = nfcAdapter ?: return
    val pendingIntent = PendingIntent.getActivity(
      this, 0,
      Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
      PendingIntent.FLAG_MUTABLE
    )
    adapter.enableForegroundDispatch(this, pendingIntent, null, null)
  }

  override fun onPause() {
    super.onPause()
    nfcAdapter?.disableForegroundDispatch(this)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleSeedIntent(intent)
    handleNfcIntent(intent)
  }

  /** Route a foreground-dispatched NFC tag to the shared [NfcManager][me.ghost.ffui.nfc.NfcManager]. */
  private fun handleNfcIntent(intent: Intent) {
    if (intent.action != NfcAdapter.ACTION_TAG_DISCOVERED &&
      intent.action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
      intent.action != NfcAdapter.ACTION_TECH_DISCOVERED
    ) return
    val tag = IntentCompat.getParcelableExtra(intent, NfcAdapter.EXTRA_TAG, Parcelable::class.java) as? Tag
      ?: return
    (application as FfuiApplication).nfcManager.handleTag(tag)
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
