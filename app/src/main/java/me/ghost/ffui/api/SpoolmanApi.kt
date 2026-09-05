package me.ghost.ffui.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OkHttp client for a user-run [Spoolman](https://github.com/Donkie/Spoolman) server.
 *
 * Follows the same structure as [FlashForgeHttpApi]: short LAN timeouts, `kotlinx.serialization`
 * for JSON, `suspend` methods on `Dispatchers.IO`, and `Result<T>` returns. No authentication
 * is required — Spoolman's REST API is unauthenticated on the LAN.
 *
 * The [baseUrl] is normalised on construction: trimmed, stripped of trailing `/`, and ensured
 * to have an `http://` scheme (Spoolman is always plain HTTP on the LAN).
 *
 * @property baseUrl The normalised Spoolman base URL (e.g. `http://192.168.1.117:7912`).
 */
class SpoolmanApi(baseUrl: String) {

    private companion object {
        /**
         * One shared client for every instance and URL — each SpoolmanApi would otherwise own a
         * private connection pool + executor threads, and the repository rebuilds the instance on
         * every base-URL change (settings edits), stacking short-lived pools. Timeouts are
         * identical everywhere, so there is nothing per-instance to configure.
         */
        private val SHARED_CLIENT = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
    }

    private val baseUrl: String

    private val client = SHARED_CLIENT

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    init {
        var normalized = baseUrl.trim().trimEnd('/')
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        // Validate it parses as a real URL
        normalized.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid Spoolman base URL: $baseUrl")
        this.baseUrl = normalized
    }

    /**
     * Health check — GET `/api/v1/health`. Succeeds when the response contains
     * `{"status": "healthy"}`.
     */
    suspend fun health(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/v1/health")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty body"))
                if (!bodyStr.contains("\"healthy\"")) {
                    return@withContext Result.failure(Exception("Unexpected health response: $bodyStr"))
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches spools from GET `/api/v1/spool` with optional query parameters.
     * Search is client-side in v1, so this endpoint does not expose a search parameter.
     *
     * @param allowArchived Whether to include archived spools (maps to `allow_archived` query param).
     * @param sort Sort expression (e.g. `"filament.name:asc"`), or `null` for default order.
     */
    suspend fun getSpools(allowArchived: Boolean, sort: String?): Result<List<SpoolmanSpool>> =
        withContext(Dispatchers.IO) {
            try {
                val baseHttpUrl = "$baseUrl/api/v1/spool".toHttpUrlOrNull()
                    ?: return@withContext Result.failure(Exception("Invalid URL"))
                val urlBuilder = baseHttpUrl.newBuilder()
                    .addQueryParameter("allow_archived", allowArchived.toString())
                if (sort != null) {
                    urlBuilder.addQueryParameter("sort", sort)
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))
                    }
                    val bodyStr = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty body"))
                    val spools = json.decodeFromString<List<SpoolmanSpool>>(bodyStr)
                    Result.success(spools)
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Fetches a single spool by id via GET `/api/v1/spool/{id}`. Used by the dashboard's
     * scan-to-set-slot flow, where the full spool list may never have been loaded.
     *
     * @param spoolId The spool ID.
     * @return The spool, or an error (e.g. HTTP 404 when no such spool exists).
     */
    suspend fun getSpool(spoolId: Int): Result<SpoolmanSpool> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/spool/$spoolId")
                    .get()
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))
                    }
                    val bodyStr = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty body"))
                    Result.success(json.decodeFromString<SpoolmanSpool>(bodyStr))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Deducts filament weight from a spool via PUT `/api/v1/spool/{id}/use`.
     *
     * @param spoolId The spool ID.
     * @param grams The weight in grams to deduct.
     * @return The updated spool.
     */
    suspend fun useWeight(spoolId: Int, grams: Float): Result<SpoolmanSpool> =
        withContext(Dispatchers.IO) {
            try {
                val body = json.encodeToString(SpoolUseBody(use_weight = grams))
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/spool/$spoolId/use")
                    .put(body.toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))
                    }
                    val bodyStr = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty body"))
                    Result.success(json.decodeFromString<SpoolmanSpool>(bodyStr))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Updates spool attributes via PATCH `/api/v1/spool/{id}`.
     * Only non-null fields in [body] are sent; the server ignores absent fields.
     *
     * @param spoolId The spool ID.
     * @param body The fields to update.
     * @return The updated spool.
     */
    suspend fun patchSpool(spoolId: Int, body: SpoolPatchBody): Result<SpoolmanSpool> =
        withContext(Dispatchers.IO) {
            try {
                val bodyStr = json.encodeToString(body)
                val request = Request.Builder()
                    .url("$baseUrl/api/v1/spool/$spoolId")
                    .patch(bodyStr.toRequestBody(mediaType))
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(Exception("HTTP ${response.code}"))
                    }
                    val respStr = response.body?.string()
                        ?: return@withContext Result.failure(Exception("Empty body"))
                    Result.success(json.decodeFromString<SpoolmanSpool>(respStr))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Archives or unarchives a spool. Thin wrapper over [patchSpool].
     *
     * @param spoolId The spool ID.
     * @param archived `true` to archive, `false` to unarchive.
     * @return The updated spool.
     */
    suspend fun setArchived(spoolId: Int, archived: Boolean): Result<SpoolmanSpool> =
        patchSpool(spoolId, SpoolPatchBody(archived = archived))
}
