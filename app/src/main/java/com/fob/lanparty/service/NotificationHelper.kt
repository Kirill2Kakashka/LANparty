package com.fob.lanparty.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.fob.lanparty.MainActivity
import com.fob.lanparty.R
import com.fob.lanparty.data.SettingsRepository
import com.fob.lanparty.model.Transport

class NotificationHelper(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    init {
        createChannels()
    }

    fun connectionNotification(peerName: String, transport: Transport): Notification {
        return NotificationCompat.Builder(context, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_connected, peerName))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_connected, peerName)),
            )
            .setContentIntent(openAppPendingIntent(openChat = true))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                R.drawable.ic_notification_disconnect,
                context.getString(R.string.notification_disconnect),
                ConnectionService.disconnectPendingIntent(context),
            )
            .setSubText(
                context.getString(
                    if (transport == Transport.WIFI) {
                        R.string.chat_connected_wifi
                    } else {
                        R.string.chat_connected_bluetooth
                    },
                ),
            )
            .build()
    }

    fun showMessage(peerName: String, text: String) {
        val soundEnabled = settingsRepository.current.notificationSound
        val channel = if (soundEnabled) CHANNEL_MESSAGES_SOUND else CHANNEL_MESSAGES_SILENT
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(peerName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppPendingIntent(openChat = true))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        safeNotify(MESSAGE_NOTIFICATION_ID, notification)
    }

    fun showPeerDisconnected(soundEnabled: Boolean) {
        val channel = if (soundEnabled) CHANNEL_MESSAGES_SOUND else CHANNEL_MESSAGES_SILENT
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.notification_peer_disconnected))
            .setContentIntent(openAppPendingIntent(openChat = false))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        safeNotify(DISCONNECT_NOTIFICATION_ID, notification)
    }

    fun clearMessageNotification() {
        NotificationManagerCompat.from(context).cancel(MESSAGE_NOTIFICATION_ID)
    }

    fun clearDisconnectNotification() {
        NotificationManagerCompat.from(context).cancel(DISCONNECT_NOTIFICATION_ID)
    }

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val connection = NotificationChannel(
            CHANNEL_CONNECTION,
            context.getString(R.string.notification_channel_connection),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_connection_description)
            setShowBadge(false)
            setSound(null, null)
        }

        val soundChannel = NotificationChannel(
            CHANNEL_MESSAGES_SOUND,
            context.getString(R.string.notification_channel_messages_sound),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            enableVibration(true)
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(
                soundUri,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }

        val silentChannel = NotificationChannel(
            CHANNEL_MESSAGES_SILENT,
            context.getString(R.string.notification_channel_messages_silent),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            setSound(null, null)
            enableVibration(false)
        }

        manager.createNotificationChannels(listOf(connection, soundChannel, silentChannel))
    }

    private fun openAppPendingIntent(openChat: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_OPEN_CHAT, openChat)
        }
        return PendingIntent.getActivity(
            context,
            if (openChat) REQUEST_OPEN_CHAT else REQUEST_OPEN_HOME,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun safeNotify(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_MESSAGES_SOUND = "messages_sound"
        const val CHANNEL_MESSAGES_SILENT = "messages_silent"
        const val CONNECTION_NOTIFICATION_ID = 1
        const val MESSAGE_NOTIFICATION_ID = 2
        const val DISCONNECT_NOTIFICATION_ID = 3
        private const val REQUEST_OPEN_CHAT = 10
        private const val REQUEST_OPEN_HOME = 11
    }
}
