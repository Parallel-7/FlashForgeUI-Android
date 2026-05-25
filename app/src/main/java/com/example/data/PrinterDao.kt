package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PrinterDao {
    @Query("SELECT * FROM printers")
    fun getAllPriters(): Flow<List<PrinterEntity>>

    @Query("SELECT * FROM printers WHERE serialNumber = :serialNumber LIMIT 1")
    suspend fun getPrinter(serialNumber: String): PrinterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(printer: PrinterEntity)
    
    @Query("DELETE FROM printers WHERE serialNumber = :serialNumber")
    suspend fun delete(serialNumber: String)
}
