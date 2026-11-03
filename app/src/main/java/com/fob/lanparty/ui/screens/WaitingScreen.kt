package com.fob.lanparty.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fob.lanparty.R
import com.fob.lanparty.model.Transport
import com.fob.lanparty.ui.components.LanTopBar

@Composable
fun WaitingScreen(
    transport: Transport,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    Scaffold(
        topBar = {
            LanTopBar(
                title = stringResource(
                    if (transport == Transport.WIFI) {
                        R.string.accept_wifi_title
                    } else {
                        R.string.accept_bluetooth_title
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
                .padding(horizontal = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(54.dp))
            WaitingIndicator(transport)
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.waiting_connection),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    if (transport == Transport.WIFI) {
                        R.string.waiting_wifi_hint
                    } else {
                        R.string.waiting_bluetooth_hint
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.waiting_keep_open),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
            OutlinedButton(
                onClick = onStop,
                modifier = Modifier.padding(bottom = 24.dp),
            ) {
                Text(stringResource(R.string.stop_waiting))
            }
        }
    }
}

@Composable
private fun WaitingIndicator(transport: Transport) {
    val transition = rememberInfiniteTransition(label = "waiting")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val ringAlpha by transition.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_300),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "ringAlpha",
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(168.dp)
                .scale(pulse)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = ringAlpha)),
        )
        Box(
            modifier = Modifier
                .size(110.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    if (transport == Transport.WIFI) {
                        R.drawable.ic_wifi
                    } else {
                        R.drawable.ic_bluetooth
                    },
                ),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .size(48.dp)
                    .alpha(0.9f),
            )
        }
    }
}
