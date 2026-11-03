package com.fob.lanparty.net

import android.content.Context
import android.util.Log
import com.fob.lanparty.R
import com.fob.lanparty.data.SettingsRepository
import com.fob.lanparty.model.AppEvent
import com.fob.lanparty.model.ChatMessage
import com.fob.lanparty.model.DiscoveredDevice
import com.fob.lanparty.model.DiscoveryState
import com.fob.lanparty.model.SessionState
import com.fob.lanparty.model.Transport
import com.fob.lanparty.service.ConnectionService
import com.fob.lanparty.service.NotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

internal class ConnectionManager(
    context: Context,
    private val settings: SettingsRepository,
    private val notifications: NotificationHelper,
) : TransportListener {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionLock = Any()

    private val wifiTransport = WifiTransport(
        context = appContext,
        scope = scope,
        listener = this,
        localName = settings::resolvedDeviceName,
    )
    private val bluetoothTransport = BluetoothTransport(
        context = appContext,
        scope = scope,
        listener = this,
        localName = settings::resolvedDeviceName,
    )

    private val _session = MutableStateFlow<SessionState>(SessionState.Idle)
    val session: StateFlow<SessionState> = _session.asStateFlow()

    private val _discovery = MutableStateFlow(DiscoveryState())
    val discovery: StateFlow<DiscoveryState> = _discovery.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _appVisible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    private val _events = MutableSharedFlow<AppEvent>(extraBufferCapacity = 32)
    val events = _events.asSharedFlow()

    private val _openChatRequests = MutableStateFlow(0L)
    val openChatRequests: StateFlow<Long> = _openChatRequests.asStateFlow()

    @Volatile
    private var currentLink: SocketLink? = null

    @Volatile
    private var currentPeerName: String? = null

    private var sessionJob: Job? = null
    private var pendingIncoming: PendingLink? = null

    @Volatile
    private var serviceRunning = false

    fun setAppVisible(visible: Boolean) {
        if (_appVisible.value == visible) return
        _appVisible.value = visible
        if (!visible) {
            handleBackground()
        } else {
            notifications.clearMessageNotification()
            notifications.clearDisconnectNotification()
        }
    }

    fun requestOpenChat() {
        _openChatRequests.value += 1
        notifications.clearMessageNotification()
    }

    fun startDiscovery(transport: Transport) {
        if (_session.value !is SessionState.Idle) return
        stopDiscoveryInternal(clear = true)
        stopAllTransports(waiting = true, connecting = true)
        _discovery.value = DiscoveryState(
            transport = transport,
            isSearching = true,
            startedAt = System.currentTimeMillis(),
        )
        when (transport) {
            Transport.WIFI -> wifiTransport.startDiscovery()
            Transport.BLUETOOTH -> bluetoothTransport.startDiscovery()
        }
    }

    fun refreshDiscovery() {
        val transport = _discovery.value.transport ?: return
        startDiscovery(transport)
    }

    fun stopDiscovery() {
        stopDiscoveryInternal(clear = true)
    }

    fun startWaiting(transport: Transport) {
        if (_session.value !is SessionState.Idle) return
        stopDiscoveryInternal(clear = true)
        stopAllTransports(waiting = true, connecting = true)
        pendingIncoming = null
        _session.value = SessionState.Waiting(transport)
        transportFor(transport).startWaiting()
    }

    fun cancelWaiting() {
        if (_session.value !is SessionState.Waiting) return
        stopAllTransports(waiting = true, connecting = true)
        pendingIncoming = null
        _session.value = SessionState.Idle
    }

    fun connect(device: DiscoveredDevice) {
        if (_session.value !is SessionState.Idle) return
        stopDiscoveryInternal(clear = false)
        stopAllTransports(waiting = true, connecting = true)
        pendingIncoming = null
        _session.value = SessionState.Connecting(device.transport, device.displayName)
        transportFor(device.transport).connect(device)
    }

    fun cancelConnecting() {
        if (_session.value !is SessionState.Connecting) return
        _session.value = SessionState.Idle
        stopAllTransports(waiting = false, connecting = true)
    }

    fun acceptIncoming() {
        val pending = pendingIncoming ?: return
        pendingIncoming = null
        transportFor(pending.transport).stopWaiting()
        scope.launch {
            val sent = runCatching {
                pending.link.send(LanProtocol.accepted(settings.resolvedDeviceName()))
            }.isSuccess
            if (sent) {
                establishConnected(pending)
            } else {
                pending.link.close()
                returnToWaiting(pending.transport)
                _events.tryEmit(AppEvent.Error(appContext.getString(R.string.connection_error)))
            }
        }
    }

    fun rejectIncoming() {
        val pending = pendingIncoming ?: return
        pendingIncoming = null
        transportFor(pending.transport).stopWaiting()
        returnToWaiting(pending.transport)
        scope.launch {
            runCatching { pending.link.send(LanProtocol.rejected()) }
            pending.link.close()
        }
    }

    fun sendMessage(text: String): Boolean {
        val normalized = text.trim()
        if (normalized.isEmpty()) return false
        if (_session.value !is SessionState.Connected) return false
        val link = currentLink ?: return false
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = normalized.take(LanProtocol.MAX_MESSAGE_LENGTH),
            timestamp = System.currentTimeMillis(),
            outgoing = true,
        )
        _messages.update { it + message }
        scope.launch {
            try {
                link.send(LanProtocol.message(message.id, message.text, message.timestamp))
            } catch (_: Exception) {
                loseActiveSession(link, AppEvent.ConnectionLost(
                    appContext.getString(R.string.connection_lost),
                ))
            }
        }
        return true
    }

    fun disconnect() {
        val link = synchronized(sessionLock) {
            val active = currentLink ?: return
            currentLink = null
            val job = sessionJob
            sessionJob = null
            job?.cancel()
            active
        }
        val peer = currentPeerName
        clearConnectedState()
        scope.launch {
            runCatching { link.send(LanProtocol.disconnect()) }
            link.close()
        }
        if (peer != null) {
            Log.d(TAG, "Disconnected from $peer")
        }
    }

    override fun onDeviceFound(device: DiscoveredDevice) {
        if (_session.value !is SessionState.Idle) return
        _discovery.update { state ->
            if (state.transport != device.transport) {
                state
            } else {
                val updated = state.devices.filterNot { it.id == device.id } + device
                state.copy(devices = updated.sortedBy { it.displayName.lowercase() })
            }
        }
    }

    override fun onDiscoveryFinished() {
        _discovery.update {
            if (it.isSearching) it.copy(isSearching = false, scanFinished = true) else it
        }
    }

    override fun onDiscoveryError(message: String) {
        _discovery.update {
            if (it.isSearching) {
                it.copy(isSearching = false, scanFinished = true, error = message)
            } else {
                it
            }
        }
    }

    override fun onWaitingError(message: String) {
        if (_session.value !is SessionState.Waiting) return
        stopAllTransports(waiting = true, connecting = false)
        pendingIncoming = null
        _session.value = SessionState.Idle
        _events.tryEmit(AppEvent.Error(message))
    }

    override fun onIncomingLink(pending: PendingLink) {
        if (_session.value !is SessionState.Waiting) {
            pending.link.close()
            return
        }
        pendingIncoming = pending
        _session.value = SessionState.Incoming(pending.transport, pending.peerName)
    }

    override fun onConnectOutcome(outcome: ConnectOutcome) {
        val connecting = _session.value as? SessionState.Connecting ?: run {
            when (outcome) {
                is ConnectOutcome.Accepted -> outcome.link.close()
                else -> Unit
            }
            return
        }
        when (outcome) {
            is ConnectOutcome.Accepted -> establishConnected(
                PendingLink(outcome.link, outcome.peerName, connecting.transport),
            )

            ConnectOutcome.Rejected -> {
                _session.value = SessionState.Idle
                _events.tryEmit(
                    AppEvent.ConnectionFailed(
                        appContext.getString(R.string.connection_rejected),
                    ),
                )
            }

            ConnectOutcome.Timeout -> {
                _session.value = SessionState.Idle
                _events.tryEmit(
                    AppEvent.ConnectionFailed(
                        appContext.getString(R.string.peer_did_not_answer),
                    ),
                )
            }

            ConnectOutcome.Failed -> {
                _session.value = SessionState.Idle
                _events.tryEmit(
                    AppEvent.ConnectionFailed(
                        appContext.getString(R.string.connection_error),
                    ),
                )
            }
        }
    }

    private fun establishConnected(pending: PendingLink) {
        if (_session.value is SessionState.Connected) {
            pending.link.close()
            return
        }
        stopAllTransports(waiting = true, connecting = false)
        pendingIncoming = null
        currentLink = pending.link
        currentPeerName = pending.peerName
        _messages.value = emptyList()
        _session.value = SessionState.Connected(pending.transport, pending.peerName)
        startReadLoop(pending.link)
        startConnectionService(pending.peerName, pending.transport)
    }

    private fun startReadLoop(link: SocketLink) {
        sessionJob = scope.launch {
            var failure = false
            try {
                while (isActive) {
                    val raw = link.readLine() ?: break
                    val json = LanProtocol.parse(raw) ?: continue
                    when (json.optString("type")) {
                        "message" -> handleIncomingMessage(json)
                        "disconnect" -> break
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failure = true
            }
            loseActiveSession(
                link,
                if (failure) {
                    AppEvent.ConnectionLost(appContext.getString(R.string.connection_lost))
                } else {
                    AppEvent.PeerDisconnected(appContext.getString(R.string.peer_disconnected))
                },
            )
        }
    }

    private fun handleIncomingMessage(json: JSONObject) {
        val text = json.optString("text").take(LanProtocol.MAX_MESSAGE_LENGTH)
        if (text.isBlank()) return
        val message = ChatMessage(
            id = UUID.randomUUID().toString(),
            text = text,
            timestamp = System.currentTimeMillis(),
            outgoing = false,
        )
        _messages.update { it + message }
        if (!_appVisible.value) {
            currentPeerName?.let { notifications.showMessage(it, text) }
        }
    }

    private fun loseActiveSession(link: SocketLink, event: AppEvent) {
        val visible = synchronized(sessionLock) {
            if (currentLink !== link) return
            currentLink = null
            sessionJob = null
            _appVisible.value
        }
        link.close()
        clearConnectedState()
        if (!visible) {
            val peerEvent = event as? AppEvent.PeerDisconnected
            if (peerEvent != null) {
                notifications.showPeerDisconnected(
                    settings.current.notificationSound,
                )
            }
        }
        _events.tryEmit(event)
    }

    private fun clearConnectedState() {
        currentPeerName = null
        pendingIncoming = null
        _messages.value = emptyList()
        _session.value = SessionState.Idle
        stopConnectionService()
    }

    private fun startConnectionService(peerName: String, transport: Transport) {
        if (serviceRunning) return
        serviceRunning = true
        runCatching {
            ConnectionService.start(appContext, peerName, transport)
        }.onFailure {
            serviceRunning = false
            Log.e(TAG, "Unable to start foreground service", it)
        }
    }

    private fun stopConnectionService() {
        if (!serviceRunning) {
            ConnectionService.stop(appContext)
            return
        }
        serviceRunning = false
        ConnectionService.stop(appContext)
    }

    private fun handleBackground() {
        stopDiscoveryInternal(clear = true)
        when (_session.value) {
            is SessionState.Waiting -> {
                stopAllTransports(waiting = true, connecting = false)
                _session.value = SessionState.Idle
            }

            is SessionState.Incoming -> {
                val pending = pendingIncoming
                pendingIncoming = null
                stopAllTransports(waiting = true, connecting = false)
                _session.value = SessionState.Idle
                pending?.let { active ->
                    scope.launch {
                        runCatching { active.link.send(LanProtocol.rejected()) }
                        active.link.close()
                    }
                }
            }

            is SessionState.Connecting -> {
                stopAllTransports(waiting = false, connecting = true)
                _session.value = SessionState.Idle
            }

            else -> Unit
        }
    }

    private fun returnToWaiting(transport: Transport) {
        stopAllTransports(waiting = true, connecting = true)
        _session.value = SessionState.Waiting(transport)
        transportFor(transport).startWaiting()
    }

    private fun stopDiscoveryInternal(clear: Boolean) {
        wifiTransport.stopDiscovery()
        bluetoothTransport.stopDiscovery()
        _discovery.update {
            if (clear) {
                DiscoveryState()
            } else {
                it.copy(isSearching = false, scanFinished = true)
            }
        }
    }

    private fun stopAllTransports(waiting: Boolean, connecting: Boolean) {
        if (waiting) {
            wifiTransport.stopWaiting()
            bluetoothTransport.stopWaiting()
        }
        if (connecting) {
            wifiTransport.stopConnecting()
            bluetoothTransport.stopConnecting()
        }
    }

    private fun transportFor(transport: Transport): NetworkTransport = when (transport) {
        Transport.WIFI -> wifiTransport
        Transport.BLUETOOTH -> bluetoothTransport
    }

    private companion object {
        const val TAG = "ConnectionManager"
    }
}
