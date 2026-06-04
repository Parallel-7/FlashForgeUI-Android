package me.ghost.ffui.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.ghost.ffui.api.SpoolmanApi
import me.ghost.ffui.api.SpoolPatchBody
import me.ghost.ffui.api.SpoolmanSpool

/**
 * Load state for the Spoolman spool list.
 */
sealed class SpoolmanLoadState {
    /** Initial state — no fetch attempted yet. */
    data object Idle : SpoolmanLoadState()
    /** A fetch is in progress. */
    data object Loading : SpoolmanLoadState()
    /** Spools were loaded successfully. */
    data object Loaded : SpoolmanLoadState()
    /** The last fetch failed. */
    data class Error(val message: String) : SpoolmanLoadState()
    /** Spoolman is not configured (no base URL set or integration disabled). */
    data object NotConfigured : SpoolmanLoadState()
}

/**
 * Repository that owns the [SpoolmanApi] instance and exposes spool data as [StateFlow]s.
 *
 * Constructed manually (like [PrinterRepository]) and wired through [FfuiApplication] /
 * [MainViewModel][me.ghost.ffui.ui.MainViewModel]. The API instance is rebuilt whenever the
 * base URL changes (collected from [SettingsDataStore.spoolmanBaseUrl]).
 *
 * All API calls run on [Dispatchers.IO].
 *
 * @property settings The app's [SettingsDataStore], used to read the base URL and enabled flag.
 */
class SpoolmanRepository(private val settings: SettingsDataStore) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var api: SpoolmanApi? = null

    private val _spools = MutableStateFlow<List<SpoolmanSpool>>(emptyList())
    val spools: StateFlow<List<SpoolmanSpool>> = _spools.asStateFlow()

    private val _loadState = MutableStateFlow<SpoolmanLoadState>(SpoolmanLoadState.Idle)
    val loadState: StateFlow<SpoolmanLoadState> = _loadState.asStateFlow()

    init {
        // Rebuild the API instance when the base URL changes.
        scope.launch {
            settings.spoolmanBaseUrl.collect { url ->
                api = if (url.isNotBlank()) {
                    try {
                        SpoolmanApi(url)
                    } catch (_: Exception) {
                        null
                    }
                } else {
                    null
                }
            }
        }
    }

    /**
     * Fetches spools from the Spoolman server. Sets [loadState] to [SpoolmanLoadState.Loading]
     * during the fetch and [SpoolmanLoadState.Loaded] on success.
     *
     * @param allowArchived Whether to include archived spools.
     * @param sort Sort expression (e.g. `"filament.name:asc"`), or `null` for default order.
     */
    suspend fun refresh(allowArchived: Boolean = false, sort: String? = null) {
        val currentApi = api
        if (currentApi == null) {
            _loadState.value = SpoolmanLoadState.NotConfigured
            return
        }

        _loadState.value = SpoolmanLoadState.Loading
        val result = currentApi.getSpools(allowArchived, sort)
        if (result.isSuccess) {
            _spools.value = result.getOrDefault(emptyList())
            _loadState.value = SpoolmanLoadState.Loaded
        } else {
            _loadState.value = SpoolmanLoadState.Error(
                result.exceptionOrNull()?.message ?: "Unknown error"
            )
        }
    }

    /**
     * Fetches a single spool by id. A read-only lookup (no list refresh) — used by the dashboard
     * scan-to-set-slot flow where the spool list may never have been loaded.
     *
     * @param spoolId The spool ID.
     * @return The spool, or an error.
     */
    suspend fun getSpool(spoolId: Int): Result<SpoolmanSpool> {
        val currentApi = api ?: return Result.failure(IllegalStateException("Spoolman not configured"))
        return currentApi.getSpool(spoolId)
    }

    /**
     * Deducts filament weight from a spool. Refreshes the spool list on success.
     *
     * @param spoolId The spool ID.
     * @param grams The weight in grams to deduct.
     * @return The updated spool, or an error.
     */
    suspend fun useWeight(spoolId: Int, grams: Float): Result<SpoolmanSpool> {
        val currentApi = api ?: return Result.failure(IllegalStateException("Spoolman not configured"))
        val result = currentApi.useWeight(spoolId, grams)
        if (result.isSuccess) {
            refresh()
        }
        return result
    }

    /**
     * Updates spool attributes. Refreshes the spool list on success.
     *
     * @param spoolId The spool ID.
     * @param body The fields to update.
     * @return The updated spool, or an error.
     */
    suspend fun patchSpool(spoolId: Int, body: SpoolPatchBody): Result<SpoolmanSpool> {
        val currentApi = api ?: return Result.failure(IllegalStateException("Spoolman not configured"))
        val result = currentApi.patchSpool(spoolId, body)
        if (result.isSuccess) {
            refresh()
        }
        return result
    }

    /**
     * Archives or unarchives a spool. Refreshes the spool list on success.
     *
     * @param spoolId The spool ID.
     * @param archived `true` to archive, `false` to unarchive.
     */
    suspend fun setArchived(spoolId: Int, archived: Boolean): Result<SpoolmanSpool> {
        val currentApi = api ?: return Result.failure(IllegalStateException("Spoolman not configured"))
        val result = currentApi.setArchived(spoolId, archived)
        if (result.isSuccess) {
            refresh()
        }
        return result
    }

    /**
     * Tests connectivity to a Spoolman server at the given [url].
     * Uses a throwaway [SpoolmanApi] instance — does not affect the active API.
     *
     * @param url The base URL to test.
     * @return Success if the server responded with a healthy status, failure otherwise.
     */
    suspend fun testConnection(url: String): Result<Unit> {
        return try {
            val testApi = SpoolmanApi(url)
            testApi.health()
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
