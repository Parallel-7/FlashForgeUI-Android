package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [PrinterEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun printerDao(): PrinterDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * v1 → v2: adds the per-printer settings/identity columns introduced alongside the
         * backend abstraction. Pure additive ALTERs with defaults, so existing rows survive.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE printers ADD COLUMN modelPid INTEGER")
                db.execSQL("ALTER TABLE printers ADD COLUMN firmwareVersion TEXT")
                db.execSQL("ALTER TABLE printers ADD COLUMN cameraStreamUrl TEXT")
                db.execSQL("ALTER TABLE printers ADD COLUMN customLedEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE printers ADD COLUMN customCameraEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE printers ADD COLUMN customCameraUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE printers ADD COLUMN forceLegacy INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE printers ADD COLUMN autoMatchMaterials INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "flasher_db"
                ).addMigrations(MIGRATION_1_2).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
