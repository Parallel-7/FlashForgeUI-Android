package com.example.api

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FlashForgeHttpApi(private val ipAddress: String) {
    private val client = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val baseUrl = "http://$ipAddress:8898"
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    suspend fun getDetail(serialNumber: String, checkCode: String): Result<PrinterDetailResponse> = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(PrinterDetailRequest(serialNumber, checkCode))
            val request = Request.Builder()
                .url("$baseUrl/detail")
                .post(reqBody.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val wrapper = json.decodeFromString<PrinterDetailWrapper>(bodyStr)
                if (wrapper.code != 0) return@withContext Result.failure(Exception("API Error: ${wrapper.message}"))
                wrapper.detail?.let { Result.success(it) } ?: Result.failure(Exception("No detail in response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches the `/product` capability flags. Per the recommended init sequence this also doubles
     * as credential validation — a non-zero `code` means the serial/checkCode pair was rejected.
     */
    suspend fun getProduct(serialNumber: String, checkCode: String): Result<Product> = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(PrinterDetailRequest(serialNumber, checkCode))
            val request = Request.Builder()
                .url("$baseUrl/product")
                .post(reqBody.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val wrapper = json.decodeFromString<ProductWrapper>(bodyStr)
                if (wrapper.code != 0) return@withContext Result.failure(Exception("API Error: ${wrapper.message}"))
                wrapper.product?.let { Result.success(it) } ?: Result.failure(Exception("No product in response"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun controlLight(serialNumber: String, checkCode: String, on: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        val req = ControlRequest(
            serialNumber = serialNumber,
            checkCode = checkCode,
            payload = ControlPayload("lightControl_cmd", json.encodeToJsonElement(LightControlArgs(if (on) "open" else "close")))
        )
        postControl(req)
    }

    suspend fun controlTemp(serialNumber: String, checkCode: String, heaterName: String, target: Int): Result<Unit> = withContext(Dispatchers.IO) {
        val args = when (heaterName) {
            "Nozzle" -> TemperatureCtlArgs(rightTemp = target)
            "Bed" -> TemperatureCtlArgs(platTemp = target)
            else -> TemperatureCtlArgs()
        }
        val req = ControlRequest(
            serialNumber = serialNumber,
            checkCode = checkCode,
            payload = ControlPayload("temperatureCtl_cmd", json.encodeToJsonElement(args))
        )
        postControl(req)
    }

    /**
     * Controls the 5M Pro air filtration via `circulateCtl_cmd`. [internal] / [external] are each
     * `"open"` or `"close"`; the caller (backend) maps a high-level mode to this pair.
     */
    suspend fun controlFiltration(serialNumber: String, checkCode: String, internal: String, external: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = ControlRequest(
            serialNumber = serialNumber,
            checkCode = checkCode,
            payload = ControlPayload("circulateCtl_cmd", json.encodeToJsonElement(CirculateCtlArgs(internal, external)))
        )
        postControl(req)
    }

    suspend fun clearPlatform(serialNumber: String, checkCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = ControlRequest(
            serialNumber = serialNumber,
            checkCode = checkCode,
            payload = ControlPayload("stateCtrl_cmd", json.encodeToJsonElement(StateCtrlArgs("setClearPlatform")))
        )
        postControl(req)
    }
    
    suspend fun pauseJob(serialNumber: String, checkCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = ControlRequest(
            serialNumber = serialNumber,
            checkCode = checkCode,
            payload = ControlPayload("jobCtl_cmd", json.encodeToJsonElement(JobCtlArgs(action = "pause")))
        )
        postControl(req)
    }
    
    suspend fun resumeJob(serialNumber: String, checkCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = ControlRequest(
            serialNumber = serialNumber,
            checkCode = checkCode,
            payload = ControlPayload("jobCtl_cmd", json.encodeToJsonElement(JobCtlArgs(action = "continue")))
        )
        postControl(req)
    }
    
    suspend fun cancelJob(serialNumber: String, checkCode: String): Result<Unit> = withContext(Dispatchers.IO) {
        val req = ControlRequest(
            serialNumber = serialNumber,
            checkCode = checkCode,
            payload = ControlPayload("jobCtl_cmd", json.encodeToJsonElement(JobCtlArgs(action = "cancel")))
        )
        postControl(req)
    }

    // ── File management (Phase 4) ──────────────────────────────────────────────

    /**
     * Fetches the recent-files list via `POST /gcodeList`. Prefers the rich [GcodeListWrapper.gcodeListDetail]
     * (AD5X, carries per-tool material data); otherwise normalizes the legacy [GcodeListWrapper.gcodeList],
     * which can be a JSON array of either bare filename strings or file objects.
     */
    suspend fun getRecentFileList(serialNumber: String, checkCode: String): Result<List<FFGcodeFileEntry>> = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(PrinterDetailRequest(serialNumber, checkCode))
            val request = Request.Builder()
                .url("$baseUrl/gcodeList")
                .post(reqBody.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val wrapper = json.decodeFromString<GcodeListWrapper>(bodyStr)
                if (wrapper.code != 0) return@withContext Result.failure(Exception("API Error: ${wrapper.message}"))

                wrapper.gcodeListDetail?.takeIf { it.isNotEmpty() }?.let { return@withContext Result.success(it) }

                val legacy = wrapper.gcodeList.orEmpty().mapNotNull { element ->
                    val asString = (element as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                    if (asString != null) {
                        FFGcodeFileEntry(gcodeFileName = asString)
                    } else {
                        // Already a file object — decode into the entry shape.
                        runCatching { json.decodeFromJsonElement(FFGcodeFileEntry.serializer(), element) }.getOrNull()
                    }
                }
                Result.success(legacy)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches a file's thumbnail via `POST /gcodeThumb`. Returns the decoded PNG bytes, or `null`
     * when the file has no thumbnail (a success response with empty/absent `imageData`).
     */
    suspend fun getGcodeThumbnail(serialNumber: String, checkCode: String, fileName: String): Result<ByteArray?> = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(GcodeThumbRequest(serialNumber, checkCode, fileName))
            val request = Request.Builder()
                .url("$baseUrl/gcodeThumb")
                .post(reqBody.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val wrapper = json.decodeFromString<GcodeThumbWrapper>(bodyStr)
                if (wrapper.code != 0) return@withContext Result.failure(Exception("API Error: ${wrapper.message}"))
                val data = wrapper.imageData?.takeIf { it.isNotBlank() }
                    ?: return@withContext Result.success(null)
                Result.success(Base64.decode(data, Base64.DEFAULT))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Starts a print of a file already on the printer via `POST /printGcode`. The full payload shape
     * is sent for all modern firmware; [useMatlStation]/[materialMappings] drive AD5X multi-color
     * behavior (raw JSON array, NOT base64). Empty mappings + `useMatlStation=false` is a plain
     * single-color/legacy print.
     */
    suspend fun printGcode(req: PrintGcodeRequest): Result<Unit> =
        postPrint(json.encodeToString(req))

    /**
     * Minimal `/printGcode` payload for pre-3.1.3 firmware, which doesn't understand the
     * material-station fields. The reference lib sends only these four keys for old firmware.
     */
    suspend fun printGcodeLegacy(serialNumber: String, checkCode: String, fileName: String, levelingBeforePrint: Boolean): Result<Unit> =
        postPrint(json.encodeToString(PrintGcodeRequestLegacy(serialNumber, checkCode, fileName, levelingBeforePrint)))

    private suspend fun postPrint(bodyStr: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/printGcode")
                .post(bodyStr.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val resp = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                val wrapper = json.decodeFromString<ControlResponseWrapper>(resp)
                if (wrapper.code != 0) return@withContext Result.failure(Exception("API Error: ${wrapper.message}"))
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun postControl(reqObj: ControlRequest): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val reqBodyStr = json.encodeToString(reqObj)
            val request = Request.Builder()
                .url("$baseUrl/control")
                .post(reqBodyStr.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use Result.failure<Unit>(Exception("HTTP ${response.code}"))
                val bodyStr = response.body?.string() ?: return@use Result.failure<Unit>(Exception("Empty body"))
                val wrapper = json.decodeFromString<ControlResponseWrapper>(bodyStr)
                if (wrapper.code != 0) return@use Result.failure<Unit>(Exception("API Error: ${wrapper.message}"))
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/** Minimal `{code, message}` envelope shared by `/control` and `/printGcode` responses. */
@kotlinx.serialization.Serializable
private data class ControlResponseWrapper(val code: Int = 0, val message: String? = null)
