package com.localhostlab.app.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localhostlab.app.data.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class SettingsViewModel(
    application: Application,
    private val repository: ProjectRepository
) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("localhostlab_settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        try {
            AppThemeMode.valueOf(prefs.getString("theme_mode", AppThemeMode.SYSTEM.name) ?: AppThemeMode.SYSTEM.name)
        } catch (e: Exception) {
            AppThemeMode.SYSTEM
        }
    )
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _defaultPort = MutableStateFlow(prefs.getInt("default_port", 3000))
    val defaultPort: StateFlow<Int> = _defaultPort.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    fun dismissStatusMessage() {
        _statusMessage.value = null
    }

    fun setThemeMode(mode: AppThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString("theme_mode", mode.name).apply()
    }

    fun setDefaultPort(port: Int) {
        _defaultPort.value = port
        prefs.edit().putInt("default_port", port).apply()
        _statusMessage.value = "Default port set to $port"
    }

    fun clearCache() {
        viewModelScope.launch {
            val freedBytes = repository.clearCache()
            val kb = freedBytes / 1024
            _statusMessage.value = "Cleared cache ($kb KB freed)"
        }
    }

    fun clearNodeModules() {
        viewModelScope.launch {
            val count = repository.clearNodeModules()
            _statusMessage.value = "Cleaned node_modules from $count idle project(s)"
        }
    }
}
