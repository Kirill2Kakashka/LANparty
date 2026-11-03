package com.fob.lanparty.net

import android.content.Context
import com.fob.lanparty.R
import com.fob.lanparty.model.DiscoveredDevice
import com.fob.lanparty.model.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException

internal class WifiTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: TransportListener,
    private val localName: () -> String,
) : NetworkTransport {

    @Volatile
    private var searchJob: Job? = null

    @Volatile
    private var searchSocket: DatagramSocket? = null

    @Volatile
    private var waitJob: Job? = null

    @Volatile
    private var advertiseJob: Job? = null

    @Volatile
    private var advertiseSocket: DatagramSocket? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var connectSocket: Socket? = null

    override fun startDiscovery() {
        stopDiscovery()
        searchJob = scope.launch {
            try {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.broadcast = true
                    socket.bind(InetSocketAddress(0))
                    socket.soTimeout = 600
                    searchSocket = socket
                    val targets = broadcastTargets()
                    val buffer = ByteArray(2048)

                    while (isActive) {
                        sendProbes(socket, targets)
                        val deadline = System.currentTimeMillis() + 1_500
                        while (isActive && System.currentTimeMillis() < deadline) {
                            val packet = DatagramPacket(buffer, buffer.size)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                break
                            } catch (e: SocketException) {
                                if (!isActive) return@launch
                                throw e
                            }
                            parseOffer(packet)?.let(listener::onDeviceFound)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isActive) {
                    listener.onDiscoveryError(context.getString(R.string.wifi_search_error))
                }
            } finally {
                searchSocket = null
            }
        }
    }

    override fun stopDiscovery() {
        searchJob?.cancel()
        searchJob = null
        runCatching { searchSocket?.close() }
        searchSocket = null
    }

    override fun startWaiting() {
        stopWaiting()
        waitJob = scope.launch {
            var server: ServerSocket? = null
            var udp: DatagramSocket? = null
            var advertiser: Job? = null
            try {
                server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(0))
                }
                serverSocket = server
                udp = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(LanProtocol.WIFI_DISCOVERY_PORT))
                }
                advertiseSocket = udp
                val port = server.localPort
                advertiser = scope.launch {
                    advertise(udp, port)
                }
                advertiseJob = advertiser

                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: SocketException) {
                        if (!isActive) return@launch
                        throw e
                    }
                    socket.tcpNoDelay = true
                    socket.soTimeout = 10_000
                    val pending = readHello(socket)
                    if (pending == null) {
                        runCatching { socket.close() }
                        continue
                    }
                    closeWaitingResources()
                    listener.onIncomingLink(pending)
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isActive) {
                    listener.onWaitingError(context.getString(R.string.connection_error))
                }
            } finally {
                advertiser?.cancel()
                runCatching { udp?.close() }
                runCatching { server?.close() }
                if (advertiseSocket === udp) advertiseSocket = null
                if (serverSocket === server) serverSocket = null
                if (advertiseJob === advertiser) advertiseJob = null
            }
        }
    }

    override fun stopWaiting() {
        waitJob?.cancel()
        waitJob = null
        closeWaitingResources()
    }

    override fun connect(device: DiscoveredDevice) {
        stopDiscovery()
        val host = device.wifiHost ?: return
        val port = device.wifiPort
        connectJob = scope.launch {
            var socket: Socket? = null
            try {
                socket = Socket()
                connectSocket = socket
                if (!isActive) {
                    runCatching { socket.close() }
                    return@launch
                }
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val link = SocketLink(socket, socket.getInputStream(), socket.getOutputStream())
                link.send(LanProtocol.hello(localName()))
                val response = link.readLine()
                if (response == null) {
                    link.close()
                    listener.onConnectOutcome(ConnectOutcome.Failed)
                    return@launch
                }
                val json = LanProtocol.parse(response)
                when (json?.optString("type")) {
                    "accepted" -> {
                        socket.soTimeout = 0
                        connectSocket = null
                        val peerName = LanProtocol.sanitizeName(
                            json.optString("name"),
                            context.getString(R.string.bluetooth_device),
                        )
                        listener.onConnectOutcome(ConnectOutcome.Accepted(link, peerName))
                    }

                    "rejected" -> {
                        link.close()
                        listener.onConnectOutcome(ConnectOutcome.Rejected)
                    }

                    else -> {
                        link.close()
                        listener.onConnectOutcome(ConnectOutcome.Failed)
                    }
                }
            } catch (_: SocketTimeoutException) {
                runCatching { socket?.close() }
                listener.onConnectOutcome(ConnectOutcome.Timeout)
            } catch (e: CancellationException) {
                runCatching { socket?.close() }
                throw e
            } catch (_: Exception) {
                runCatching { socket?.close() }
                listener.onConnectOutcome(ConnectOutcome.Failed)
            } finally {
                if (connectSocket === socket) connectSocket = null
            }
        }
    }

    override fun stopConnecting() {
        connectJob?.cancel()
        connectJob = null
        runCatching { connectSocket?.close() }
        connectSocket = null
    }

    fun shutdown() {
        stopDiscovery()
        stopWaiting()
        stopConnecting()
    }

    private suspend fun advertise(socket: DatagramSocket, tcpPort: Int) {
        val buffer = ByteArray(1024)
        while (currentCoroutineContext().isActive) {
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val request = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                if (request == LanProtocol.DISCOVERY_REQUEST) {
                    val payload = LanProtocol.discoveryResponse(localName(), tcpPort)
                        .toByteArray(Charsets.UTF_8)
                    socket.send(
                        DatagramPacket(
                            payload,
                            payload.size,
                            packet.address,
                            packet.port,
                        ),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: SocketException) {
                return
            } catch (_: IOException) {
                return
            }
        }
    }

    private fun sendProbes(socket: DatagramSocket, targets: List<InetAddress>) {
        val payload = LanProtocol.DISCOVERY_REQUEST.toByteArray(Charsets.UTF_8)
        targets.forEach { address ->
            runCatching {
                socket.send(
                    DatagramPacket(
                        payload,
                        payload.size,
                        address,
                        LanProtocol.WIFI_DISCOVERY_PORT,
                    ),
                )
            }
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val targets = linkedSetOf<InetAddress>()
        runCatching { targets += InetAddress.getByName("255.255.255.255") }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { it.broadcast }
                .forEach { targets += it }
        }
        return targets.toList()
    }

    private fun parseOffer(packet: DatagramPacket): DiscoveredDevice? {
        val raw = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val json = LanProtocol.parse(raw) ?: return null
        if (!LanProtocol.isLanParty(json) || json.optString("type") != "offer") return null
        val port = json.optInt("port", 0)
        if (port !in 1..65535) return null
        val host = packet.address?.hostAddress ?: return null
        val name = LanProtocol.sanitizeName(
            json.optString("name"),
            context.getString(R.string.wifi_device),
        )
        val model = LanProtocol.sanitizeName(
            json.optString("model"),
            context.getString(R.string.wifi_device),
        )
        return DiscoveredDevice(
            transport = Transport.WIFI,
            id = "wifi:$host:$port",
            displayName = name,
            detail = if (model == name) context.getString(R.string.wifi_device) else model,
            wifiHost = host,
            wifiPort = port,
        )
    }

    private fun readHello(socket: Socket): PendingLink? {
        val link = SocketLink(socket, socket.getInputStream(), socket.getOutputStream())
        val raw = try {
            link.readLine()
        } catch (_: Exception) {
            link.close()
            return null
        }
        val json = raw?.let(LanProtocol::parse)
        if (json == null || !LanProtocol.isLanParty(json) || json.optString("type") != "hello") {
            link.close()
            return null
        }
        socket.soTimeout = 0
        return PendingLink(
            link = link,
            peerName = LanProtocol.sanitizeName(
                json.optString("name"),
                context.getString(R.string.wifi_device),
            ),
            transport = Transport.WIFI,
        )
    }

    private fun closeWaitingResources() {
        advertiseJob?.cancel()
        advertiseJob = null
        runCatching { advertiseSocket?.close() }
        advertiseSocket = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val HANDSHAKE_TIMEOUT_MS = 60_000
    }
}
