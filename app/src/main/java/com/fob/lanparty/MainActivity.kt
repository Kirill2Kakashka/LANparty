package com.fob.lanparty

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.fob.lanparty.ui.LanPartyApp
import com.fob.lanparty.ui.theme.LanPartyTheme

class MainActivity : ComponentActivity() {

    private val app: LanPartyApplication
        get() = application as LanPartyApplication

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            val settings by app.settingsRepository.settings.collectAsState()
            LanPartyTheme(darkTheme = settings.darkTheme) {
                LanPartyApp(app)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        app.connectionManager.setAppVisible(true)
    }

    override fun onStop() {
        if (!isChangingConfigurations) {
            app.connectionManager.setAppVisible(false)
        }
        super.onStop()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CHAT, false) == true) {
            app.connectionManager.requestOpenChat()
            intent.removeExtra(EXTRA_OPEN_CHAT)
        }
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "com.fob.lanparty.extra.OPEN_CHAT"
    }
}
