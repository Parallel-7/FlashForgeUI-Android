package me.ghost.ffui.data

import me.ghost.ffapi.models.FFPrinterDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PrintEventDetector] — the pure notification-event state machine extracted from
 * [ActivePrinterSession]. Pins the first-snapshot baseline suppression, the print-complete
 * transition, the cooled watch (including the bedWasHot gate and reset-on-new-job), and error
 * transitions. All flags on — flag suppression is covered separately.
 */
class PrintEventDetectorTest {

    private val detector = PrintEventDetector()

    private val allOn = NotifyFlags()

    private fun feed(status: String?, bedTemp: Float? = null, errorCode: String? = null) =
        detector.detect(
            FFPrinterDetail(status = status, platTemp = bedTemp, errorCode = errorCode),
            allOn.complete, allOn.cooled, allOn.error,
        )

    /** Opt-in flags — all default true here; individual tests override what they pin. */
    private data class NotifyFlags(
        val complete: Boolean = true,
        val cooled: Boolean = true,
        val error: Boolean = true,
    )

    private fun events(vararg expected: PrinterEvent): List<PrinterEvent> = expected.toList()

    @Test
    fun `first snapshot only baselines, never emits`() {
        // Connecting to an already-completed / already-errored printer must stay quiet.
        assertEquals(events(), feed("completed", bedTemp = 30f, errorCode = "E0100"))
        assertEquals(events(), feed("printing", bedTemp = 60f))
    }

    @Test
    fun `completing a print emits PrintCompleted`() {
        assertEquals(events(), feed("printing", bedTemp = 60f))
        assertEquals(events(PrinterEvent.PrintCompleted), feed("completed", bedTemp = 55f))
    }

    @Test
    fun `consecutive completed snapshots emit exactly once`() {
        feed("printing", bedTemp = 60f)
        assertEquals(events(PrinterEvent.PrintCompleted), feed("completed", bedTemp = 55f))
        assertEquals(events(), feed("completed", bedTemp = 50f))
    }

    @Test
    fun `bed cooling below 40C after a hot completion emits PrintCooled`() {
        feed("printing", bedTemp = 60f)                       // bed seen ≥ 40 → arms the gate
        feed("completed", bedTemp = 55f)                      // PrintCompleted + cooldown watch armed
        assertEquals(events(PrinterEvent.PrintCooled), feed("completed", bedTemp = 39.5f))
    }

    @Test
    fun `a print on an already-cold bed never emits PrintCooled`() {
        // The bedWasHot gate: a short PLA job on a cold bed must not fire "safe to remove" right
        // behind "print complete" (the completion itself still notifies).
        feed("printing", bedTemp = 35f)
        assertEquals(events(PrinterEvent.PrintCompleted), feed("completed", bedTemp = 30f))
        assertEquals(events(), feed("completed", bedTemp = 25f))
    }

    @Test
    fun `a null bed temp after completion does not fire the cooled watch`() {
        feed("printing", bedTemp = 60f)
        feed("completed", bedTemp = 55f)
        assertEquals(events(), feed("completed", bedTemp = null))
    }

    @Test
    fun `starting a new job resets the cooldown watch and the hot-bed gate`() {
        feed("printing", bedTemp = 60f)
        feed("completed", bedTemp = 55f)  // watch armed, bedWasHot = true
        // Any active print state cancels the pending watch and clears bedWasHot...
        assertEquals(events(), feed("printing", bedTemp = 30f))
        // ...so the *next* completion on a cold bed fires PrintCompleted but not PrintCooled.
        assertEquals(events(PrinterEvent.PrintCompleted), feed("completed", bedTemp = 30f))
        assertEquals(events(), feed("completed", bedTemp = 25f))
    }

    @Test
    fun `every active print token cancels a pending cooldown watch`() {
        // "pause" (C5 Pro spelling) and the others all mean "a job is running again".
        PrintEventDetector.ACTIVE_PRINT_STATES.forEach { status ->
            val d = PrintEventDetector()
            d.detect(FFPrinterDetail(status = "printing", platTemp = 60f), true, true, true)
            d.detect(FFPrinterDetail(status = "completed", platTemp = 55f), true, true, true)
            assertEquals(status, emptyList<PrinterEvent>(), d.detect(FFPrinterDetail(status = status, platTemp = 55f), true, true, true))
            // Watch cancelled → no cooled event even once the bed goes cold.
            assertEquals(status, emptyList<PrinterEvent>(), d.detect(FFPrinterDetail(status = "ready", platTemp = 20f), true, true, true))
        }
    }

    @Test
    fun `a new error code emits PrinterError, persistence and repeats differ`() {
        assertEquals(events(), feed("printing", errorCode = ""))   // baseline: no error
        assertEquals(events(PrinterEvent.PrinterError("E0100")), feed("printing", errorCode = "E0100"))
        assertEquals(events(), feed("printing", errorCode = "E0100"))            // same code persists
        assertEquals(events(PrinterEvent.PrinterError("E0104")), feed("printing", errorCode = "E0104"))
        assertEquals(events(), feed("printing", errorCode = "0"))                // "0" is not an error
        assertEquals(events(), feed("printing", errorCode = ""))                 // cleared
        assertEquals(events(PrinterEvent.PrinterError("E0100")), feed("printing", errorCode = "E0100")) // re-fired
    }

    @Test
    fun `opt-out flags suppress the matching events without breaking the machine`() {
        val d = PrintEventDetector()
        d.detect(FFPrinterDetail(status = "printing", platTemp = 60f), notifyOnComplete = false, notifyOnCooled = false, notifyOnError = false)
        assertEquals(emptyList<PrinterEvent>(), d.detect(FFPrinterDetail(status = "completed", platTemp = 55f), notifyOnComplete = false, notifyOnCooled = false, notifyOnError = false))
        assertEquals(emptyList<PrinterEvent>(), d.detect(FFPrinterDetail(status = "completed", platTemp = 30f), notifyOnComplete = false, notifyOnCooled = false, notifyOnError = false))
    }

    @Test
    fun `completion emits in emission order cooled before error`() {
        // One snapshot can carry both a cooled bed and a new error; order is cooled, completed,
        // error (matches the pre-extraction session loop).
        feed("printing", bedTemp = 60f)
        feed("completed", bedTemp = 55f)
        val evs = feed("completed", bedTemp = 30f, errorCode = "E0200")
        assertEquals(listOf(PrinterEvent.PrintCooled, PrinterEvent.PrinterError("E0200")), evs)
    }

    @Test
    fun `status matching is case-insensitive`() {
        feed("Printing", bedTemp = 60f)
        assertEquals(events(PrinterEvent.PrintCompleted), feed("COMPLETED", bedTemp = 55f))
        assertTrue(PrintEventDetector.ACTIVE_PRINT_STATES.isNotEmpty()) // keep the set pinned non-empty
    }
}
