package com.virtualap.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class APStatus(
    val running: Boolean = false,
    val gateway: String = APConfig.DEFAULT_GATEWAY,
    val dnsServers: String? = null,
    val clients: Int = 0,
    val ssid: String? = null,
    val band: String? = null,
    val channel: String? = null,
    val width: String? = null,
    val security: String? = null,
    val upstream: String? = null,
    val started: String? = null,
    val mode: String = "routed",
    val container: String? = null,
    val gatewayMac: String? = null
)

data class NetworkIface(val name: String, val ip: String?)

data class ApClient(val mac: String, val ip: String?, val name: String?)

object APManager {
    private const val PREFS_NAME = "virtualap_normal_prefs"
    private const val KEY_CONFIG_LINES = "ap_conf_lines"
    private const val KEY_RUNNING = "ap_running"

    private var appContext: Context? = null
    private var currentStatus = APStatus()

    fun init(context: Context) {
        appContext = context.applicationContext
        val prefs = prefs()
        val running = prefs?.getBoolean(KEY_RUNNING, false) ?: false
        currentStatus = currentStatus.copy(running = running)
    }

    private fun prefs() =
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun getStatus(): APStatus = withContext(Dispatchers.IO) {
        currentStatus
    }

    suspend fun start(cfg: APConfig, logger: BackendLogger): Boolean = withContext(Dispatchers.IO) {
        if (!cfg.isValid()) return@withContext false
        saveConfig(cfg)
        currentStatus = APStatus(
            running = true,
            gateway = cfg.gateway.ifBlank { APConfig.DEFAULT_GATEWAY },
            clients = 0,
            ssid = cfg.ssid,
            band = cfg.band,
            channel = cfg.channel.ifBlank { null },
            width = cfg.width.ifBlank { null },
            security = cfg.security,
            upstream = cfg.upstream.ifBlank { "auto" },
            mode = "routed"
        )
        prefs()?.edit()?.putBoolean(KEY_RUNNING, true)?.apply()
        logger.log(android.util.Log.INFO, "Hotspot state updated (non-root mode)")
        true
    }

    suspend fun loadConfig(): APConfig? = withContext(Dispatchers.IO) {
        val raw = prefs()?.getString(KEY_CONFIG_LINES, null) ?: return@withContext null
        runCatching { APConfig.fromConf(raw.lines()) }.getOrNull()
    }

    suspend fun saveConfig(cfg: APConfig): Boolean = withContext(Dispatchers.IO) {
        if (!cfg.isValid()) return@withContext false
        prefs()?.edit()?.putString(KEY_CONFIG_LINES, cfg.toConf())?.apply()
        true
    }

    suspend fun stop(logger: BackendLogger): Boolean = withContext(Dispatchers.IO) {
        currentStatus = currentStatus.copy(running = false, clients = 0)
        prefs()?.edit()?.putBoolean(KEY_RUNNING, false)?.apply()
        logger.log(android.util.Log.INFO, "Hotspot stopped (non-root mode)")
        true
    }

    suspend fun getContainers(): List<String>? = withContext(Dispatchers.IO) {
        emptyList()
    }

    fun parseContainers(json: String): List<String> =
        runCatching {
            JSONObject(json).optJSONArray("running")?.let { a ->
                (0 until a.length()).mapNotNull {
                    a.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank)
                }
            }.orEmpty()
        }.getOrDefault(emptyList())

    suspend fun getCaps(): Map<String, String> = withContext(Dispatchers.IO) {
        mapOf("ttl" to "0", "mac" to "")
    }

    suspend fun getClients(): List<ApClient>? = withContext(Dispatchers.IO) {
        emptyList()
    }

    suspend fun getBlocked(): List<ApClient>? = withContext(Dispatchers.IO) {
        emptyList()
    }

    suspend fun getAllowed(): List<ApClient>? = withContext(Dispatchers.IO) {
        emptyList()
    }

    suspend fun block(mac: String, name: String?): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun unblock(mac: String): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun allow(mac: String, name: String?): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun disallow(mac: String): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun setAllowOnly(on: Boolean): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun allowAndTurnOn(devices: List<ApClient>): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun getAllowOnly(): Boolean? = withContext(Dispatchers.IO) {
        false
    }

    suspend fun getInterfaces(): List<NetworkIface> = withContext(Dispatchers.IO) {
        val ctx = appContext ?: return@withContext emptyList()
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return@withContext emptyList()
        val active = cm.activeNetwork ?: return@withContext emptyList()
        val lp: LinkProperties = cm.getLinkProperties(active) ?: return@withContext emptyList()
        val ifaceName = lp.interfaceName ?: return@withContext emptyList()
        val ip = lp.linkAddresses.firstOrNull { it.address.hostAddress?.contains(':') == false }?.address?.hostAddress
        listOf(NetworkIface(ifaceName, ip))
    }

    suspend fun isInstalled(): Boolean = withContext(Dispatchers.IO) {
        true
    }
}
