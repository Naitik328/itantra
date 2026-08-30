package com.sih.p2pconnect.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.util.Log

/**
 * Listens for the four Wi-Fi P2P system broadcasts and forwards them into [WifiDirectManager].
 * Registered only while the hosting Activity is resumed (see MainActivity) so it never leaks
 * and discovery stays foreground-only.
 */
class WifiDirectBroadcastReceiver(
    private val manager: WifiDirectManager,
    private val p2pManager: WifiP2pManager?,
    private val channelProvider: () -> WifiP2pManager.Channel?,
) : BroadcastReceiver() {

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                manager.onP2pStateChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
            }

            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                val mgr = p2pManager
                val ch = channelProvider()
                if (mgr != null && ch != null) {
                    try {
                        mgr.requestPeers(ch) { peers ->
                            manager.onPeersAvailable(peers.deviceList)
                        }
                    } catch (se: SecurityException) {
                        Log.e(TAG, "requestPeers SecurityException", se)
                    }
                }
            }

            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                val state = intent.getIntExtra(
                    WifiP2pManager.EXTRA_DISCOVERY_STATE,
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED,
                )
                manager.onDiscoveryChanged(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED)
            }

            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_NETWORK_INFO,
                        android.net.NetworkInfo::class.java,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                }
                manager.onConnectionChanged(networkInfo?.isConnected == true)
            }

            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(
                        WifiP2pManager.EXTRA_WIFI_P2P_DEVICE,
                        WifiP2pDevice::class.java,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                }
                device?.let { manager.onThisDeviceChanged(it) }
            }
        }
    }

    companion object {
        private const val TAG = "WifiDirectReceiver"

        val intentFilter: IntentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
    }
}
