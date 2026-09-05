package me.ghost.ffui.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import me.ghost.ffui.MainActivity
import me.ghost.ffui.R
import me.ghost.ffui.data.PrinterEvent

/**
 * Posts per-printer status notifications (print complete / bed cooled / printer error).
 *
 * One high-importance channel backs all three event types so they can surface as heads-up alerts.
 * Notification IDs are derived from the printer serial + event type so a newer alert of the same
 * kind replaces the older one rather than stacking. The actual gating (which events a printer is
 * subscribed to) happens upstream in the poll loop — this class just renders whatever it's given.
 */
class PrinterNotifier(private val context: Context) {

    init {
        // minSdk 26 == O — channel creation is required unconditionally, no guard needed.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Printer status",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Print completion, bed-cooled, and printer error alerts"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    /** Renders [event] for the printer named [printerName]. No-op if the user denied notifications. */
    fun notify(serial: String, printerName: String, event: PrinterEvent) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val (titleSuffix, text) = when (event) {
            PrinterEvent.PrintCompleted -> "print complete" to "The print has finished."
            PrinterEvent.PrintCooled -> "ready to remove" to "The bed has cooled below 40 °C — safe to remove the print."
            is PrinterEvent.PrinterError -> "printer error" to "Reported error code ${event.code}."
        }

        // Tapping the alert opens the app — the heads-up "print complete" is exactly the
        // notification a user taps from another app; without a content intent it only dismisses.
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_printer)
            .setContentTitle("$printerName — $titleSuffix")
            .setContentText(text)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(serial, event), notification)
    }

    /** Stable per-(printer, event-type) id so repeats replace rather than stack. */
    private fun notificationId(serial: String, event: PrinterEvent): Int {
        val typeOffset = when (event) {
            PrinterEvent.PrintCompleted -> 0
            PrinterEvent.PrintCooled -> 1
            is PrinterEvent.PrinterError -> 2
        }
        return serial.hashCode() * 8 + typeOffset
    }

    private companion object {
        const val CHANNEL_ID = "printer_status"
    }
}
