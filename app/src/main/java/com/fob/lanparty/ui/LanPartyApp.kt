package com.fob.lanparty.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.fob.lanparty.LanPartyApplication
import com.fob.lanparty.R
import com.fob.lanparty.model.AppEvent
import com.fob.lanparty.model.SessionState
import com.fob.lanparty.model.Transport
import com.fob.lanparty.ui.screens.ChatScreen
import com.fob.lanparty.ui.screens.HomeScreen
import com.fob.lanparty.ui.screens.SearchScreen
import com.fob.lanparty.ui.screens.SettingsScreen
import com.fob.lanparty.ui.screens.TransportChoiceScreen
import com.fob.lanparty.ui.screens.TransportPurpose
import com.fob.lanparty.ui.screens.WaitingScreen
import kotlinx.coroutines.delay

private enum class AppScreen {
    HOME,
    SEARCH_CHOICE,
    ACCEPT_CHOICE,
    SEARCH,
    WAITING,
    CHAT,
    SETTINGS,
}

private enum class BluetoothSetupStage {
    IDLE,
    REQUEST_PERMISSIONS,
    CHECK_ENABLED,
    REQUEST_ENABLE,
    REQUEST_DISCOVERABLE,
    NAVIGATE,
}

private enum class BluetoothTarget {
    SEARCH,
    WAITING,
}

private data class AlertData(
    val title: String,
    val message: String,
)

@Composable
fun LanPartyApp(app: LanPartyApplication) {
    val context = LocalContext.current
    val manager = app.connectionManager
    val settingsRepository = app.settingsRepository

    val settings by settingsRepository.settings.collectAsState()
    val session by manager.session.collectAsState()
    val discovery by manager.discovery.collectAsState()
    val messages by manager.messages.collectAsState()
    val appVisible by manager.appVisible.collectAsState()
    val openChatRequest by manager.openChatRequests.collectAsState()

    val errorTitle = stringResource(R.string.error)
    val appName = stringResource(R.string.app_name)
    val connectionFailedTitle = stringResource(R.string.connection_failed)
    val bluetoothErrorText = stringResource(R.string.bluetooth_error)
    val bluetoothUnsupportedText = stringResource(R.string.bluetooth_not_supported)
    val discoverableDeniedText = stringResource(R.string.bluetooth_discoverable_denied)

    var screenName by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }
    var transportName by rememberSaveable { mutableStateOf<String?>(null) }
    var handledOpenChatRequest by rememberSaveable { mutableLongStateOf(0L) }
    var alert by remember { mutableStateOf<AlertData?>(null) }

    val screen = runCatching { AppScreen.valueOf(screenName) }.getOrDefault(AppScreen.HOME)
    val activeTransport = transportName
        ?.let { runCatching { Transport.valueOf(it) }.getOrNull() }

    var btTarget by remember { mutableStateOf<BluetoothTarget?>(null) }
    var btStage by remember { mutableStateOf(BluetoothSetupStage.IDLE) }

    fun finishBluetoothNavigation() {
        val target = btTarget
        transportName = Transport.BLUETOOTH.name
        screenName = when (target) {
            BluetoothTarget.SEARCH -> AppScreen.SEARCH.name
            BluetoothTarget.WAITING -> AppScreen.WAITING.name
            null -> return
        }
        btTarget = null
        btStage = BluetoothSetupStage.IDLE
    }

    fun failBluetooth(message: String) {
        btTarget = null
        btStage = BluetoothSetupStage.IDLE
        alert = AlertData(
            title = errorTitle,
            message = message,
        )
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    val bluetoothPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.isNotEmpty() && result.values.all { it }) {
            btStage = BluetoothSetupStage.CHECK_ENABLED
        } else {
            failBluetooth(bluetoothErrorText)
        }
    }

    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            btStage = BluetoothSetupStage.CHECK_ENABLED
        } else {
            failBluetooth(bluetoothErrorText)
        }
    }

    val discoverableLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode != Activity.RESULT_CANCELED) {
            btStage = BluetoothSetupStage.NAVIGATE
        } else {
            failBluetooth(discoverableDeniedText)
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(btStage) {
        when (btStage) {
            BluetoothSetupStage.IDLE -> Unit
            BluetoothSetupStage.REQUEST_PERMISSIONS -> {
                val permissions = requiredBluetoothPermissions()
                if (permissions.isEmpty()) {
                    btStage = BluetoothSetupStage.CHECK_ENABLED
                } else {
                    bluetoothPermissionLauncher.launch(permissions)
                }
            }

            BluetoothSetupStage.CHECK_ENABLED -> {
                val adapter = bluetoothAdapter(context)
                when {
                    btTarget == null -> btStage = BluetoothSetupStage.IDLE
                    adapter == null -> failBluetooth(
                        bluetoothUnsupportedText,
                    )

                    !adapter.isEnabled -> btStage = BluetoothSetupStage.REQUEST_ENABLE
                    btTarget == BluetoothTarget.WAITING ->
                        btStage = BluetoothSetupStage.REQUEST_DISCOVERABLE

                    else -> btStage = BluetoothSetupStage.NAVIGATE
                }
            }

            BluetoothSetupStage.REQUEST_ENABLE -> {
                runCatching {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                }.onFailure {
                    failBluetooth(bluetoothErrorText)
                }
            }

            BluetoothSetupStage.REQUEST_DISCOVERABLE -> {
                val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                runCatching {
                    discoverableLauncher.launch(intent)
                }.onFailure {
                    failBluetooth(discoverableDeniedText)
                }
            }

            BluetoothSetupStage.NAVIGATE -> finishBluetoothNavigation()
        }
    }

    fun beginBluetooth(target: BluetoothTarget) {
        btTarget = target
        btStage = if (hasBluetoothPermissions(context)) {
            BluetoothSetupStage.CHECK_ENABLED
        } else {
            BluetoothSetupStage.REQUEST_PERMISSIONS
        }
    }

    LaunchedEffect(session) {
        when (session) {
            is SessionState.Connected -> {
                if (screen != AppScreen.CHAT && screen != AppScreen.SETTINGS) {
                    screenName = AppScreen.CHAT.name
                }
            }

            is SessionState.Idle -> {
                if (screen == AppScreen.CHAT || screen == AppScreen.SETTINGS) {
                    screenName = AppScreen.HOME.name
                }
            }

            else -> Unit
        }
    }

    LaunchedEffect(openChatRequest) {
        if (openChatRequest > handledOpenChatRequest) {
            handledOpenChatRequest = openChatRequest
            if (session is SessionState.Connected) {
                screenName = AppScreen.CHAT.name
            }
        }
    }

    val latestScreenForEvents by rememberUpdatedState(screen)
    LaunchedEffect(Unit) {
        manager.events.collect { event ->
            when (event) {
                is AppEvent.ConnectionFailed -> {
                    alert = AlertData(
                        title = connectionFailedTitle,
                        message = event.message,
                    )
                }

                is AppEvent.PeerDisconnected -> {
                    screenName = AppScreen.HOME.name
                    alert = AlertData(
                        title = appName,
                        message = event.message,
                    )
                }

                is AppEvent.ConnectionLost -> {
                    screenName = AppScreen.HOME.name
                    alert = AlertData(
                        title = errorTitle,
                        message = event.message,
                    )
                }

                is AppEvent.Error -> {
                    if (latestScreenForEvents == AppScreen.WAITING) {
                        screenName = AppScreen.ACCEPT_CHOICE.name
                    }
                    alert = AlertData(
                        title = errorTitle,
                        message = event.message,
                    )
                }
            }
        }
    }

    LaunchedEffect(screen, appVisible, transportName) {
        if (screen == AppScreen.SEARCH && appVisible) {
            activeTransport?.let { manager.startDiscovery(it) }
        } else {
            manager.stopDiscovery()
        }
    }

    LaunchedEffect(screen, appVisible, transportName) {
        if (screen == AppScreen.WAITING && appVisible) {
            if (session is SessionState.Idle) {
                activeTransport?.let { manager.startWaiting(it) }
            }
        } else if (screen != AppScreen.WAITING) {
            manager.cancelWaiting()
        }
    }

    BackHandler(
        enabled = screen != AppScreen.HOME &&
            screen != AppScreen.CHAT &&
            session !is SessionState.Incoming,
    ) {
        when (screen) {
            AppScreen.SEARCH_CHOICE -> screenName = AppScreen.HOME.name
            AppScreen.ACCEPT_CHOICE -> screenName = AppScreen.HOME.name
            AppScreen.SEARCH -> screenName = AppScreen.SEARCH_CHOICE.name
            AppScreen.WAITING -> {
                manager.cancelWaiting()
                screenName = AppScreen.ACCEPT_CHOICE.name
            }

            AppScreen.SETTINGS -> {
                screenName = if (session is SessionState.Connected) {
                    AppScreen.CHAT.name
                } else {
                    AppScreen.HOME.name
                }
            }

            else -> Unit
        }
    }

    when (screen) {
        AppScreen.HOME -> HomeScreen(
            onSearchDevices = { screenName = AppScreen.SEARCH_CHOICE.name },
            onAcceptConnection = { screenName = AppScreen.ACCEPT_CHOICE.name },
            onOpenSettings = { screenName = AppScreen.SETTINGS.name },
        )

        AppScreen.SEARCH_CHOICE -> TransportChoiceScreen(
            purpose = TransportPurpose.SEARCH,
            onBack = { screenName = AppScreen.HOME.name },
            onPick = { transport ->
                when (transport) {
                    Transport.WIFI -> {
                        transportName = Transport.WIFI.name
                        screenName = AppScreen.SEARCH.name
                    }

                    Transport.BLUETOOTH -> beginBluetooth(BluetoothTarget.SEARCH)
                }
            },
        )

        AppScreen.ACCEPT_CHOICE -> TransportChoiceScreen(
            purpose = TransportPurpose.ACCEPT,
            onBack = { screenName = AppScreen.HOME.name },
            onPick = { transport ->
                when (transport) {
                    Transport.WIFI -> {
                        transportName = Transport.WIFI.name
                        screenName = AppScreen.WAITING.name
                    }

                    Transport.BLUETOOTH -> beginBluetooth(BluetoothTarget.WAITING)
                }
            },
        )

        AppScreen.SEARCH -> {
            val transport = activeTransport
            if (transport != null) {
                SearchScreen(
                    transport = transport,
                    discovery = discovery,
                    onBack = { screenName = AppScreen.SEARCH_CHOICE.name },
                    onRefresh = manager::refreshDiscovery,
                    onDeviceClick = manager::connect,
                )
            }
        }

        AppScreen.WAITING -> {
            val transport = activeTransport
            if (transport != null) {
                WaitingScreen(
                    transport = transport,
                    onBack = {
                        manager.cancelWaiting()
                        screenName = AppScreen.ACCEPT_CHOICE.name
                    },
                    onStop = {
                        manager.cancelWaiting()
                        screenName = AppScreen.ACCEPT_CHOICE.name
                    },
                )
            }
        }

        AppScreen.CHAT -> {
            val connected = session as? SessionState.Connected
            if (connected != null) {
                ChatScreen(
                    peerName = connected.peerName,
                    transport = connected.transport,
                    messages = messages,
                    onSend = manager::sendMessage,
                    onOpenSettings = { screenName = AppScreen.SETTINGS.name },
                    onDisconnect = manager::disconnect,
                )
            }
        }

        AppScreen.SETTINGS -> SettingsScreen(
            deviceName = settings.deviceName,
            darkTheme = settings.darkTheme,
            notificationSound = settings.notificationSound,
            onDeviceNameChange = settingsRepository::updateDeviceName,
            onDarkThemeChange = settingsRepository::setDarkTheme,
            onNotificationSoundChange = settingsRepository::setNotificationSound,
            onBack = {
                screenName = if (session is SessionState.Connected) {
                    AppScreen.CHAT.name
                } else {
                    AppScreen.HOME.name
                }
            },
        )
    }

    val connecting = session as? SessionState.Connecting
    if (connecting != null) {
        ConnectingDialog(
            peerName = connecting.peerName,
            onCancel = {
                manager.cancelConnecting()
                if (screen == AppScreen.SEARCH) {
                    activeTransport?.let(manager::startDiscovery)
                }
            },
        )
    }

    val incoming = session as? SessionState.Incoming
    if (incoming != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.incoming_connection)) },
            text = {
                Text(stringResource(R.string.incoming_connection_question, incoming.peerName))
            },
            confirmButton = {
                TextButton(onClick = manager::acceptIncoming) {
                    Text(stringResource(R.string.accept))
                }
            },
            dismissButton = {
                TextButton(onClick = manager::rejectIncoming) {
                    Text(stringResource(R.string.reject))
                }
            },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        )
    }

    alert?.let { current ->
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text(current.title) },
            text = { Text(current.message) },
            confirmButton = {
                TextButton(onClick = { alert = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }
}

@Composable
private fun ConnectingDialog(peerName: String, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = { },
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 26.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(30.dp), strokeWidth = 3.dp)
                    Column {
                        Text(
                            text = stringResource(R.string.connecting),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = peerName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "Ожидаем подтверждения на другом устройстве…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

private fun hasBluetoothPermissions(context: Context): Boolean {
    val permissions = requiredBluetoothPermissions()
    return permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requiredBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
    context.getSystemService(BluetoothManager::class.java)?.adapter
