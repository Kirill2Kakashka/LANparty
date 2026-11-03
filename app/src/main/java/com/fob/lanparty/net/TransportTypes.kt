package com.fob.lanparty.net

import com.fob.lanparty.model.DiscoveredDevice
import com.fob.lanparty.model.Transport

internal data class PendingLink(
    val link: SocketLink,
    val peerName: String,
    val transport: Transport,
)

internal sealed interface ConnectOutcome {
    data class Accepted(
        val link: SocketLink,
        val peerName: String,
    ) : ConnectOutcome

    data object Rejected : ConnectOutcome

    data object Timeout : ConnectOutcome

    data object Failed : ConnectOutcome
}

internal interface TransportListener {
    fun onDeviceFound(device: DiscoveredDevice)
    fun onDiscoveryFinished()
    fun onDiscoveryError(message: String)
    fun onWaitingError(message: String)
    fun onIncomingLink(pending: PendingLink)
    fun onConnectOutcome(outcome: ConnectOutcome)
}
