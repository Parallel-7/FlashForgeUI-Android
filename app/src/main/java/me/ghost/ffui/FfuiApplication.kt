package me.ghost.ffui

import android.app.Application
import me.ghost.ffui.data.AppDatabase
import me.ghost.ffui.data.PrinterRepository
import me.ghost.ffui.data.PrinterSessionManager
import me.ghost.ffui.data.SettingsDataStore

/**
 * Application entry point. Hosts the single process-lifetime [PrinterSessionManager] so live printer
 * sessions (and the notifications they raise) can outlive any Activity/ViewModel and continue under
 * the foreground monitoring service. `MainViewModel` reads the manager from here.
 */
class FfuiApplication : Application() {

    lateinit var sessionManager: PrinterSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        val repository = PrinterRepository(AppDatabase.getDatabase(this).printerDao())
        sessionManager = PrinterSessionManager(
            appContext = applicationContext,
            repository = repository,
            settings = SettingsDataStore(this)
        )
        sessionManager.start()
    }
}
