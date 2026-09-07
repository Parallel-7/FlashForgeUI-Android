package me.ghost.ffui.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrinterDao {
    @Query("SELECT * FROM printers")
    fun getAllPrinters(): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE serialNumber = :serialNumber LIMIT 1")
    suspend fun getPrinter(serialNumber: String): PrinterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(printer: PrinterEntity)

    @Update
    suspend fun update(printer: PrinterEntity)

    /**
     * Persists identity/capability fields learned from a successful identify. Null arguments keep
     * the stored value (COALESCE) — the legacy TCP `~M115` path learns firmware but not pid or
     * camera URL, and must not wipe fields a previous HTTP identify persisted.
     */
    @Query(
        "UPDATE printers SET modelPid = COALESCE(:pid, modelPid), " +
            "firmwareVersion = COALESCE(:firmware, firmwareVersion), " +
            "cameraStreamUrl = COALESCE(:cameraUrl, cameraStreamUrl) WHERE serialNumber = :serialNumber"
    )
    suspend fun updateIdentity(serialNumber: String, pid: Int?, firmware: String?, cameraUrl: String?)

    @Query("UPDATE printers SET ipAddress = :ip WHERE serialNumber = :serialNumber")
    suspend fun updateAddress(serialNumber: String, ip: String)

    @Query("DELETE FROM printers WHERE serialNumber = :serialNumber")
    suspend fun delete(serialNumber: String)
}
