package me.ghost.ffui

import android.app.Application
import me.ghost.ffui.data.AppDatabase
import me.ghost.ffui.data.PrinterRepository
import me.ghost.ffui.data.PrinterSessionManager
import me.ghost.ffui.data.SettingsDataStore
import me.ghost.ffui.data.SpoolmanRepository

/**
 * Application entry point. Hosts the single process-lifetime [PrinterSessionManager] so live printer
 * sessions (and the notifications they raise) can outlive any Activity/ViewModel and continue under
 * the foreground monitoring service. `MainViewModel` reads the manager from here.
 */
class FfuiApplication : Application() {

    lateinit var sessionManager: PrinterSessionManager
        private set

    lateinit var spoolmanRepository: SpoolmanRepository
        private set

    override fun onCreate() {
        super.onCreate()
        val repository = PrinterRepository(AppDatabase.getDatabase(this).printerDao())
        val settings = SettingsDataStore(this)
        sessionManager = PrinterSessionManager(
            appContext = applicationContext,
            repository = repository,
            settings = settings
        )
        spoolmanRepository = SpoolmanRepository(settings)
        sessionManager.start()
    }
}
