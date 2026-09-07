package me.ghost.ffui.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-memory Room test for [PrinterDao.updateAddress] — the persistence half of discovery-first
 * connect resolution. Verifies a DHCP rotation rewrite touches ONLY the address column: the
 * identity/capability fields a previous identify persisted must survive it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PrinterDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: PrinterDao

    private fun entity(serial: String, ip: String) = PrinterEntity(
        serialNumber = serial,
        checkCode = "1234",
        name = "Printer $serial",
        ipAddress = ip,
        modelPid = 38,
        firmwareVersion = "3.1.0",
        cameraStreamUrl = "http://$ip:8080/?action=stream",
        customLedEnabled = false
    )

    @Before
    fun setUp() {
        val context = org.robolectric.RuntimeEnvironment.getApplication() as Context
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.printerDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun updateAddress_rewritesOnlyTheAddress() = runTest {
        dao.insert(entity("SN-A", "192.168.1.50"))
        dao.insert(entity("SN-B", "192.168.1.51"))

        dao.updateAddress("SN-A", "192.168.1.99")

        val updated = dao.getPrinter("SN-A")
        assertNotNull(updated)
        assertEquals("192.168.1.99", updated!!.ipAddress)
        // Identity/capability columns must survive an address-only update.
        assertEquals("SN-A", updated.serialNumber)
        assertEquals("1234", updated.checkCode)
        assertEquals(38, updated.modelPid)
        assertEquals("3.1.0", updated.firmwareVersion)
        // Other printers are untouched.
        assertEquals("192.168.1.51", dao.getPrinter("SN-B")?.ipAddress)
    }

    @Test
    fun updateAddress_unknownSerialIsANoOp() = runTest {
        dao.insert(entity("SN-A", "192.168.1.50"))

        dao.updateAddress("SN-MISSING", "192.168.1.99")

        assertEquals("192.168.1.50", dao.getPrinter("SN-A")?.ipAddress)
        assertEquals(null, dao.getPrinter("SN-MISSING"))
    }
}
