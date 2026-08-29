package com.itantra.relay.transport

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.itantra.relay.alert.AlertNotifier
import com.itantra.relay.protocol.FrameType
import com.itantra.relay.protocol.WireCodec
import com.itantra.relay.ui.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the Wi-Fi Direct relay alive while the app is
 * backgrounded or closed. It owns the incoming-frame collector, so an inbound
 * ALERT still pops a full-screen notification with no Activity present.
 */
class RelayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectorJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AlertNotifier.ensureChannels(this)
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground()
        val name = ProfileStore.load(this)?.name ?: "iTantra"
        RelayHub.ensureRegistered(applicationContext, name)
        startCollector(name)
        return START_STICKY
    }

    private fun startAsForeground() {
        val notif = AlertNotifier.ongoingNotification(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, AlertNotifier.ONGOING_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(AlertNotifier.ONGOING_ID, notif)
        }
    }

    private fun startCollector(name: String) {
        if (collectorJob?.isActive == true) return
        val transport = RelayHub.getOrCreate(applicationContext)
        collectorJob = scope.launch {
            transport.incoming.collect { bytes ->
                val res = WireCodec.decode(bytes)
                if (res is WireCodec.DecodeResult.Ok && res.frame.type == FrameType.ALERT) {
                    val text = res.frame.text
                    AlertNotifier.showAlert(applicationContext, text)
                    RelayHub.setAlert(RelayHub.ReceivedAlert(text, res.frame.src, System.currentTimeMillis()))
                    // Let the sender know we got it.
                    launch { transport.send(RelayHub.ackBytes(name)) }
                }
                // ACK / NORMAL frames need no service-side handling here.
            }
        }
    }

    override fun onDestroy() {
        collectorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
