package com.itantra.relay.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.itantra.relay.ui.Gender
import com.itantra.relay.ui.UserProfile
import com.itantra.relay.ui.components.ProfileAvatar
import com.itantra.relay.ui.components.WtHeader
import com.itantra.relay.ui.theme.Accent
import com.itantra.relay.ui.theme.AccentBlue
import com.itantra.relay.ui.theme.bodyBrush
import com.itantra.relay.ui.theme.AvatarTints
import com.itantra.relay.ui.theme.BrandMono
import com.itantra.relay.ui.theme.CardWhite
import com.itantra.relay.ui.theme.ChipGray
import com.itantra.relay.ui.theme.Hairline
import com.itantra.relay.ui.theme.Ink
import com.itantra.relay.ui.theme.InkFaint
import com.itantra.relay.ui.theme.InkSoft
import com.itantra.relay.ui.theme.PillBlack
import com.itantra.relay.ui.theme.StatusGreen
import java.io.File

@Composable
fun OnboardingScreen(onDone: (UserProfile) -> Unit) {
    val context = LocalContext.current

    var name by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf(Gender.OTHER) }
    var colorIndex by remember { mutableIntStateOf(0) }
    var photoPath by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            photoPath = runCatching {
                val file = File(context.filesDir, "avatar_${System.currentTimeMillis()}.jpg")
                context.contentResolver.openInputStream(uri)!!.use { input ->
                    file.outputStream().use { input.copyTo(it) }
                }
                file.absolutePath
            }.getOrNull()
        }
    }

    fun checkPerm(p: String) =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    val needsBt = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val needsNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    // Wi-Fi Direct discovery needs NEARBY_WIFI_DEVICES on 13+, location below it.
    val wifiPerm = if (needsNotif) Manifest.permission.NEARBY_WIFI_DEVICES
    else Manifest.permission.ACCESS_FINE_LOCATION

    var micOk by remember { mutableStateOf(checkPerm(Manifest.permission.RECORD_AUDIO)) }
    var wifiOk by remember { mutableStateOf(checkPerm(wifiPerm)) }
    var btOk by remember { mutableStateOf(!needsBt || checkPerm(Manifest.permission.BLUETOOTH_CONNECT)) }
    var notifOk by remember {
        mutableStateOf(!needsNotif || checkPerm(Manifest.permission.POST_NOTIFICATIONS))
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        micOk = checkPerm(Manifest.permission.RECORD_AUDIO)
        wifiOk = checkPerm(wifiPerm)
        btOk = !needsBt || checkPerm(Manifest.permission.BLUETOOTH_CONNECT)
        notifOk = !needsNotif || checkPerm(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun requestPerms() {
        val list = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(wifiPerm)
            if (needsBt) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (needsNotif) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permLauncher.launch(list.toTypedArray())
    }

    val tint = AvatarTints[colorIndex % AvatarTints.size]

    Column(
        Modifier
            .fillMaxSize()
            .background(bodyBrush())
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        WtHeader(recording = false)
        Spacer(Modifier.height(14.dp))
        Text("SETUP", color = AccentBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Text("Set up your walkie", fontFamily = BrandMono, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = Ink)
        Spacer(Modifier.height(6.dp))
        Text("Just a few things before you can talk. Everything stays on your phone.", color = InkSoft, fontSize = 14.sp)

        // Avatar
        Spacer(Modifier.height(28.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ProfileAvatar(name = name, tint = tint, photoPath = photoPath, size = 104.dp)
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .border(1.dp, Hairline, RoundedCornerShape(20.dp))
                    .clickable { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.PhotoCamera, null, tint = Ink, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (photoPath == null) "Choose photo" else "Change photo", color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            if (photoPath != null) {
                Spacer(Modifier.height(8.dp))
                Text("Remove photo", color = InkFaint, fontSize = 13.sp, modifier = Modifier.clickable { photoPath = null })
            }
        }

        // Colour (used when no photo)
        Spacer(Modifier.height(20.dp))
        Text("Avatar colour", color = InkSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AvatarTints.take(6).forEachIndexed { i, c ->
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(c)
                        .then(if (i == colorIndex) Modifier.border(2.5.dp, Ink, CircleShape) else Modifier)
                        .clickable { colorIndex = i },
                )
            }
        }

        // Name
        Spacer(Modifier.height(24.dp))
        Text("Your name", color = InkSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .height(50.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(ChipGray)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (name.isEmpty()) Text("e.g. Naitik", color = InkFaint, fontSize = 15.sp)
                BasicTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    textStyle = TextStyle(color = Ink, fontSize = 15.sp),
                    cursorBrush = SolidColor(AccentBlue), modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // Gender
        Spacer(Modifier.height(24.dp))
        Text("Gender", color = InkSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Gender.entries.forEach { g ->
                GenderChip(g.label, selected = gender == g, modifier = Modifier.weight(1f)) { gender = g }
            }
        }

        // Permissions
        Spacer(Modifier.height(24.dp))
        Text("Permissions", color = InkSoft, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        PermRow(Icons.Filled.Mic, "Microphone", "To speak into the walkie", micOk)
        PermRow(Icons.Filled.Wifi, "Wi-Fi Direct", "To find and reach nearby phones", wifiOk)
        PermRow(Icons.Filled.Bluetooth, "Bluetooth", "Backup discovery for nearby squads", btOk)
        if (needsNotif) PermRow(Icons.Filled.Notifications, "Notifications", "For messages and alerts", notifOk)

        Spacer(Modifier.height(12.dp))
        val allGranted = micOk && wifiOk && btOk && notifOk
        if (!allGranted) {
            OutlineButton("Grant permissions") { requestPerms() }
        }

        // Continue
        Spacer(Modifier.height(28.dp))
        val enabled = name.isNotBlank()
        Row(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (enabled) Accent else ChipGray)
                .clickable(enabled = enabled) {
                    onDone(UserProfile(name.trim(), gender, colorIndex, photoPath))
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Get started",
                color = if (enabled) CardWhite else InkFaint,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun GenderChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Ink else ChipGray)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (selected) CardWhite else Ink, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
private fun PermRow(icon: ImageVector, title: String, subtitle: String, granted: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(ChipGray),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = Ink, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = InkFaint, fontSize = 12.sp)
        }
        if (granted) {
            Box(
                Modifier.size(24.dp).clip(CircleShape).background(StatusGreen),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, "Granted", tint = CardWhite, modifier = Modifier.size(15.dp)) }
        } else {
            Text("Needed", color = InkFaint, fontSize = 12.sp)
        }
    }
}

@Composable
private fun OutlineButton(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}
