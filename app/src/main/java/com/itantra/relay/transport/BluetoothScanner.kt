package com.itantra.relay.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat

/**
 * Classic Bluetooth discovery that reports **only phones running iTantra**.
 *
 * It runs an inquiry to find nearby devices, then does an SDP lookup on each and
 * keeps only those advertising our RFCOMM service UUID — so headsets, laptops and
 * other phones without the app are filtered out.
 *
 * Caller must hold BLUETOOTH_SCAN + BLUETOOTH_CONNECT (API 31+) / location (≤30).
 */
class BluetoothScanner(
    private val context: Context,
    private val adapter: BluetoothAdapter,
) {
    interface Listener {
        /** Called for each confirmed iTantra device. */
        fun onDevice(device: BluetoothDevice)
        /** Called when the inquiry sweep ends (SDP checks may still be resolving). */
        fun onFinished()
    }

    private var listener: Listener? = null
    private var registered = false
    private val ourUuid = ParcelUuid(BluetoothRfcommTransport.SPP_UUID)
    private val candidates = LinkedHashMap<String, BluetoothDevice>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    deviceOf(intent)?.let { candidates[it.address] = it }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    listener?.onFinished()
                    // Now ask each candidate for its service records.
                    candidates.values.forEach { runCatching { fetchUuids(it) } }
                }
                BluetoothDevice.ACTION_UUID -> {
                    val device = deviceOf(intent) ?: return
                    @Suppress("DEPRECATION")
                    val uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID)
                    val isItantra = uuids?.any { it is ParcelUuid && it == ourUuid } == true
                    if (isItantra) listener?.onDevice(device)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchUuids(device: BluetoothDevice) {
        device.fetchUuidsWithSdp()
    }

    private fun deviceOf(intent: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    @SuppressLint("MissingPermission")
    fun start(l: Listener) {
        listener = l
        candidates.clear()
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothDevice.ACTION_UUID)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            // These are system broadcasts → must be EXPORTED on Android 14+.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
        runCatching { if (adapter.isDiscovering) adapter.cancelDiscovery() }
        runCatching { adapter.startDiscovery() }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        runCatching { adapter.cancelDiscovery() }
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        listener = null
    }
}
