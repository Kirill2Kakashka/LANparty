package com.fob.lanparty.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.content.ContextCompat
import com.fob.lanparty.R
import com.fob.lanparty.model.DiscoveredDevice
import com.fob.lanparty.model.Transport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

@SuppressLint("MissingPermission")
internal class BluetoothTransport(
    private val context: Context,
    private val scope: CoroutineScope,
    private val listener: TransportListener,
    private val localName: () -> String,
) : NetworkTransport {

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(BluetoothManager::class.java)

    @Volatile
    private var searchJob: Job? = null

    @Volatile
    private var waitJob: Job? = null

    @Volatile
    private var connectJob: Job? = null

    @Volatile
    private var connectSocket: BluetoothSocket? = null

    @Volatile
    private var serverSocket: BluetoothServerSocket? = null

    private var registeredReceiver: BroadcastReceiver? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.bluetoothDevice() ?: return
                    val address = runCatching { device.address }.getOrNull() ?: return
                    val rawName = runCatching { device.name }.getOrNull()
                    val name = LanProtocol.sanitizeName(
                        rawName,
                        context?.getString(R.string.bluetooth_device) ?: "Bluetooth",
                    )
                    listener.onDeviceFound(
                        DiscoveredDevice(
                            transport = Transport.BLUETOOTH,
                            id = "bt:$address",
                            displayName = name,
                            detail = context?.getString(R.string.bluetooth_device)
                                ?: "Bluetooth",
                            bluetoothAddress = address,
                        ),
                    )
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    listener.onDiscoveryFinished()
                }
            }
        }
    }

    override fun startDiscovery() {
        stopDiscovery()
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            listener.onDiscoveryError(context.getString(R.string.bluetooth_not_supported))
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            listener.onDiscoveryError(context.getString(R.string.bluetooth_error))
            return
        }

        searchJob = scope.launch {
            try {
                val filter = IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                }
                val currentReceiver = receiver
                ContextCompat.registerReceiver(
                    context,
                    currentReceiver,
                    filter,
                    ContextCompat.RECEIVER_EXPORTED,
                )
                registeredReceiver = currentReceiver
                runCatching { adapter.cancelDiscovery() }
                if (!adapter.startDiscovery()) {
                    throw IOException("Bluetooth discovery did not start")
                }
                while (isActive) {
                    kotlinx.coroutines.delay(1_000)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                if (isActive) {
                    listener.onDiscoveryError(context.getString(R.string.bluetooth_error))
                }
            } catch (_: Exception) {
                if (isActive) {
                    listener.onDiscoveryError(
                        context.getString(R.string.bluetooth_search_error),
                    )
                }
            } finally {
                unregisterReceiver()
            }
        }
    }

    override fun stopDiscovery() {
        searchJob?.cancel()
        searchJob = null
        unregisterReceiver()
        runCatching { bluetoothManager?.adapter?.cancelDiscovery() }
    }

    override fun startWaiting() {
        stopWaiting()
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            listener.onWaitingError(context.getString(R.string.bluetooth_not_supported))
            return
        }
        if (!runCatching { adapter.isEnabled }.getOrDefault(false)) {
            listener.onWaitingError(context.getString(R.string.bluetooth_error))
            return
        }

        waitJob = scope.launch {
            var server: BluetoothServerSocket? = null
            try {
                server = adapter.listenUsingInsecureRfcommWithServiceRecord(
                    SERVICE_NAME,
                    LanProtocol.BLUETOOTH_UUID,
                )
                serverSocket = server
                while (isActive) {
                    val socket = try {
                        server.accept()
                    } catch (e: IOException) {
                        if (!isActive) return@launch
                        throw e
                    }
                    val pending = readHelloWithTimeout(socket)
                    if (pending == null) {
                        runCatching { socket.close() }
                        continue
                    }
                    runCatching { server.close() }
                    serverSocket = null
                    listener.onIncomingLink(pending)
                    return@launch
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                if (isActive) {
                    listener.onWaitingError(context.getString(R.string.bluetooth_error))
                }
            } catch (_: Exception) {
                if (isActive) {
                    listener.onWaitingError(context.getString(R.string.connection_error))
                }
            } finally {
                runCatching { server?.close() }
                if (serverSocket === server) serverSocket = null
            }
        }
    }

    override fun stopWaiting() {
        waitJob?.cancel()
        waitJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    override fun connect(device: DiscoveredDevice) {
        stopDiscovery()
        val address = device.bluetoothAddress ?: return
        val adapter = bluetoothManager?.adapter ?: return
        val remote = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return

        connectJob = scope.launch {
            var socket: BluetoothSocket? = null
            var watchdog: Job? = null
            val startedAt = System.currentTimeMillis()
            try {
                runCatching { adapter.cancelDiscovery() }
                val activeSocket = remote.createInsecureRfcommSocketToServiceRecord(
                    LanProtocol.BLUETOOTH_UUID,
                )
                socket = activeSocket
                connectSocket = activeSocket
                if (!isActive) {
                    runCatching { activeSocket.close() }
                    return@launch
                }
                watchdog = scope.launch {
                    delay(HANDSHAKE_TIMEOUT_MS)
                    runCatching { activeSocket.close() }
                }
                activeSocket.connect()
                val link = SocketLink(activeSocket, activeSocket.inputStream, activeSocket.outputStream)
                link.send(LanProtocol.hello(localName()))
                val response = link.readLine()
                if (response == null) {
                    link.close()
                    connectSocket = null
                    listener.onConnectOutcome(ConnectOutcome.Failed)
                    return@launch
                }
                val json = LanProtocol.parse(response)
                when (json?.optString("type")) {
                    "accepted" -> {
                        connectSocket = null
                        val peerName = LanProtocol.sanitizeName(
                            json.optString("name"),
                            context.getString(R.string.bluetooth_device),
                        )
                        listener.onConnectOutcome(ConnectOutcome.Accepted(link, peerName))
                    }

                    "rejected" -> {
                        link.close()
                        connectSocket = null
                        listener.onConnectOutcome(ConnectOutcome.Rejected)
                    }

                    else -> {
                        link.close()
                        connectSocket = null
                        listener.onConnectOutcome(ConnectOutcome.Failed)
                    }
                }
            } catch (e: CancellationException) {
                runCatching { socket?.close() }
                throw e
            } catch (_: Exception) {
                runCatching { socket?.close() }
                val timedOut =
                    System.currentTimeMillis() - startedAt >= HANDSHAKE_TIMEOUT_MS - 1_000
                listener.onConnectOutcome(
                    if (timedOut) ConnectOutcome.Timeout else ConnectOutcome.Failed,
                )
            } finally {
                watchdog?.cancel()
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

    private suspend fun readHelloWithTimeout(socket: BluetoothSocket): PendingLink? {
        val watchdog = scope.launch {
            delay(HELLO_TIMEOUT_MS)
            runCatching { socket.close() }
        }
        return try {
            readHello(socket)
        } finally {
            watchdog.cancel()
        }
    }

    private fun readHello(socket: BluetoothSocket): PendingLink? {
        val link = SocketLink(socket, socket.inputStream, socket.outputStream)
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
        return PendingLink(
            link = link,
            peerName = LanProtocol.sanitizeName(
                json.optString("name"),
                context.getString(R.string.bluetooth_device),
            ),
            transport = Transport.BLUETOOTH,
        )
    }

    private fun unregisterReceiver() {
        registeredReceiver?.let { runCatching { context.unregisterReceiver(it) } }
        registeredReceiver = null
    }

    @Suppress("DEPRECATION")
    private fun Intent.bluetoothDevice(): BluetoothDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }
    }

    private companion object {
        const val SERVICE_NAME = "LANparty"
        const val HELLO_TIMEOUT_MS = 10_000L
        const val HANDSHAKE_TIMEOUT_MS = 60_000L
    }
}
