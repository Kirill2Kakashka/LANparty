package com.fob.lanparty.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fob.lanparty.LanPartyApplication
import com.fob.lanparty.model.Transport

class ConnectionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            val app = application as LanPartyApplication
            app.connectionManager.disconnect()
            releaseLocks()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val peerName = intent?.getStringExtra(EXTRA_PEER_NAME)
        val transport = intent?.getStringExtra(EXTRA_TRANSPORT)
            ?.let { runCatching { Transport.valueOf(it) }.getOrNull() }
        if (peerName.isNullOrBlank() || transport == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        acquireLocks(transport)
        val app = application as LanPartyApplication
        val notification = app.notificationHelper.connectionNotification(peerName, transport)
        startForeground(NotificationHelper.CONNECTION_NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        NotificationManagerCompat.from(this)
            .cancel(NotificationHelper.CONNECTION_NOTIFICATION_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks(transport: Transport) {
        if (wakeLock == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LANparty:connection")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (transport == Transport.WIFI && wifiLock == null) {
            val wifiManager = applicationContext
                .getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            wifiLock = wifiManager
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LANparty:wifi")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        wakeLock = null
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
        wifiLock = null
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        NotificationManagerCompat.from(this)
            .cancel(NotificationHelper.CONNECTION_NOTIFICATION_ID)
    }

    companion object {
        private const val ACTION_DISCONNECT = "com.fob.lanparty.action.DISCONNECT"
        private const val EXTRA_PEER_NAME = "peer_name"
        private const val EXTRA_TRANSPORT = "transport"

        fun start(context: Context, peerName: String, transport: Transport) {
            val intent = Intent(context, ConnectionService::class.java).apply {
                putExtra(EXTRA_PEER_NAME, peerName)
                putExtra(EXTRA_TRANSPORT, transport.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionService::class.java))
            NotificationManagerCompat.from(context)
                .cancel(NotificationHelper.CONNECTION_NOTIFICATION_ID)
        }

        fun disconnectPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, ConnectionService::class.java)
                .setAction(ACTION_DISCONNECT)
            return PendingIntent.getService(
                context,
                20,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
