package me.ghost.ffui.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A saved printer plus its per-printer settings. Identity/capability fields (`modelPid`,
 * `firmwareVersion`, `cameraStreamUrl`) are filled in after the first successful `/detail`; the
 * `custom*` / `force*` / `autoMatch*` fields are user preferences edited on the per-printer
 * settings screen.
 */
@Entity(tableName = "printers")
data class PrinterEntity(
    @PrimaryKey val serialNumber: String,
    val ipAddress: String,
    val name: String,
    val checkCode: String,
    /** Firmware-stable product ID (35=5M, 36=5M Pro, 38=AD5X); null until first /detail. */
    val modelPid: Int? = null,
    /** Firmware version reported by the printer; null until first /detail. */
    val firmwareVersion: String? = null,
    /** OEM camera stream URL discovered from /detail (5M Pro); null if none. */
    val cameraStreamUrl: String? = null,
    /** Enable LED control on a model without factory LEDs (5M / AD5X) via TCP ~M146. */
    val customLedEnabled: Boolean = false,
    /** Use a user-supplied camera URL instead of the OEM stream. */
    val customCameraEnabled: Boolean = false,
    /** User-supplied RTSP/HTTP/MJPEG camera URL. */
    val customCameraUrl: String = "",
    /** Force the TCP-only legacy backend even for a modern printer. */
    val forceLegacy: Boolean = false,
    /** AD5X: try to auto-match tools to IFS slots before falling back to the manual dialog. */
    val autoMatchMaterials: Boolean = false,
    /** Autoplay the camera stream when viewing the dashboard. */
    val cameraAutoPlayEnabled: Boolean = false,
    /** Overlay a live FPS counter on the camera feed (dashboard card + fullscreen). */
    val cameraFpsCounterEnabled: Boolean = false
)
