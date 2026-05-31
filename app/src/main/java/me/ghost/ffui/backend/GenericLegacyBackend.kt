package me.ghost.ffui.backend

import me.ghost.ffui.api.AD5XMaterialMapping
import me.ghost.ffui.api.FFGcodeFileEntry
import me.ghost.ffui.api.FlashForgeHttpApi
import me.ghost.ffui.api.FlashForgeTcpClient
import me.ghost.ffui.api.PrinterCapabilities
import me.ghost.ffui.api.PrinterDetailResponse
import me.ghost.ffui.api.PrinterModel
import me.ghost.ffui.data.PrinterEntity

/**
 * Backend for legacy FlashForge printers (Adventurer 3, Adventurer 4, and other older machines)
 * that lack the modern HTTP REST API. Status is polled over TCP G-code (`~M119`, `~M105`, `~M27`)
 * and mapped onto [PrinterDetailResponse] so the rest of the app treats all backends uniformly.
 *
 * All control operations (pause/resume/cancel, start print, LED, homing, temperatures) go over
 * TCP G-code commands. HTTP-dependent operations (rename, auto-shutdown, clear platform) are
 * unavailable and return failure.
 */
class GenericLegacyBackend(
    printer: PrinterEntity,
    http: FlashForgeHttpApi,
    tcp: FlashForgeTcpClient,
    override val model: PrinterModel = PrinterModel.GENERIC_LEGACY
) : PrinterBackend(printer, http, tcp) {

    override val pollsOverTcp = true

    // ---- Capabilities ----

    /** Legacy printers have no HTTP API; capabilities are fixed at construction time. */
    override suspend fun initialize(): Result<PrinterCapabilities> {
        capabilities = PrinterCapabilities(
            model = model,
            ledControl = true,       // factory LEDs via ~M146
            ledViaHttp = false,
            filtrationControl = false,
            hasMaterialStation = false
        )
        return Result.success(capabilities)
    }

    // ---- Active TCP status polling ----

    /**
     * Issues `~M119` (machine status, LED, current file), `~M105` (temperatures), and `~M27`
     * (print progress) over TCP, then maps the combined response onto [PrinterDetailResponse].
     */
    override suspend fun pollStatus(): Result<PrinterDetailResponse> {
        if (!tcp.isConnected.value) {
            return Result.failure(IllegalStateException("Legacy TCP transport not connected"))
        }

        // M119 — machine status, LED state, current file
        val m119 = tcp.sendCommandWithResponse("~M119")
        if (m119.isFailure) return Result.failure(m119.exceptionOrNull()!!)

        // M105 — temperatures
        val m105 = tcp.sendCommandWithResponse("~M105")

        // M27 — progress (only useful while printing/paused)
        val rawStatus = parseMachineStatus(m119.getOrDefault(""))
        val status = normalizeStatus(rawStatus)
        val m27 = if (status == "printing" || status == "paused") {
            tcp.sendCommandWithResponse("~M27")
        } else {
            Result.success("")
        }

        tcp.resetReconnectBackoff()

        return Result.success(
            buildDetailResponse(
                statusResp = m119.getOrDefault(""),
                tempResp = m105.getOrDefault(""),
                progressResp = m27.getOrDefault("")
            )
        )
    }

    // ---- TCP-only job control ----

    override suspend fun pause(): Result<Unit> =
        tcp.sendCommandWithResponse("~M25").map { }

    override suspend fun resume(): Result<Unit> =
        tcp.sendCommandWithResponse("~M24").map { }

    override suspend fun cancel(): Result<Unit> =
        tcp.sendCommandWithResponse("~M26").map { }

    override suspend fun startPrint(
        fileName: String,
        leveling: Boolean,
        mappings: List<AD5XMaterialMapping>
    ): Result<Unit> {
        // A3 uses /data/ prefix; A4/generic uses 0:/user/ prefix.
        val path = if (model == PrinterModel.ADVENTURER_3) {
            "/data/$fileName"
        } else {
            "0:/user/$fileName"
        }
        tcp.sendCommandWithResponse("~M23 $path")
            .onFailure { return Result.failure(it) }
        return tcp.sendCommandWithResponse("~M24").map { }
    }

    override suspend fun clearPlatform(): Result<Unit> =
        Result.failure(IllegalStateException("Clear platform not available over TCP"))

    /**
     * LED control. A3 uses `~M146 1/0` (simple on/off), while A4/generic uses `~M146 r255 g255
     * b255 F0` (RGB). Override to pick the right format based on [model].
     */
    override suspend fun setLight(on: Boolean): Result<Unit> {
        if (!capabilities.ledControl) {
            return Result.failure(IllegalStateException("LED control not available for $model"))
        }
        if (model == PrinterModel.ADVENTURER_3) {
            tcp.sendCommand(if (on) "~M146 1" else "~M146 0")
        } else {
            if (on) tcp.ledOn() else tcp.ledOff()
        }
        return Result.success(Unit)
    }

    // ---- File management (TCP-only) ----

    /**
     * Legacy printers have no HTTP `/gcodeList`; falls back to the TCP `~M661` file list
     * wrapped as minimal [FFGcodeFileEntry] objects (no metadata).
     */
    override suspend fun listRecentFiles(): Result<List<FFGcodeFileEntry>> =
        listLocalFiles().map { names ->
            names.map { FFGcodeFileEntry(gcodeFileName = it) }
        }

    /** File thumbnail via TCP `~M662`. Returns `null` when the file has no thumbnail. */
    override suspend fun getThumbnail(fileName: String): Result<ByteArray?> =
        tcp.getFileThumbnail(fileName)

    // ---- Unavailable HTTP-only operations ----

    override suspend fun rename(name: String): Result<Unit> =
        Result.failure(IllegalStateException("Rename not available over TCP"))

    override suspend fun setAutoShutdown(enabled: Boolean, minutes: Int): Result<Unit> =
        Result.failure(IllegalStateException("Auto-shutdown not available over TCP"))

    // ---- Response parsing helpers ----

    /**
     * Maps the legacy `M119` `MachineStatus` token onto the modern lowercase `status` strings
     * the rest of the app (and the adaptive cadence) speaks. `BUILDING_FROM_SD` — which only
     * comes from the TCP flow — becomes `printing`, so the HTTP cadence table never sees it.
     */
    private fun normalizeStatus(raw: String): String = when (raw.uppercase()) {
        "READY", "IDLE" -> "ready"
        "BUILDING_FROM_SD", "PRINTING" -> "printing"
        "BUILDING_COMPLETED" -> "completed"
        "PAUSED" -> "paused"
        "BUSY" -> "busy"
        else -> raw.lowercase()
    }

    /** Extracts the `MachineStatus:` value from an M119 multi-line response. */
    private fun parseMachineStatus(response: String): String =
        response.lineSequence()
            .map { it.trim() }
            .find { it.startsWith("MachineStatus:") }
            ?.substringAfter("MachineStatus:")?.trim().orEmpty()

    /**
     * Extracts extruder and bed temperatures from an M105 response line like
     * `T0:25/200 T1:0/0 B:25/60 @:0 B@:0`.
     */
    private fun parseTemps(response: String): Temps {
        var eCur: Float? = null
        var eTar: Float? = null
        var bCur: Float? = null
        var bTar: Float? = null

        for (part in response.split(" ", "\n")) {
            val trimmed = part.trim()
            if (trimmed.startsWith("T0:")) {
                val tempStr = trimmed.substring(3)
                if (tempStr.contains("/")) {
                    val s = tempStr.split("/")
                    eCur = s[0].toFloatOrNull()
                    eTar = s[1].toFloatOrNull()
                } else {
                    eCur = tempStr.toFloatOrNull()
                }
            } else if (trimmed.startsWith("B:") && !trimmed.startsWith("B@")) {
                val tempStr = trimmed.substring(2)
                if (tempStr.contains("/")) {
                    val s = tempStr.split("/")
                    bCur = s[0].toFloatOrNull()
                    bTar = s[1].toFloatOrNull()
                } else {
                    bCur = tempStr.toFloatOrNull()
                }
            }
        }
        return Temps(eCur, eTar, bCur, bTar)
    }

    /** Extracts progress and layer info from an M27 response. */
    private fun parseProgress(response: String): Progress {
        val sdLine = response.lineSequence()
            .map { it.trim() }
            .find { it.contains("SD printing byte", ignoreCase = true) }
        val sdMatch = sdLine?.let {
            Regex("""SD printing byte\s+(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(it)
        }

        val layerLine = response.lineSequence()
            .map { it.trim() }
            .find { it.startsWith("Layer:") }
        val layerMatch = layerLine?.let {
            Regex("""Layer:\s*(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)
                .find(it)
        }

        return Progress(
            progressPercent = sdMatch?.groupValues?.get(1)?.toFloatOrNull(),
            progressTotal = sdMatch?.groupValues?.get(2)?.toFloatOrNull(),
            currentLayer = layerMatch?.groupValues?.get(1)?.toFloatOrNull(),
            totalLayers = layerMatch?.groupValues?.get(2)?.toFloatOrNull()
        )
    }

    /** Extracts the `CurrentFile:` value from an M119 response. */
    private fun parseCurrentFile(response: String): String? =
        response.lineSequence()
            .map { it.trim() }
            .find { it.startsWith("CurrentFile:") || it.startsWith("PrintFileName:") }
            ?.substringAfter(":")?.trim()?.takeIf { it.isNotEmpty() }

    /** Extracts the `LED:` value from an M119 response (0 or 1). */
    private fun parseLedState(response: String): Boolean {
        val ledLine = response.lineSequence()
            .map { it.trim() }
            .find { it.startsWith("LED:") || it.startsWith("LEDStatus:") }
        val value = ledLine?.substringAfter(":")?.trim()?.lowercase()
        return value == "1" || value == "on"
    }

    /**
     * Combines parsed M119, M105, and M27 data into a single [PrinterDetailResponse].
     */
    private fun buildDetailResponse(
        statusResp: String,
        tempResp: String,
        progressResp: String
    ): PrinterDetailResponse {
        val rawStatus = parseMachineStatus(statusResp)
        val status = normalizeStatus(rawStatus)
        val temps = parseTemps(tempResp)
        val progress = parseProgress(progressResp)
        val currentFile = parseCurrentFile(statusResp)
        val ledOn = parseLedState(statusResp)

        // printProgress is a 0..1 fraction (the dashboard multiplies by 100). M27 reports
        // "SD printing byte cur/total"; fall back to layer counts when the SD byte pair is missing.
        val printProgress = run {
            val cur = progress.progressPercent
            val total = progress.progressTotal
            if (cur != null && total != null && total > 0f) return@run cur / total
            val layer = progress.currentLayer ?: return@run null
            val layers = progress.totalLayers ?: return@run null
            if (layers > 0f) layer / layers else null
        }

        return PrinterDetailResponse(
            status = status,
            rightTemp = temps.extCurrent,
            rightTargetTemp = temps.extTarget,
            platTemp = temps.bedCurrent,
            platTargetTemp = temps.bedTarget,
            printProgress = printProgress,
            printLayer = progress.currentLayer,
            targetPrintLayer = progress.totalLayers,
            printFileName = currentFile,
            lightStatus = if (ledOn) "open" else "close",
            name = printer.name
        )
    }

    // ---- Internal data classes for parsed telemetry ----

    private data class Temps(
        val extCurrent: Float? = null,
        val extTarget: Float? = null,
        val bedCurrent: Float? = null,
        val bedTarget: Float? = null
    )

    private data class Progress(
        val progressPercent: Float? = null,
        val progressTotal: Float? = null,
        val currentLayer: Float? = null,
        val totalLayers: Float? = null
    )
}
