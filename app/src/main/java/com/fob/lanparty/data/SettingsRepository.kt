package com.fob.lanparty.data

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import com.fob.lanparty.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class SettingsRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val fallbackName: String = Build.MODEL
        .takeIf { it.isNotBlank() && !it.equals("unknown", ignoreCase = true) }
        ?: "Моё устройство"

    private val _settings = MutableStateFlow(
        AppSettings(
            deviceName = prefs.getString(KEY_DEVICE_NAME, fallbackName).orEmpty(),
            darkTheme = prefs.getBoolean(KEY_DARK_THEME, false),
            notificationSound = prefs.getBoolean(KEY_NOTIFICATION_SOUND, true),
        ),
    )
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings
        get() = _settings.value

    fun resolvedDeviceName(): String = current.deviceName.trim().ifBlank { fallbackName }

    fun updateDeviceName(value: String) {
        val sanitized = value
            .filterNot { it.isISOControl() }
            .take(MAX_NAME_LENGTH)
        prefs.edit { putString(KEY_DEVICE_NAME, sanitized) }
        _settings.update { it.copy(deviceName = sanitized) }
    }

    fun setDarkTheme(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_DARK_THEME, enabled) }
        _settings.update { it.copy(darkTheme = enabled) }
    }

    fun setNotificationSound(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_NOTIFICATION_SOUND, enabled) }
        _settings.update { it.copy(notificationSound = enabled) }
    }

    private companion object {
        const val PREFS_NAME = "lanparty_settings"
        const val KEY_DEVICE_NAME = "device_name"
        const val KEY_DARK_THEME = "dark_theme"
        const val KEY_NOTIFICATION_SOUND = "notification_sound"
        const val MAX_NAME_LENGTH = 40
    }
}
