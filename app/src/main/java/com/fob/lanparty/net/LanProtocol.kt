package com.fob.lanparty.net

import android.os.Build
import org.json.JSONObject
import java.util.UUID

object LanProtocol {
    const val VERSION = 1
    const val APP_ID = "LANparty"
    const val WIFI_DISCOVERY_PORT = 48765
    const val DISCOVERY_REQUEST = "LANPARTY_DISCOVER_V1"
    const val MAX_LINE_LENGTH = 65_536
    const val MAX_MESSAGE_LENGTH = 4_000

    val BLUETOOTH_UUID: UUID = UUID.fromString("6a2f8e8e-3e57-4b9a-8f95-7b1a5c2d9c41")

    fun localModel(): String = Build.MODEL.orEmpty().take(60)

    fun discoveryResponse(name: String, port: Int): String = JSONObject()
        .put("app", APP_ID)
        .put("version", VERSION)
        .put("type", "offer")
        .put("name", sanitizeName(name, "Устройство"))
        .put("model", localModel())
        .put("port", port)
        .toString()

    fun hello(name: String): JSONObject = JSONObject()
        .put("app", APP_ID)
        .put("version", VERSION)
        .put("type", "hello")
        .put("name", sanitizeName(name, "Устройство"))
        .put("model", localModel())

    fun accepted(name: String): JSONObject = JSONObject()
        .put("type", "accepted")
        .put("name", sanitizeName(name, "Устройство"))

    fun rejected(): JSONObject = JSONObject()
        .put("type", "rejected")

    fun message(id: String, text: String, timestamp: Long): JSONObject = JSONObject()
        .put("type", "message")
        .put("id", id)
        .put("text", text.take(MAX_MESSAGE_LENGTH))
        .put("timestamp", timestamp)

    fun disconnect(): JSONObject = JSONObject().put("type", "disconnect")

    fun parse(raw: String): JSONObject? = runCatching { JSONObject(raw) }.getOrNull()

    fun isLanParty(payload: JSONObject): Boolean =
        payload.optString("app") == APP_ID && payload.optInt("version", 0) == VERSION

    fun sanitizeName(value: String?, fallback: String): String {
        val clean = value.orEmpty()
            .filterNot { it.isISOControl() }
            .trim()
            .take(48)
        return clean.ifBlank { fallback }
    }
}
