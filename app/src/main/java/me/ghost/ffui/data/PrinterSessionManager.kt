package me.ghost.ffui.data

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import me.ghost.ffui.notifications.PrinterNotifier
import me.ghost.ffui.api.UdpDiscovery
import me.ghost.ffui.service.PrinterMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Process-lifetime owner of every live [ActivePrinterSession]. Extracted out of the
 * (Activity-scoped) `MainViewModel` so connections — and the completion / cooled / error
 * notifications they raise — can survive the app being backgrounded or closed.
 *
 * There is exactly one instance per process, created in [me.ghost.ffui.FfuiApplication]. The
 * `MainViewModel` is a thin pass-through over the flows and methods here; the foreground
 * [PrinterMonitorService] is a keep-alive shell that holds the process open while sessions run.
 * Neither re-implements any polling, event-detection, or notification logic — that all stays in
 * [ActivePrinterSession] / [PrinterNotifier].
 *
 * Two global settings shape background behaviour (see [SettingsDataStore]):
 *  - `backgroundMonitoringEnabled` — when on, [PrinterMonitorService] is kept running while ≥1
 *    session is connected, so monitoring continues after the app is closed. When off, sessions are
 *    torn down with the app (the legacy behaviour, driven from `MainViewModel.onCleared`).
 *  - `backgroundThrottleEnabled` + `backgroundThrottleSeconds` — when on, the poll cadence is
 *    floored to the chosen interval while the app is backgrounded, trading alert latency for
 *    battery (see [ActivePrinterSession.pollFloorMs]).
 */
class PrinterSessionManager(
    private val appContext: Context,
    val repository: PrinterRepository,
    val settings: SettingsDataStore,
) {
    private val notifier = PrinterNotifier(appContext)

    /**
     * Long-lived scope tied to the process, not to any Activity/ViewModel. Sessions launch their
     * poll loops here so they keep running across configuration changes and while backgrounded.
     * Never cancelled in normal operation — it dies only with the process.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _sessions = MutableStateFlow<Map<String, ActivePrinterSession>>(emptyMap())
    /** All live printer sessions, keyed by serial number. */
    val sessions: StateFlow<Map<String, ActivePrinterSession>> = _sessions

    private val _activeSerial = MutableStateFlow<String?>(null)
    /** Serial number of the printer whose dashboard tab is currently active (visible). */
    val activeSerial: StateFlow<String?> = _activeSerial

    /** The session the user is currently looking at (derived convenience). */
    val activeSession: StateFlow<ActivePrinterSession?> = combine(
        _sessions, _activeSerial
    ) { map, serial ->
        serial?.let { map[it] }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Serials with a discovery-resolution currently in flight; guards double-taps (main-thread confined). */
    private val resolvingAddresses = mutableSetOf<String>()

    private val _needsAddress = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Serials whose *user-tapped* connect missed discovery AND then failed on the saved address at
     * the transport level (host unreachable — not an auth rejection, not a firmware error envelope).
     * The Printers tab renders an address-only re-entry dialog for these. Cleared when the address
     * is updated, the dialog is dismissed, or the session connects after all. Background reconnects
     * (startup / sticky restart) never arm it.
     */
    val needsAddressSerials: StateFlow<Set<String>> = _needsAddress

    // ---- Hot snapshots of the settings that gate background behaviour ----
    // Kept as plain fields (not just flows) so synchronous callers — onCleared, evaluateService,
    // applyThrottle — can read the current value without suspending.
    @Volatile private var backgroundEnabled = false
    @Volatile private var throttleEnabled = false
    @Volatile private var throttleSeconds = SettingsDataStore.THROTTLE_DEFAULT_SECONDS
    // Starts false: a headless service-only restart (process killed, no Activity) never receives a
    // ProcessLifecycleOwner ON_START, so it should be treated as backgrounded (and throttled). A
    // normal launch flips this true the moment the first Activity starts.
    @Volatile private var appInForeground = false
    @Volatile private var started = false

    /** Synchronous snapshot of whether background monitoring is currently enabled. */
    val isBackgroundMonitoringEnabled: Boolean get() = backgroundEnabled

    /**
     * One-time startup (idempotent): wires the process-lifecycle observer, starts the settings
     * collectors, and runs startup-reconnect. Called from `FfuiApplication.onCreate` on the main
     * thread.
     */
    fun start() {
        if (started) return
        started = true

        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { appInForeground = true; applyThrottle() }
            override fun onStop(owner: LifecycleOwner) { appInForeground = false; applyThrottle() }
        })

        scope.launch {
            settings.backgroundMonitoringEnabled.collect {
                backgroundEnabled = it
                applyThrottle()
                evaluateService()
            }
        }
        scope.launch { settings.backgroundThrottleEnabled.collect { throttleEnabled = it; applyThrottle() } }
        scope.launch { settings.backgroundThrottleSeconds.collect { throttleSeconds = it; applyThrottle() } }
    }

    // ---- Startup reconnect ----

    /**
     * Reconnects previously-connected printers per the startup-reconnect setting. Triggered when the
     * UI is (re)opened — i.e. from `MainViewModel.init` — rather than at process start, so reopening
     * the app after a foreground close reconnects just as it did before this manager existed.
     * Idempotent: [connectToPrinter] dedups by serial, so already-live sessions are untouched.
     */
    fun reconnectOnAppOpen() {
        scope.launch { runStartupReconnect() }
    }

    /**
     * Re-establishes monitoring after the OS killed the process and `START_STICKY` relaunched the
     * foreground service headlessly (no UI). Reconnects whatever printers were connected at the
     * time — read from the persisted last-connected set, *independent* of the startup-reconnect mode,
     * since those printers were actively being monitored and should come back. Reads the live setting
     * (not the cached snapshot) to avoid racing the settings collectors on a fresh process. No-op if
     * background monitoring is off; [evaluateService] then stops the stray service.
     */
    fun resumeMonitoringAfterRestart() {
        scope.launch {
            // Sync the snapshot from the fresh read first, so the evaluateService() inside
            // connectToPrinter doesn't race the (still-warming-up) settings collector and stop the
            // very service we're restarting.
            val enabled = settings.backgroundMonitoringEnabled.first()
            backgroundEnabled = enabled
            if (enabled) {
                val serials = settings.lastConnectedSerials.first()
                val lastActive = settings.lastActiveSerial.first()
                for (serial in serials) {
                    repository.getPrinter(serial)?.let { connectToPrinter(it, activate = false) }
                }
                // Sessions land asynchronously (address resolution first), so the tab is restored
                // from the persisted set rather than the not-yet-populated sessions map.
                if (lastActive != null && serials.contains(lastActive)) {
                    setActive(lastActive)
                }
            }
            evaluateService()
        }
    }

    private suspend fun runStartupReconnect() {
        when (settings.startupReconnect.first()) {
            StartupReconnect.ALL -> {
                val serials = settings.lastConnectedSerials.first()
                val lastActive = settings.lastActiveSerial.first()
                for (serial in serials) {
                    repository.getPrinter(serial)?.let { connectToPrinter(it, activate = false) }
                }
                // Sessions land asynchronously (address resolution first), so the tab is restored
                // from the persisted set rather than the not-yet-populated sessions map.
                if (lastActive != null && serials.contains(lastActive)) {
                    setActive(lastActive)
                }
            }
            StartupReconnect.LAST_ACTIVE -> {
                val lastActive = settings.lastActiveSerial.first()
                if (lastActive != null) {
                    repository.getPrinter(lastActive)?.let { connectToPrinter(it) }
                }
            }
            StartupReconnect.OFF -> { /* manual connect only */ }
        }
    }

    // ---- Session management ----

    fun saveAndConnect(printer: PrinterEntity) {
        scope.launch {
            repository.savePrinter(printer)
            // The address was just typed in — no discovery cross-check on this path.
            connectToPrinter(printer, resolveAddress = false)
        }
    }

    /**
     * Opens a live session for [printer], resolving its current address first. If a session for
     * this serial already exists the call just switches the active tab to it (no duplicate
     * connections). Otherwise — for a fresh connect — one short UDP discovery window (~1.5 s,
     * early-exits the moment this serial answers) is matched by exact serial number
     * ([ConnectionResolver]); a changed address is persisted before the session is built, so a
     * DHCP rotation self-heals instead of leaving the saved entry pointing at a dead address.
     * When discovery doesn't see the printer, the saved address is used as before.
     *
     * The session is created asynchronously, after the discovery window; [resolvingAddresses]
     * guards against a second tap starting a second window for the same serial.
     *
     * @param resolveAddress false skips the discovery window (saveAndConnect just typed this IP).
     * @param activate false leaves the active-tab choice alone (startup reconnect sets it
     *   explicitly once it knows which persisted serial was last active).
     * @param userInitiated true arms the needs-address prompt: when discovery missed *and* the
     *   saved address then fails at the transport level, the serial is surfaced in
     *   [needsAddressSerials] exactly once.
     */
    fun connectToPrinter(
        printer: PrinterEntity,
        resolveAddress: Boolean = true,
        activate: Boolean = true,
        userInitiated: Boolean = false
    ) {
        if (_sessions.value.containsKey(printer.serialNumber)) {
            setActive(printer.serialNumber)
            return
        }
        if (!resolvingAddresses.add(printer.serialNumber)) return
        scope.launch {
            var entity = printer
            var discoveryMissed = false
            if (resolveAddress) {
                val resolved = resolveAddressViaDiscovery(printer)
                if (resolved != null) entity = resolved else discoveryMissed = true
            }
            resolvingAddresses.remove(printer.serialNumber)
            val session = ActivePrinterSession(
                initialPrinter = entity,
                appContext = appContext,
                scope = scope,
                onIdentity = { pid, firmware, cameraUrl ->
                    repository.updateIdentity(entity.serialNumber, pid, firmware, cameraUrl)
                },
                onEvent = { p, event ->
                    notifier.notify(p.serialNumber, p.name, event)
                }
            ).apply { pollFloorMs = currentFloorMs() }
            _sessions.update { it + (entity.serialNumber to session) }
            if (activate) setActive(entity.serialNumber)
            session.startSession()
            persistSessionState()
            if (userInitiated && discoveryMissed) watchForAddressPrompt(entity.serialNumber, session)
            evaluateService()
        }
    }

    /**
     * Runs one short discovery window and resolves [printer]'s current address by exact serial
     * match ([ConnectionResolver]). Returns the printer updated to the discovered address —
     * persisted via [PrinterRepository.updateAddress] when it changed — or `null` when the window
     * didn't see the serial (the caller falls back to the saved address). Discovery and the DB
     * write run on `Dispatchers.IO` inside [UdpDiscovery] / Room. A discovery failure of any kind
     * also yields `null`: resolution must never block a connect.
     */
    private suspend fun resolveAddressViaDiscovery(printer: PrinterEntity): PrinterEntity? {
        val found = UdpDiscovery.discoverQuick(appContext, printer.serialNumber)
        val ip = ConnectionResolver.resolve(printer.serialNumber, found) ?: return null
        if (ip == printer.ipAddress) return printer
        repository.updateAddress(printer.serialNumber, ip)
        return printer.copy(ipAddress = ip)
    }

    /**
     * Arms the needs-address prompt for a user-tapped connect that missed discovery: when the
     * session's first non-connecting state is a transport-level [ConnectionState.Offline] (the
     * saved address is unreachable), the serial is surfaced in [needsAddressSerials] exactly
     * once; the poll loop's later retry ticks never re-fire it. The watch ends when the session
     * leaves the sessions map (reconnect/disconnect), and the serial is cleared if the session
     * connects after all.
     */
    private fun watchForAddressPrompt(serial: String, session: ActivePrinterSession) {
        scope.launch {
            var prompted = false
            combine(session.connectionState, sessions) { state, live ->
                state to (live[serial] === session)
            }.takeWhile { (_, alive) -> alive }.collect { (state, _) ->
                when {
                    !prompted && state is ConnectionState.Offline && state.transportFailure -> {
                        prompted = true
                        _needsAddress.update { it + serial }
                    }
                    prompted && state is ConnectionState.Connected ->
                        _needsAddress.update { it - serial }
                }
            }
        }
    }

    /**
     * Persists a user-supplied address for a printer whose saved address went dead, clears the
     * prompt, and reconnects. The live session (if any) was built against the old address, so it
     * is torn down and rebuilt from the updated row. The fresh connect re-runs address resolution,
     * so a wrong entry fails the same way and prompts again.
     */
    fun updateAddressAndReconnect(serial: String, ipAddress: String) {
        scope.launch {
            repository.updateAddress(serial, ipAddress)
            _needsAddress.update { it - serial }
            disconnect(serial, deferServiceEvaluation = true)
            repository.getPrinter(serial)?.let { connectToPrinter(it, userInitiated = true) }
            evaluateService()
        }
    }

    /** Consumes the address prompt for [serial] without changing anything (dialog dismissed). */
    fun dismissNeedsAddress(serial: String) {
        _needsAddress.update { it - serial }
    }

    /**
     * Disconnects a single printer by serial number. [deferServiceEvaluation] as in
     * [connectToPrinter].
     */
    fun disconnect(serial: String, deferServiceEvaluation: Boolean = false) {
        _sessions.value[serial]?.stopSession()
        _sessions.update { it - serial }
        if (_activeSerial.value == serial) {
            _activeSerial.value = _sessions.value.keys.firstOrNull()
        }
        persistSessionState()
        if (!deferServiceEvaluation) evaluateService()
    }

    /** Convenience overload: disconnect whatever printer is currently active. */
    fun disconnect() {
        _activeSerial.value?.let { disconnect(it) }
    }

    /** Stops and removes every session. Used on teardown — deliberately does not persist state. */
    fun disconnectAll() {
        _sessions.value.values.forEach { it.stopSession() }
        _sessions.value = emptyMap()
        _activeSerial.value = null
        evaluateService()
    }

    /** Switches the visible dashboard tab to [serial]. */
    fun setActive(serial: String) {
        _activeSerial.value = serial
        persistSessionState()
    }

    /**
     * Persists edited per-printer settings and pushes them to the live session so UI-only prefs
     * apply immediately. Transport-affecting toggles additionally call [reconnectSession].
     */
    fun updatePrinterSettings(updated: PrinterEntity) {
        scope.launch { repository.updatePrinter(updated) }
        _sessions.value[updated.serialNumber]?.updatePrinterSettings(updated)
    }

    /**
     * Reconnects an already-connected session — used when per-printer settings change so the
     * backend re-resolves capabilities. The disconnect/connect pair defers the foreground-service
     * evaluation (see [connectToPrinter]): evaluating after each half would stop the service when
     * the map momentarily empties and restart it milliseconds later — churning the "Monitoring N
     * printers" notification for no benefit.
     */
    fun reconnectSession(serial: String) {
        scope.launch {
            val wasActive = _activeSerial.value == serial
            disconnect(serial, deferServiceEvaluation = true)
            repository.getPrinter(serial)?.let { entity ->
                connectToPrinter(entity)
                if (wasActive) setActive(serial)
            }
            evaluateService()
        }
    }

    /**
     * Writes the live connected-serials set + active serial to DataStore so startup-reconnect can
     * restore them. Done eagerly on every change. Deliberately NOT called from [disconnectAll]
     * (which fires during teardown): persisting an empty set there would erase the very state we
     * want to reconnect to next launch.
     */
    private fun persistSessionState() {
        val serials = _sessions.value.keys
        val active = _activeSerial.value
        scope.launch {
            settings.setLastConnectedSerials(serials)
            settings.setLastActiveSerial(active)
        }
    }

    // ---- Background service + throttle ----

    /** The poll floor (ms) implied by current settings + foreground state. 0 = no floor. */
    private fun currentFloorMs(): Long =
        if (backgroundEnabled && throttleEnabled && !appInForeground) throttleSeconds * 1_000L else 0L

    /** Pushes the current poll floor to every live session (takes effect on their next tick). */
    private fun applyThrottle() {
        val floor = currentFloorMs()
        _sessions.value.values.forEach { it.pollFloorMs = floor }
    }

    /**
     * Starts or stops the foreground keep-alive service to match current state: it runs exactly
     * when background monitoring is enabled and at least one printer is connected. Always invoked
     * while the app is in the foreground (connect/disconnect and the settings toggle all originate
     * from the UI), so the Android 12+ background-start restriction never applies.
     */
    private fun evaluateService() {
        // In-flight address resolutions count as live here: each will add a session in a moment,
        // so a disconnect+connect pair (or a reconnect) must not stop the service in the gap
        // between the old session's removal and the new session landing.
        if (backgroundEnabled && (_sessions.value.isNotEmpty() || resolvingAddresses.isNotEmpty())) {
            PrinterMonitorService.start(appContext)
        } else {
            PrinterMonitorService.stop(appContext)
        }
    }
}
