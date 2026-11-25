package com.virtualap.app.ui.viewmodel

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.virtualap.app.util.APConfig
import com.virtualap.app.util.APManager
import com.virtualap.app.util.ApClient
import com.virtualap.app.util.Hotspot
import com.virtualap.app.util.NetworkIface
import com.virtualap.app.util.VirtualAPInstaller
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class APViewModel(application: Application) : AndroidViewModel(application) {
    val status get() = Hotspot.status
    val phase get() = Hotspot.phase
    val actionLogs get() = Hotspot.actionLogs
    var config by mutableStateOf(APConfig())
    var interfaces by mutableStateOf<List<NetworkIface>>(emptyList())
        private set
    var containers by mutableStateOf<List<String>>(emptyList())
        private set
    var ttlFixSupported by mutableStateOf(false)
        private set
    var deviceMac by mutableStateOf("")
        private set
    var blocked by mutableStateOf<List<ApClient>?>(emptyList())
        private set
    var allowed by mutableStateOf<List<ApClient>?>(emptyList())
        private set
    var allowOnly by mutableStateOf<Boolean?>(false)
        private set
    var startAtBoot by mutableStateOf(false)
        private set
    var showActionLogs by mutableStateOf(false)
        private set
    var isReady by mutableStateOf(false)
        private set

    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        viewModelScope.launch {
            runCatching { refreshDeviceLists() }
        }
        viewModelScope.launch {
            startAtBoot = runCatching { VirtualAPInstaller.bootModeEnabled() }.getOrDefault(false)
        }
        viewModelScope.launch {
            try {
                val caps = async { runCatching { APManager.getCaps() }.getOrDefault(emptyMap()) }
                runCatching { APManager.loadConfig() }.getOrNull()?.let { config = it }
                launch {
                    snapshotFlow { config }.drop(1).collectLatest {
                        delay(500)
                        runCatching { APManager.saveConfig(it) }
                    }
                }
                refreshAllNow()
                val c = caps.await()
                ttlFixSupported = c["ttl"] == "1"
                deviceMac = c["mac"].orEmpty()
                if (!ttlFixSupported && config.ttlFix) config = config.copy(ttlFix = false)
            } finally {
                isReady = true
            }
        }
    }

    suspend fun refreshDeviceLists() {
        runCatching { APManager.getBlocked() }.getOrNull()?.let { blocked = it }
        runCatching { APManager.getAllowed() }.getOrNull()?.let { allowed = it }
        runCatching { APManager.getAllowOnly() }.getOrNull()?.let { allowOnly = it }
    }

    suspend fun updateStartAtBoot(enabled: Boolean): Boolean {
        val written = runCatching { VirtualAPInstaller.setBootMode(enabled) }.getOrDefault(false)
        if (written) startAtBoot = enabled
        return written
    }

    suspend fun refreshAllNow() = coroutineScope {
        val s = async { runCatching { Hotspot.refresh() } }
        val ifs = async { runCatching { APManager.getInterfaces() }.getOrDefault(emptyList()) }
        val cs = async { runCatching { APManager.getContainers() }.getOrNull() }
        s.await()
        interfaces = ifs.await()
        cs.await()?.let { containers = it }
    }

    fun selectBand(value: String) {
        config = config.copy(
            band = value,
            channel = "",
            width = APConfig.validWidthForBand(value, config.width)
        )
    }

    fun selectMacMode(mode: String) {
        if (mode == config.macMode) return
        config = config.copy(
            macMode = mode,
            gatewayMac = when (mode) {
                "persistent" -> APConfig.randomMac()
                "static" -> deviceMac
                else -> ""
            }
        )
    }

    fun start() {
        runCatching { Hotspot.start(config) }
    }

    fun stop() {
        runCatching { Hotspot.stop() }
    }

    fun clearLog() {
        Hotspot.actionLogs.clear()
    }

    fun openLogSheet() { showActionLogs = true }
    fun dismissActionLogs() { showActionLogs = false }
}
