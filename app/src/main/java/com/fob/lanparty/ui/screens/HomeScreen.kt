package com.fob.lanparty.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fob.lanparty.R
import com.fob.lanparty.ui.components.ActionCard
import com.fob.lanparty.ui.components.BrandMark
import com.fob.lanparty.ui.components.LanTopBar

@Composable
fun HomeScreen(
    onSearchDevices: () -> Unit,
    onAcceptConnection: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            LanTopBar(
                title = stringResource(R.string.app_name),
                showSettings = true,
                onSettings = onOpenSettings,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            BrandMark()
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(36.dp))
            ActionCard(
                title = stringResource(R.string.search_devices),
                subtitle = stringResource(R.string.search_devices_hint),
                icon = painterResource(R.drawable.ic_search),
                onClick = onSearchDevices,
                filled = true,
            )
            Spacer(Modifier.height(14.dp))
            ActionCard(
                title = stringResource(R.string.accept_connection),
                subtitle = stringResource(R.string.accept_connection_hint),
                icon = painterResource(R.drawable.ic_wifi),
                onClick = onAcceptConnection,
            )
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Wi-Fi · Bluetooth",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
