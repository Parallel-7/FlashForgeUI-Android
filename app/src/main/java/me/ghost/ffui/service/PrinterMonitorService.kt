package me.ghost.ffui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import me.ghost.ffui.FfuiApplication
import me.ghost.ffui.MainActivity
import me.ghost.ffui.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Foreground keep-alive shell that holds the process open so [me.ghost.ffui.data.PrinterSessionManager]
 * can keep polling printers — and raising completion/cooled/error alerts — while the app is closed.
 *
 * It owns **no** monitoring logic: the sessions, poll loops, event detection and notifications all
 * live in the manager / [me.ghost.ffui.notifications.PrinterNotifier]. This service only shows the
 * mandatory ongoing notification and observes the live session count to keep that notification's
 * text current. Its lifecycle is driven entirely by the manager (`start`/`stop`), which runs it
 * exactly while background monitoring is enabled and ≥1 printer is connected.
 *
 * Foreground-service type is `specialUse` (declared in the manifest): a LAN printer monitor doesn't
 * fit `dataSync`/`connectedDevice`, and `specialUse` avoids Android 15's ~6h/day `dataSync` cap that
 * would otherwise cut off monitoring during a long print. Suitable for a sideloaded build; a Play
 * Store release would need a `specialUse` justification in the console.
 */
class PrinterMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val manager = (application as FfuiApplication).sessionManager

        // Must reach foreground within ~5s of startForegroundService — do it before collecting.
        startForegroundCompat(buildNotification(manager.sessions.value.size))

        // Keep the count text live as printers connect/disconnect. `drop(1)` skips the value we
        // already rendered above.
        scope.launch {
            manager.sessions.drop(1).collect { sessions ->
                NotificationManagerCompat.from(this@PrinterMonitorService)
                    .notify(NOTIF_ID, buildNotification(sessions.size))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system relaunched us after killing the process under memory
        // pressure (START_STICKY). The manager was rebuilt empty by FfuiApplication.onCreate, so ask
        // it to reconnect the printers that were being monitored. App-initiated starts carry a
        // non-null intent and already have their sessions, so they skip this.
        if (intent == null) {
            (application as FfuiApplication).sessionManager.resumeMonitoringAfterRestart()
        }
        // START_STICKY so the OS re-creates us after an out-of-memory kill (it will not restart after
        // an explicit user force-stop — that's intentional).
        return START_STICKY
    }

    /**
     * Swiping the app from Recents does NOT stop monitoring — that's the whole point of the
     * background mode. Overridden as a no-op (the default would still keep us running, but this
     * makes the intent explicit and guards against a future `stopWithTask`).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Intentionally keep running.
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
    }

    private fun buildNotification(printerCount: Int): Notification {
        val text = when (printerCount) {
            0 -> getString(R.string.notif_monitoring_zero)
            else -> resources.getQuantityString(R.plurals.notif_monitoring_printers, printerCount, printerCount)
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_printer)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        // minSdk 26 == O — channel creation is required unconditionally, no guard needed.
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.service_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.service_channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "background_monitoring"
        private const val NOTIF_ID = 1

        /** Starts (or no-ops if already running) the foreground keep-alive service. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, PrinterMonitorService::class.java)
            )
        }

        /** Stops the foreground keep-alive service. */
        fun stop(context: Context) {
            context.stopService(Intent(context, PrinterMonitorService::class.java))
        }
    }
}
