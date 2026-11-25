package com.virtualap.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import com.virtualap.app.HotspotStopReceiver
import com.virtualap.app.HotspotTileService
import com.virtualap.app.MainActivity
import com.virtualap.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The one owner of a start/stop session. It outlives the ViewModel so a start
 * begun from a Quick Settings tile is visible to the screen and the other way
 * round. Everything that runs the engine's start/stop goes through here.
 */
object Hotspot {
    enum class Phase { IDLE, STARTING, STOPPING }

    var phase by mutableStateOf(Phase.IDLE)
        private set
    var status by mutableStateOf(APStatus())
        private set
    val actionLogs = mutableStateListOf<Pair<Int, String>>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var app: Context
    private val logger = BackendLogger { level, msg -> actionLogs.add(level to msg) }
    private var synced = false

    private const val CHANNEL = "hotspot"
    private const val NOTIFICATION_ID = 1

    fun init(context: Context) {
        app = context.applicationContext
        APManager.init(app)
        runCatching {
            app.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    app.getString(R.string.notification_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    /** The session's job, or null when nothing was started (busy, or the config cannot start). */
    fun start(cfg: APConfig): Job? {
        if (phase != Phase.IDLE || !cfg.isValid()) return null
        enterPhase(Phase.STARTING)
        actionLogs.clear()
        return scope.launch {
            val ok = runCatching { APManager.start(cfg, logger) }.getOrDefault(false)
            if (!ok) logFailure()
            settle()
        }
    }

    fun stop(): Job? {
        if (phase != Phase.IDLE) return null
        enterPhase(Phase.STOPPING)
        actionLogs.clear()
        return scope.launch {
            val ok = runCatching { APManager.stop(logger) }.getOrDefault(false)
            if (!ok) logFailure()
            settle()
        }
    }

    /** Re-read status; only a real change fans out to the tile and the
     *  notification, so the screen's 3 s poll cannot ping-pong with the tile. */
    suspend fun refresh(): APStatus {
        val s = runCatching { APManager.getStatus() }.getOrDefault(status)
        if (s != status || !synced) {
            synced = true
            status = s
            syncTile()
            syncNotification()
        }
        return s
    }

    private suspend fun settle() {
        synced = true
        status = runCatching { APManager.getStatus() }.getOrDefault(status)
        enterPhase(Phase.IDLE)
        syncNotification()
    }

    private fun enterPhase(p: Phase) {
        phase = p
        syncTile()
    }

    private fun syncTile() {
        if (!::app.isInitialized) return
        runCatching {
            TileService.requestListeningState(app, ComponentName(app, HotspotTileService::class.java))
        }
    }

    private fun syncNotification() {
        if (!::app.isInitialized) return
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (!status.running) {
                nm.cancel(NOTIFICATION_ID)
                return
            }
            val open = PendingIntent.getActivity(
                app, 0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE
            )
            val stop = PendingIntent.getBroadcast(
                app, 1, Intent(app, HotspotStopReceiver::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val clients = status.clients
            val notification = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_wifi_tethering)
                .setContentTitle(status.ssid ?: app.getString(R.string.app_name))
                .setContentText(app.resources.getQuantityString(R.plurals.notification_clients, clients, clients))
                .setContentIntent(open)
                .addAction(0, app.getString(R.string.stop_ap), stop)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun logFailure() {
        if (!::app.isInitialized) return
        logger.log(Log.ERROR, app.getString(R.string.command_failed))
    }
}
