package com.virtualap.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.virtualap.app.util.PreferencesManager
import com.virtualap.app.util.RootStatus
import kotlinx.coroutines.launch

enum class InstallStatus { Checking, NotInstalled, Installed }

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = PreferencesManager

    var rootStatus by mutableStateOf(RootStatus.Granted)
        private set

    var welcomeDone by mutableStateOf(prefs.hasSeenWelcome)
        private set

    var installStatus by mutableStateOf(InstallStatus.Installed)
        private set

    init {
        rootStatus = RootStatus.Granted
        installStatus = InstallStatus.Installed
    }

    fun finishWelcome() {
        prefs.hasSeenWelcome = true
        welcomeDone = true
        rootStatus = RootStatus.Granted
        installStatus = InstallStatus.Installed
    }

    fun checkRoot(loud: Boolean = false) {
        viewModelScope.launch {
            rootStatus = RootStatus.Granted
            installStatus = InstallStatus.Installed
        }
    }

    fun markInstalled() {
        installStatus = InstallStatus.Installed
    }
}
