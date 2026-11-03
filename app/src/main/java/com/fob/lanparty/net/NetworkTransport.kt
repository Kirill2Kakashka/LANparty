package com.fob.lanparty.net

import com.fob.lanparty.model.DiscoveredDevice

internal interface NetworkTransport {
    fun startDiscovery()
    fun stopDiscovery()
    fun startWaiting()
    fun stopWaiting()
    fun connect(device: DiscoveredDevice)
    fun stopConnecting()
}
