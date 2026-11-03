package com.fob.lanparty.model

enum class Transport {
    WIFI,
    BLUETOOTH,
}

sealed interface SessionState {
    data object Idle : SessionState

    data class Waiting(val transport: Transport) : SessionState

    data class Incoming(
        val transport: Transport,
        val peerName: String,
    ) : SessionState

    data class Connecting(
        val transport: Transport,
        val peerName: String,
    ) : SessionState

    data class Connected(
        val transport: Transport,
        val peerName: String,
    ) : SessionState
}

data class DiscoveredDevice(
    val transport: Transport,
    val id: String,
    val displayName: String,
    val detail: String,
    val wifiHost: String? = null,
    val wifiPort: Int = 0,
    val bluetoothAddress: String? = null,
)

data class DiscoveryState(
    val transport: Transport? = null,
    val devices: List<DiscoveredDevice> = emptyList(),
    val isSearching: Boolean = false,
    val startedAt: Long = 0L,
    val scanFinished: Boolean = false,
    val error: String? = null,
)

data class ChatMessage(
    val id: String,
    val text: String,
    val timestamp: Long,
    val outgoing: Boolean,
)

sealed interface AppEvent {
    data class ConnectionFailed(val message: String) : AppEvent

    data class ConnectionLost(val message: String) : AppEvent

    data class PeerDisconnected(val message: String) : AppEvent

    data class Error(val message: String) : AppEvent
}

data class AppSettings(
    val deviceName: String,
    val darkTheme: Boolean = false,
    val notificationSound: Boolean = true,
)
