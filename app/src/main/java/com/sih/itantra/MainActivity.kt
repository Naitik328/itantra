package com.sih.itantra

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sih.itantra.ui.screen.ConnectScreen
import com.sih.itantra.ui.theme.ITantraTheme
import com.sih.itantra.wifidirect.WifiDirectBroadcastReceiver
import com.sih.itantra.wifidirect.WifiDirectManager
import com.sih.itantra.wifidirect.WifiDirectViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: WifiDirectViewModel by viewModels()

    private var receiver: WifiDirectBroadcastReceiver? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            viewModel.onPermissionsGranted()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ITantraTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                ConnectScreen(
                    state = state,
                    onScan = ::onScanRequested,
                    onDisconnect = viewModel::onDisconnectClicked,
                    onPeerClick = viewModel::onPeerClicked,
                    onSendMessage = viewModel::onSendMessage,
                )
            }
        }
    }

    private fun onScanRequested() {
        if (hasRequiredPermissions()) {
            viewModel.onScanClicked()
        } else {
            permissionLauncher.launch(WifiDirectManager.requiredPermissions())
        }
    }

    private fun hasRequiredPermissions(): Boolean =
        WifiDirectManager.requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    override fun onResume() {
        super.onResume()
        // Register the P2P broadcast receiver only while in the foreground.
        val r = viewModel.createReceiver()
        ContextCompat.registerReceiver(
            this,
            r,
            WifiDirectBroadcastReceiver.intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiver = r

        // Begin continuous discovery automatically so peers appear without tapping Scan.
        // If permissions aren't granted yet, the Scan button still handles the request.
        if (hasRequiredPermissions()) {
            viewModel.onForeground()
        }
    }

    override fun onPause() {
        super.onPause()
        viewModel.onBackground()
        receiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        receiver = null
    }
}
