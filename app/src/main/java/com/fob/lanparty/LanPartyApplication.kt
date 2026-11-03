package com.fob.lanparty

import android.app.Application
import com.fob.lanparty.data.SettingsRepository
import com.fob.lanparty.net.ConnectionManager
import com.fob.lanparty.service.NotificationHelper

class LanPartyApplication : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set

    lateinit var notificationHelper: NotificationHelper
        private set

    internal lateinit var connectionManager: ConnectionManager
        private set

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        notificationHelper = NotificationHelper(this, settingsRepository)
        connectionManager = ConnectionManager(this, settingsRepository, notificationHelper)
    }
}
