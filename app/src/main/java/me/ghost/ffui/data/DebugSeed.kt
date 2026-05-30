package me.ghost.ffui.data

import android.content.Context
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Minimal printer description accepted by [DebugPrinterSeeder]; everything else uses entity defaults. */
@Serializable
data class SeedPrinter(
    val serial: String,
    val ip: String,
    val name: String,
    val checkCode: String
)

/**
 * Debug-only fast re-seed of saved printers after a destructive DB wipe (every schema bump drops the
 * DB in this prototype). Driven over adb via an intent extra so re-pairing needs no UI:
 *
 * ```
 * adb shell am start -n me.ghost.ffui/.MainActivity --es seed_b64 <base64-json>
 * ```
 *
 * The payload is a base64-encoded JSON array of [SeedPrinter]. Inserting through Room (not raw SQL)
 * means new entity columns simply take their Kotlin defaults — the host script never has to track
 * the schema. See `scripts/seed-printers.ps1`.
 */
object DebugPrinterSeeder {
    /** Intent extra carrying the base64-encoded JSON array of printers to seed. */
    const val EXTRA_SEED_B64 = "seed_b64"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decodes [base64Json] (a base64 JSON array of [SeedPrinter]), upserts each into Room, and points
     * startup-reconnect at the seeded set so the next launch brings them up live. Returns the count.
     */
    suspend fun seedFromBase64(context: Context, base64Json: String): Int {
        val decoded = String(Base64.decode(base64Json, Base64.DEFAULT), Charsets.UTF_8)
        val printers = json.decodeFromString<List<SeedPrinter>>(decoded)
        if (printers.isEmpty()) return 0

        val dao = AppDatabase.getDatabase(context).printerDao()
        printers.forEach { p ->
            dao.insert(
                PrinterEntity(
                    serialNumber = p.serial,
                    ipAddress = p.ip,
                    name = p.name,
                    checkCode = p.checkCode
                )
            )
        }

        // Arm startup-reconnect so a relaunch reconnects everything we just seeded.
        val store = SettingsDataStore(context)
        store.setStartupReconnect(StartupReconnect.ALL)
        store.setLastConnectedSerials(printers.map { it.serial }.toSet())
        store.setLastActiveSerial(printers.first().serial)

        return printers.size
    }
}
