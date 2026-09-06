package me.ghost.ffui.data

import me.ghost.ffapi.models.FFPrinterDetail

/**
 * Pure state machine behind [ActivePrinterSession]'s notification events, extracted so the
 * transition logic is unit-testable without a live poll loop.
 *
 * Baselines are captured on the first observation so connecting to an already-completed or
 * already-errored printer doesn't fire a spurious alert. [PrinterEvent.PrintCooled] only arms
 * after the bed was actually seen at/above [BED_SAFE_TEMP_C] — a short PLA job on an already-cold
 * bed must not fire "safe to remove" right behind "print complete" — and the watch resets when a
 * new job starts.
 */
internal class PrintEventDetector {

    private var seenFirstDetail = false
    private var prevStatusKey: String? = null
    private var prevErrorCode: String? = null
    private var awaitingCooldown = false

    /** Whether the bed was seen at/above [BED_SAFE_TEMP_C] since the job started. */
    private var bedWasHot = false

    /**
     * Feeds one `/detail` snapshot and returns the events to emit, in emission order
     * (cooled, completed, error). The per-printer opt-in flags are passed in by the caller so it
     * can re-read them from the live printer at emit time.
     */
    fun detect(
        detail: FFPrinterDetail,
        notifyOnComplete: Boolean,
        notifyOnCooled: Boolean,
        notifyOnError: Boolean,
    ): List<PrinterEvent> {
        val events = mutableListOf<PrinterEvent>()
        val status = detail.status?.lowercase()
        val error = detail.errorCode?.takeIf { it.isNotBlank() && it != "0" }

        if (!seenFirstDetail) {
            seenFirstDetail = true
            prevStatusKey = status
            prevErrorCode = error
            return events
        }

        // Bed cooled below the safe-to-remove threshold (only after a completion we witnessed
        // AND only if the bed was actually hot at some point).
        if (awaitingCooldown) {
            val bed = detail.platTemp
            when {
                status in ACTIVE_PRINT_STATES -> { awaitingCooldown = false; bedWasHot = false } // new job
                bed != null && bed < BED_SAFE_TEMP_C -> {
                    awaitingCooldown = false
                    if (notifyOnCooled) events.add(PrinterEvent.PrintCooled)
                }
            }
        }

        // Track whether the bed ever reached the removal threshold this job.
        detail.platTemp?.let { if (it >= BED_SAFE_TEMP_C) bedWasHot = true }

        // Print just finished — arm the cooled watch only when there is hot mass to cool.
        if (status == "completed" && prevStatusKey != "completed") {
            if (notifyOnComplete) events.add(PrinterEvent.PrintCompleted)
            awaitingCooldown = bedWasHot
        }

        // A new error code appeared.
        if (error != null && error != prevErrorCode && notifyOnError) {
            events.add(PrinterEvent.PrinterError(error))
        }

        prevStatusKey = status
        prevErrorCode = error
        return events
    }

    companion object {
        /** Bed temp (°C) below which a finished print is considered safe to remove. */
        const val BED_SAFE_TEMP_C = 40f

        /** Wire statuses that mean a job is actively running (cancels a pending cooldown watch). */
        val ACTIVE_PRINT_STATES = setOf(
            "printing", "working", "busy", "heating", "calibrate_doing",
            "pause", "paused", "pausing", "canceling", "downloading"
        )
    }
}
