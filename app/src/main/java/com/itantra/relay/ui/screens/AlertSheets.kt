package com.itantra.relay.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.relay.transport.RelayHub
import com.itantra.relay.ui.theme.CardWhite
import com.itantra.relay.ui.theme.Ink
import com.itantra.relay.ui.theme.InkSoft
import com.itantra.relay.ui.theme.PillBlack
import com.itantra.relay.ui.theme.RecordingRed

/**
 * "Sending alert…" sheet shown to the sender. While [sending] it shows a spinner
 * and the live count of phones reached; when the broadcast window ends it swaps to
 * a Done button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendingAlertSheet(
    count: Int,
    sending: Boolean,
    onDone: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = CardWhite) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (sending) "Sending alert…" else "Alert sent",
                color = Ink, fontWeight = FontWeight.Bold, fontSize = 20.sp,
            )
            Spacer(Modifier.size(6.dp))
            Text(
                RelayHub.SOS_TEXT,
                color = InkSoft, fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(20.dp))

            if (sending) CircularProgressIndicator(color = RecordingRed)
            Spacer(Modifier.size(16.dp))

            Text("$count", color = RecordingRed, fontWeight = FontWeight.Bold, fontSize = 44.sp)
            Text(
                if (count == 1) "person reached nearby" else "people reached nearby",
                color = InkSoft, fontSize = 14.sp,
            )
            Spacer(Modifier.size(20.dp))

            if (!sending) {
                Row(
                    Modifier.fillMaxWidth().height(50.dp)
                        .clip(RoundedCornerShape(14.dp)).background(PillBlack)
                        .clickable(onClick = onDone),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Done", color = CardWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            } else {
                Text("Keep this open until nearby phones join.", color = InkSoft, fontSize = 12.sp)
            }
        }
    }
}

/**
 * Full-screen red takeover shown to a receiver when an SOS arrives while the app
 * is in the foreground. (Backgrounded receivers get it via the full-screen
 * notification instead.)
 */
@Composable
fun IncomingAlertOverlay(alert: RelayHub.ReceivedAlert, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(RecordingRed).clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("🚨", fontSize = 72.sp)
            Spacer(Modifier.size(16.dp))
            Text("SOS ALERT", color = CardWhite, fontWeight = FontWeight.Bold, fontSize = 30.sp)
            Spacer(Modifier.size(12.dp))
            Text(
                alert.text,
                color = CardWhite, fontSize = 18.sp, textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.size(40.dp))
            Row(
                Modifier.clip(RoundedCornerShape(14.dp)).background(CardWhite)
                    .clickable(onClick = onDismiss).padding(horizontal = 32.dp, vertical = 14.dp),
            ) {
                Text("Dismiss", color = RecordingRed, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }
}
