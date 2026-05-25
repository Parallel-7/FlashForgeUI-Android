package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "printers")
data class PrinterEntity(
    @PrimaryKey val serialNumber: String,
    val ipAddress: String,
    val name: String,
    val checkCode: String
)
