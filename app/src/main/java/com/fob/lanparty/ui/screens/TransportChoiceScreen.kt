package com.fob.lanparty.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fob.lanparty.R
import com.fob.lanparty.model.Transport
import com.fob.lanparty.ui.components.LanTopBar
import com.fob.lanparty.ui.components.ScreenTitle
import com.fob.lanparty.ui.components.TransportOption

enum class TransportPurpose {
    SEARCH,
    ACCEPT,
}

@Composable
fun TransportChoiceScreen(
    purpose: TransportPurpose,
    onBack: () -> Unit,
    onPick: (Transport) -> Unit,
) {
    Scaffold(
        topBar = {
            LanTopBar(
                title = stringResource(
                    if (purpose == TransportPurpose.SEARCH) {
                        R.string.search_devices
                    } else {
                        R.string.accept_connection
                    },
                ),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            ScreenTitle(
                eyebrow = stringResource(R.string.app_name),
                title = stringResource(R.string.choose_connection),
            )
            Spacer(Modifier.height(10.dp))
            TransportOption(
                title = stringResource(R.string.wifi),
                subtitle = stringResource(
                    if (purpose == TransportPurpose.SEARCH) {
                        R.string.search_over_wifi_hint
                    } else {
                        R.string.accept_over_wifi_hint
                    },
                ),
                icon = painterResource(R.drawable.ic_wifi),
                onClick = { onPick(Transport.WIFI) },
            )
            TransportOption(
                title = stringResource(R.string.bluetooth),
                subtitle = stringResource(
                    if (purpose == TransportPurpose.SEARCH) {
                        R.string.search_over_bluetooth_hint
                    } else {
                        R.string.accept_over_bluetooth_hint
                    },
                ),
                icon = painterResource(R.drawable.ic_bluetooth),
                onClick = { onPick(Transport.BLUETOOTH) },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = when (purpose) {
                    TransportPurpose.SEARCH ->
                        "Устройства должны быть подключены к одной Wi-Fi сети или находиться рядом по Bluetooth."
                    TransportPurpose.ACCEPT ->
                        "После выбора телефон начнёт ждать подключения. Не закрывайте приложение."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
