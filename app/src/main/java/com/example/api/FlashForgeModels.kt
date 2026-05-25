package com.example.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class PrinterDetailRequest(
    val serialNumber: String,
    val checkCode: String
)

@Serializable
data class PrinterDetailWrapper(
    val code: Int = 0,
    val message: String? = null,
    val detail: PrinterDetailResponse? = null
)

@Serializable
data class PrinterDetailResponse(
    val status: String? = null,
    val rightTemp: Float? = null,
    val rightTargetTemp: Float? = null,
    val platTemp: Float? = null,
    val platTargetTemp: Float? = null,
    val chamberTemp: Float? = null,
    val printProgress: Float? = null,
    val estimatedTime: Int? = null,
    val printDuration: Int? = null,
    val printFileName: String? = null,
    val totalFilamentLength: Float? = null,
    val totalFilamentWeight: Float? = null,
    val machineType: String? = null,
    val pid: Int? = null,
    val doorStatus: String? = null,
    val lightStatus: String? = null,
    val hasMatlStation: Boolean? = null,
    val name: String? = null,
    val firmwareVersion: String? = null
)

@Serializable
data class ControlRequest(
    val serialNumber: String,
    val checkCode: String,
    val payload: ControlPayload
)

@Serializable
data class ControlPayload(
    val cmd: String,
    val args: JsonElement
)

@Serializable
data class LightControlArgs(val status: String)

@Serializable
data class TemperatureCtlArgs(val rightTemp: Int = -200, val leftTemp: Int = -200, val platTemp: Int = -200, val chamberTemp: Int = -200)

@Serializable
data class StateCtrlArgs(val action: String)

@Serializable
data class JobCtlArgs(val jobID: String = "", val action: String)

@Serializable
data class MatlStationInfo(
    val currentLoadSlot: Int = 0,
    val currentSlot: Int = 0,
    val slotCnt: Int = 0,
    val slotInfos: List<MatlSlotInfo> = emptyList(),
    val stateAction: Int = 0,
    val stateStep: Int = 0
)

@Serializable
data class MatlSlotInfo(
    val hasFilament: Boolean = false,
    val materialColor: String = "",
    val materialName: String = "",
    val slotId: Int = 0
)
