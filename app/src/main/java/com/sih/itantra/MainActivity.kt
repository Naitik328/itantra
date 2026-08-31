package com.sih.itantra

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sih.itantra.audio.VoiceActionsFactory
import com.sih.itantra.audio.VoiceViewModel
import com.sih.itantra.ui.screen.ConnectScreen
import com.sih.itantra.ui.theme.ITantraTheme
import com.sih.itantra.wifidirect.WifiDirectBroadcastReceiver
import com.sih.itantra.wifidirect.WifiDirectManager
import com.sih.itantra.wifidirect.WifiDirectViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: WifiDirectViewModel by viewModels()
    private val voiceViewModel: VoiceViewModel by viewModels()

    private var receiver: WifiDirectBroadcastReceiver? = null

    /** Drives the voice card; mirrored into Compose state so the UI reacts to a grant. */
    private var micGranted by mutableStateOf(false)

    /** Shown before the system dialog when the platform says an explanation is warranted. */
    private var showMicRationale by mutableStateOf(false)

    /** Shown after a denial that the system will no longer prompt for. */
    private var showMicDenied by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results.values.all { it }
        if (granted) {
            viewModel.onPermissionsGranted()
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        micGranted = results[Manifest.permission.RECORD_AUDIO] == true || hasMicPermission()
        // A denial with no rationale offered means "don't ask again" — the only route left is
        // the system settings page, so say that rather than re-prompting into a void.
        if (!micGranted &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
        ) {
            showMicDenied = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        micGranted = hasMicPermission()

        setContent {
            ITantraTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()

                // Remembered so the bundle keeps its identity across recompositions; a fresh
                // instance every frame would invalidate the voice card on every state change.
                val voiceActions = remember {
                    VoiceActionsFactory.from(
                        viewModel = voiceViewModel,
                        onRequestPermission = ::requestMicPermission,
                    )
                }

                ConnectScreen(
                    state = state,
                    voiceState = voiceState,
                    voiceLevel = voiceViewModel.level,
                    micGranted = micGranted,
                    voiceActions = voiceActions,
                    onScan = ::onScanRequested,
                    onDisconnect = viewModel::onDisconnectClicked,
                    onPeerClick = viewModel::onPeerClicked,
                    onSendMessage = viewModel::onSendMessage,
                )

                if (showMicRationale) {
                    RationaleDialog(
                        onConfirm = {
                            showMicRationale = false
                            launchMicRequest()
                        },
                        onDismiss = { showMicRationale = false },
                    )
                }

                if (showMicDenied) {
                    DeniedDialog(
                        onOpenSettings = {
                            showMicDenied = false
                            openAppSettings()
                        },
                        onDismiss = { showMicDenied = false },
                    )
                }
            }
        }
    }

    // -- permissions ------------------------------------------------------------------------

    private fun requestMicPermission() {
        if (hasMicPermission()) {
            micGranted = true
            return
        }
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            showMicRationale = true
        } else {
            launchMicRequest()
        }
    }

    private fun launchMicRequest() {
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            // The foreground service that keeps the mic open off-screen needs a visible
            // notification, so ask for both in one dialog rather than two in a row.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        micPermissionLauncher.launch(permissions.toTypedArray())
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", packageName, null)),
        )
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

    // -- lifecycle --------------------------------------------------------------------------

    override fun onResume() {
        super.onResume()
        // The user may have granted the mic in Settings while we were away.
        micGranted = hasMicPermission()

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

@androidx.compose.runtime.Composable
private fun RationaleDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.mic_rationale_title)) },
        text = { Text(androidx.compose.ui.res.stringResource(R.string.mic_rationale_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(androidx.compose.ui.res.stringResource(R.string.mic_rationale_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.mic_rationale_dismiss))
            }
        },
    )
}

@androidx.compose.runtime.Composable
private fun DeniedDialog(onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(androidx.compose.ui.res.stringResource(R.string.mic_rationale_title)) },
        text = { Text(androidx.compose.ui.res.stringResource(R.string.mic_denied_body)) },
        confirmButton = {
            TextButton(onClick = onOpenSettings) {
                Text(androidx.compose.ui.res.stringResource(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(androidx.compose.ui.res.stringResource(R.string.mic_rationale_dismiss))
            }
        },
    )
}
