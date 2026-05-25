package com.example.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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

    suspend fun getMatlStation(serialNumber: String, checkCode: String): Result<MatlStationInfo> = withContext(Dispatchers.IO) {
        try {
            val reqBody = json.encodeToString(PrinterDetailRequest(serialNumber, checkCode))
            val request = Request.Builder()
                .url("$baseUrl/matlStation")
                .post(reqBody.toRequestBody(mediaType))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext Result.failure(Exception("HTTP ${response.code}"))
                val bodyStr = response.body?.string() ?: return@withContext Result.failure(Exception("Empty body"))
                // Create a temporary decoder for MatlStation wrapper
                @kotlinx.serialization.Serializable
                data class MatlStationWrapper(val code: Int = 0, val matlStation: MatlStationInfo? = null)
                val wrapper = json.decodeFromString<MatlStationWrapper>(bodyStr)
                if (wrapper.code != 0) return@withContext Result.failure(Exception("API Error"))
                wrapper.matlStation?.let { Result.success(it) } ?: Result.failure(Exception("No matlStation"))
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
                @kotlinx.serialization.Serializable
                data class ControlResponseWrapper(val code: Int = 0, val message: String? = null)
                val wrapper = json.decodeFromString<ControlResponseWrapper>(bodyStr)
                if (wrapper.code != 0) return@use Result.failure<Unit>(Exception("API Error: ${wrapper.message}"))
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
