package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PrinterDao {
    @Query("SELECT * FROM printers")
    fun getAllPriters(): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE serialNumber = :serialNumber LIMIT 1")
    suspend fun getPrinter(serialNumber: String): PrinterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(printer: PrinterEntity)

    @Update
    suspend fun update(printer: PrinterEntity)

    /** Persists identity/capability fields learned from the first successful /detail. */
    @Query("UPDATE printers SET modelPid = :pid, firmwareVersion = :firmware, cameraStreamUrl = :cameraUrl WHERE serialNumber = :serialNumber")
    suspend fun updateIdentity(serialNumber: String, pid: Int?, firmware: String?, cameraUrl: String?)

    @Query("DELETE FROM printers WHERE serialNumber = :serialNumber")
    suspend fun delete(serialNumber: String)
}
