package com.sih.itantra.ui.screen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sih.itantra.audio.AudioLevel
import com.sih.itantra.audio.AudioRoute
import com.sih.itantra.audio.CaptureHalt
import com.sih.itantra.audio.CaptureMode
import com.sih.itantra.audio.VoiceSessionState
import kotlinx.coroutines.flow.StateFlow

/** Everything the voice card needs to drive the session, bundled to keep the call site short. */
data class VoiceActions(
    val onTalkPressed: () -> Unit,
    val onTalkReleased: () -> Unit,
    val onHandsFreeToggled: () -> Unit,
    val onModeSelected: (CaptureMode) -> Unit,
    val onRouteSelected: (AudioRoute) -> Unit,
    val onArchiveToggled: (Boolean) -> Unit,
    val onEchoToggled: (Boolean) -> Unit,
    val onClearArchive: () -> Unit,
    val onTestAlert: () -> Unit,
    val onStopPlayback: () -> Unit,
    val onRequestPermission: () -> Unit,
)

@Composable
fun VoiceCard(
    state: VoiceSessionState,
    level: StateFlow<Float>,
    micGranted: Boolean,
    actions: VoiceActions,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${state.routeLabel} · gate: ${state.gateLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (!micGranted) {
                MicPermissionPrompt(actions.onRequestPermission)
                return@Column
            }

            ModeChips(state.mode, actions.onModeSelected)
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                TalkButton(state, actions)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = statusLine(state),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LevelMeter(level = level, active = state.capturing)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = detailLine(state),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            RouteChips(state.route, actions.onRouteSelected)

            Spacer(Modifier.height(8.dp))
            ToggleRow(
                label = "Echo captured audio",
                help = "Plays each utterance back — proves the path end to end before STT lands",
                checked = state.echoEnabled,
                onCheckedChange = actions.onEchoToggled,
            )
            ToggleRow(
                label = "Save clips for benchmarking",
                help = if (state.archivedClips > 0) {
                    "${state.archivedClips} clips · ${state.archivedBytes / 1024} KB in app files/captures"
                } else {
                    "Writes 16 kHz WAVs the AI team can adb pull"
                },
                checked = state.archiveEnabled,
                onCheckedChange = actions.onArchiveToggled,
            )

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = actions.onTestAlert, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Test alert")
                }
                if (state.playing) {
                    OutlinedButton(onClick = actions.onStopPlayback, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Stop")
                    }
                } else if (state.archivedClips > 0) {
                    TextButton(onClick = actions.onClearArchive, modifier = Modifier.weight(1f)) {
                        Text("Clear clips")
                    }
                }
            }

            state.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The meter is its own composable so that the 31-per-second level updates invalidate only this
 * subtree, instead of recomposing the card — and through it the screen — on every audio frame.
 */
@Composable
private fun LevelMeter(level: StateFlow<Float>, active: Boolean) {
    val dbfs by level.collectAsStateWithLifecycle()
    val target = if (active) AudioLevel.normalized(dbfs) else 0f
    val animated by animateFloatAsState(targetValue = target, label = "level")

    LinearProgressIndicator(
        progress = { animated },
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .background(Color.Transparent, RoundedCornerShape(3.dp)),
    )
}

@Composable
private fun TalkButton(state: VoiceSessionState, actions: VoiceActions) {
    val capturing = state.capturing
    val background = when {
        state.speaking -> MaterialTheme.colorScheme.error
        capturing -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.primaryContainer
    }
    val tint = when {
        state.speaking || capturing -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    val gestures = if (state.isPushToTalk) {
        Modifier.pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    actions.onTalkPressed()
                    // Suspends until the finger lifts or the gesture is cancelled, so release
                    // is guaranteed to fire and the microphone can never be left open.
                    tryAwaitRelease()
                    actions.onTalkReleased()
                },
            )
        }
    } else {
        Modifier.pointerInput(Unit) {
            detectTapGestures(onTap = { actions.onHandsFreeToggled() })
        }
    }

    Box(
        modifier = Modifier
            .size(72.dp)
            .background(background, CircleShape)
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (capturing) Icons.Filled.Mic else Icons.Filled.MicOff,
            contentDescription = if (state.isPushToTalk) "Hold to talk" else "Toggle listening",
            tint = tint,
            modifier = Modifier.size(30.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeChips(mode: CaptureMode, onSelected: (CaptureMode) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = mode == CaptureMode.PUSH_TO_TALK,
            onClick = { onSelected(CaptureMode.PUSH_TO_TALK) },
            label = { Text("Push to talk") },
        )
        FilterChip(
            selected = mode == CaptureMode.HANDS_FREE,
            onClick = { onSelected(CaptureMode.HANDS_FREE) },
            label = { Text("Hands-free") },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteChips(route: AudioRoute, onSelected: (AudioRoute) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AudioRoute.entries.forEach { option ->
            FilterChip(
                selected = route == option,
                onClick = { onSelected(option) },
                label = {
                    Text(
                        when (option) {
                            AudioRoute.AUTO -> "Auto"
                            AudioRoute.SPEAKER -> "Speaker"
                            AudioRoute.EARPIECE -> "Earpiece"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                help,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MicPermissionPrompt(onRequest: () -> Unit) {
    Column {
        Text(
            "The microphone is how this app works — speech is turned into text on this phone " +
                "and never leaves it as audio.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRequest) {
            Icon(Icons.Filled.Mic, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("Grant microphone access")
        }
    }
}

private fun statusLine(state: VoiceSessionState): String = when {
    state.halt == CaptureHalt.INTERRUPTED -> "Paused — another app took the mic"
    state.halt == CaptureHalt.STOPPED -> "Stopped by the system"
    state.playing -> "Playing back…"
    state.speaking -> "Speech detected"
    state.capturing && state.isPushToTalk -> "Listening — release to send"
    state.capturing -> "Listening for speech"
    state.isPushToTalk -> "Hold the mic to talk"
    else -> "Tap the mic to start listening"
}

private fun detailLine(state: VoiceSessionState): String {
    if (state.utteranceCount == 0) return "No utterances captured yet"
    val reason = state.lastEndReason?.let { " · $it" } ?: ""
    return "${state.utteranceCount} captured · last ${state.lastUtteranceMs} ms$reason"
}
