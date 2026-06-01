package me.ghost.ffui.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers for the system battery-optimization exemption. Without it, aggressive OEM power managers
 * (and Doze) can kill or freeze the [PrinterMonitorService] regardless of `START_STICKY`, so the
 * Background settings section nudges the user to grant it once background monitoring is enabled.
 *
 * Note: `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a Play-policy-restricted permission, but this is a
 * sideloaded build where the direct prompt is the right UX. A Play Store release would need to drop
 * the direct request and point users at the battery-optimization settings list instead.
 */
object BatteryOptimization {

    /** Whether the app is already exempt from battery optimization (Doze/standby). */
    fun isIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** Intent that pops the system "allow background activity / ignore battery optimization" dialog. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.fromParts("package", context.packageName, null))
}
