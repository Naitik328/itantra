package com.itantra.relay.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.relay.ui.theme.Accent
import com.itantra.relay.ui.theme.AccentBright
import com.itantra.relay.ui.theme.AccentGlow
import com.itantra.relay.ui.theme.BodyBottom
import com.itantra.relay.ui.theme.BodyTop
import com.itantra.relay.ui.theme.BrandMono
import com.itantra.relay.ui.theme.ChannelBg
import com.itantra.relay.ui.theme.ChannelBgActive
import com.itantra.relay.ui.theme.ChannelDim
import com.itantra.relay.ui.theme.ChannelText
import com.itantra.relay.ui.theme.DisplayDim
import com.itantra.relay.ui.theme.DisplayText
import com.itantra.relay.ui.theme.Ink
import com.itantra.relay.ui.theme.InkSoft
import com.itantra.relay.ui.theme.KnobEdge
import com.itantra.relay.ui.theme.KnobHi
import com.itantra.relay.ui.theme.KnobLo
import com.itantra.relay.ui.theme.KnobMid
import com.itantra.relay.ui.theme.LedGreen
import com.itantra.relay.ui.theme.LedGreenGlow
import com.itantra.relay.ui.theme.LedRed
import com.itantra.relay.ui.theme.LedRedGlow
import com.itantra.relay.ui.theme.PanelHi
import com.itantra.relay.ui.theme.PanelLo
import com.itantra.relay.ui.theme.WaveGreen
import com.itantra.relay.ui.theme.displayBrush
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/* ============================ Header ============================ */

/** The device chrome: "WT-01" wordmark, a status LED, and a speaker grille. */
@Composable
fun WtHeader(recording: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "WT-01",
            fontFamily = BrandMono,
            fontWeight = FontWeight.Bold,
            fontSize = 26.sp,
            letterSpacing = 2.sp,
            color = Ink,
        )
        Spacer(Modifier.weight(1f))
        LedDot(recording)
        Spacer(Modifier.size(18.dp))
        SpeakerGrille()
    }
}

/** A recessed status LED — green when idle, molten red while transmitting. */
@Composable
fun LedDot(recording: Boolean, size: Dp = 30.dp) {
    val color = if (recording) LedRed else LedGreen
    val glow = if (recording) LedRedGlow else LedGreenGlow
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Brush.radialGradient(listOf(BodyTop, PanelLo)))
            .border(1.dp, PanelLo.copy(alpha = 0.6f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size * 0.42f)) {
            drawCircle(color = glow, radius = this.size.minDimension * 0.9f)
            drawCircle(color = color, radius = this.size.minDimension * 0.5f)
            drawCircle(
                color = Color.White.copy(alpha = 0.5f),
                radius = this.size.minDimension * 0.16f,
                center = Offset(this.size.width * 0.38f, this.size.height * 0.36f),
            )
        }
    }
}

/** A punched dot-matrix speaker grille. */
@Composable
fun SpeakerGrille(cols: Int = 14, rows: Int = 6) {
    Canvas(Modifier.size((cols * 5).dp, (rows * 5).dp)) {
        val gapX = this.size.width / cols
        val gapY = this.size.height / rows
        val r = this.size.height / rows * 0.22f
        for (c in 0 until cols) {
            for (rIdx in 0 until rows) {
                drawCircle(
                    color = PanelLo.copy(alpha = 0.85f),
                    radius = r,
                    center = Offset(gapX * (c + 0.5f), gapY * (rIdx + 0.5f)),
                )
            }
        }
    }
}

/* ============================ Display panel ============================ */

/** A dark inset LCD-style panel with an inner bevel. */
@Composable
fun DisplayPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(displayBrush())
            .border(1.5.dp, Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
            .padding(1.5.dp)
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, PanelHi.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) { content() }
}

/**
 * A channel display — name, a status subtitle, big mono channel number, and a
 * scrolling green idle waveform. [online] lights the status dot.
 */
@Composable
fun ChannelDisplay(
    name: String,
    subtitle: String,
    number: String,
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    DisplayPanel(modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = DisplayText, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Spacer(Modifier.size(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(if (online) LedGreen else DisplayDim),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(subtitle, color = DisplayDim, fontSize = 13.sp)
                    }
                }
                Text(
                    number,
                    fontFamily = BrandMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    color = DisplayText,
                )
            }
            Spacer(Modifier.size(14.dp))
            Waveform(active = false, color = WaveGreen, modifier = Modifier.fillMaxWidth().height(26.dp))
        }
    }
}

/** A "someone is speaking" display — name, live timer, real-time voice waveform. */
@Composable
fun SpeakingDisplay(
    speaker: String,
    timer: String,
    modifier: Modifier = Modifier,
    levels: List<Float>? = null,
) {
    DisplayPanel(modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(speaker, color = DisplayText, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                    Spacer(Modifier.size(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Accent))
                        Spacer(Modifier.size(6.dp))
                        Text("Speaking…", color = Accent, fontSize = 13.sp)
                    }
                }
                Text(
                    timer,
                    fontFamily = BrandMono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    color = DisplayText,
                )
            }
            Spacer(Modifier.size(14.dp))
            Waveform(
                active = true,
                color = AccentBright,
                modifier = Modifier.fillMaxWidth().height(30.dp),
                levels = levels,
            )
        }
    }
}

/**
 * A bar-style audio waveform.
 *
 * When [levels] are supplied (real mic amplitudes, 0..1, newest last) they are
 * drawn directly — the bars scroll left as fresh samples arrive, so the shape is
 * your actual voice. With no levels it falls back to a faint idle line, or a
 * synthetic ripple when [active].
 */
@Composable
fun Waveform(
    active: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    levels: List<Float>? = null,
) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Restart),
        label = "phase",
    )
    Canvas(modifier) {
        val bars = 46
        val gap = this.size.width / bars
        val midY = this.size.height / 2f
        val barW = gap * 0.42f
        val live = active && levels != null && levels.isNotEmpty()
        for (i in 0 until bars) {
            val t = i.toFloat() / bars
            val env: Float = when {
                live -> {
                    // Right-align the newest sample so the trace scrolls leftward.
                    val idx = levels!!.size - bars + i
                    val v = if (idx >= 0) levels[idx] else 0f
                    0.06f + v.coerceIn(0f, 1f) * 0.94f
                }
                active -> {
                    val a = sin((phase + t * 10f).toDouble()) * 0.5 + 0.5
                    val b = sin((phase * 1.7f + t * 22f).toDouble()) * 0.5 + 0.5
                    (0.18 + a * b * 0.82).toFloat()
                }
                else -> (0.10 + (sin((t * 14f).toDouble()) * 0.5 + 0.5) * 0.16).toFloat()
            }
            val h = midY * env
            val x = gap * (i + 0.5f)
            drawLine(
                color = color.copy(alpha = if (active) 0.95f else 0.7f),
                start = Offset(x, midY - h),
                end = Offset(x, midY + h),
                strokeWidth = barW,
                cap = StrokeCap.Round,
            )
        }
    }
}

/* ============================ SOS dome ============================ */

/**
 * The emergency SOS button — a glossy red dome seated in a metallic tick
 * collar, echoing the device's rotary hardware. Tap to broadcast an alert.
 */
@Composable
fun SosButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 178.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val outer = this.size.minDimension / 2f
            val tickR = outer * 0.97f
            val collarR = outer * 0.78f
            val domeR = outer * 0.60f

            // Tick collar — a cluster of orange lights at the top, steel elsewhere.
            val ticks = 40
            val sweepStart = -210.0
            val sweepEnd = 30.0
            for (i in 0 until ticks) {
                val frac = i.toFloat() / (ticks - 1)
                val ang = Math.toRadians(sweepStart + (sweepEnd - sweepStart) * frac)
                val lit = frac in 0.40f..0.60f
                val major = i % 4 == 0
                val inner = tickR * (if (major) 0.80f else 0.88f)
                val col = if (lit) AccentBright else KnobEdge.copy(alpha = 0.7f)
                drawLine(
                    color = col,
                    start = Offset(c.x + inner * cos(ang).toFloat(), c.y + inner * sin(ang).toFloat()),
                    end = Offset(c.x + tickR * cos(ang).toFloat(), c.y + tickR * sin(ang).toFloat()),
                    strokeWidth = if (major) 3.5f else 2f,
                    cap = StrokeCap.Round,
                )
            }

            // Metallic collar.
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(0.0f to KnobHi, 0.6f to KnobMid, 1.0f to KnobLo),
                    center = Offset(c.x - collarR * 0.3f, c.y - collarR * 0.34f),
                    radius = collarR * 1.5f,
                ),
                radius = collarR,
                center = c,
            )
            drawCircle(
                color = KnobEdge.copy(alpha = 0.6f), radius = collarR, center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
            )

            // Soft red glow around the dome.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(LedRedGlow, Color.Transparent),
                    center = c,
                    radius = domeR * 1.55f,
                ),
                radius = domeR * 1.55f,
                center = c,
            )

            // Drop shadow beneath the dome.
            drawCircle(
                color = Color.Black.copy(alpha = 0.30f),
                radius = domeR * 1.05f,
                center = Offset(c.x, c.y + domeR * 0.12f),
            )

            // Glossy red dome.
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFFF06452),
                        0.5f to Color(0xFFC63A28),
                        1.0f to Color(0xFF7C1B12),
                    ),
                    center = Offset(c.x - domeR * 0.34f, c.y - domeR * 0.40f),
                    radius = domeR * 1.5f,
                ),
                radius = domeR,
                center = c,
            )
            drawCircle(
                color = Color(0xFF5E140D), radius = domeR, center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
            )

            // Specular highlight.
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.45f), Color.Transparent),
                    center = Offset(c.x - domeR * 0.32f, c.y - domeR * 0.42f),
                    radius = domeR * 0.72f,
                ),
                radius = domeR * 0.72f,
                center = Offset(c.x - domeR * 0.26f, c.y - domeR * 0.34f),
            )
        }
        Text(
            "SOS",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 30.sp,
            letterSpacing = 3.sp,
        )
    }
}

/* ============================ Push-to-talk ============================ */

/**
 * The big hold-to-talk key — a single rounded button with a thin darker bottom
 * lip for thickness and a soft cast shadow, matching the WT-01 mockup. Pressing
 * sinks the cap onto the lip. Orange when idle, charcoal when live.
 */
@Composable
fun PttButton(
    speaking: Boolean,
    onHoldStart: () -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Keep the latest callbacks without re-keying the gesture detector — re-keying
    // would cancel the in-flight press and swallow the release (latching it "on").
    val holdStart = rememberUpdatedState(onHoldStart)
    val holdEnd = rememberUpdatedState(onHoldEnd)

    // The cap sits flush at the top; a thin lip shows at the bottom until pressed.
    val sink by animateDpAsState(if (speaking) 8.dp else 0.dp, label = "ptt-sink")

    val faceTop = if (speaking) Color(0xFF46403A) else Color(0xFFF07C31)
    val faceMid = if (speaking) Color(0xFF332F2A) else Color(0xFFE5641C)
    val faceBot = if (speaking) Color(0xFF2A2621) else Color(0xFFD75A12)
    val lip = if (speaking) Color(0xFF1C1915) else Color(0xFFB04C14)
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .fillMaxWidth()
            .height(84.dp),
    ) {
        // Base — only its bottom edge peeks out as the button's lip.
        Box(
            Modifier
                .matchParentSize()
                .shadow(9.dp, shape, clip = false)
                .clip(shape)
                .background(lip),
        )
        // The pressable top face, flush at the top and full width.
        Box(
            Modifier
                .fillMaxWidth()
                .height(76.dp)
                .align(Alignment.TopCenter)
                .padding(top = sink)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0.0f to faceTop,
                        0.5f to faceMid,
                        1.0f to faceBot,
                    ),
                )
                .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        holdStart.value()
                        tryAwaitRelease()
                        holdEnd.value()
                    })
                },
            contentAlignment = Alignment.Center,
        ) {
            // A soft highlight banded across the very top of the face.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = if (speaking) 0.07f else 0.18f),
                                Color.Transparent,
                            ),
                        ),
                    ),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (speaking) "RELEASE TO LISTEN" else "HOLD TO TALK",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.size(3.dp))
                Text(
                    if (speaking) "Hold to talk" else "Release to listen",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/* ============================ Control buttons ============================ */

/** A round, raised control button (speaker / lock / mute) with a caption. */
@Composable
fun ControlButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(58.dp)
                .shadow(5.dp, CircleShape, clip = false)
                .clip(CircleShape)
                .background(
                    if (active) Brush.verticalGradient(listOf(AccentBright, Accent))
                    else Brush.verticalGradient(listOf(BodyTop, BodyBottom)),
                )
                .border(1.dp, if (active) Color.White.copy(alpha = 0.2f) else PanelLo.copy(alpha = 0.7f), CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (active) Color.White else Ink, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.size(7.dp))
        Text(label, color = InkSoft, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

/* ============================ Channel row ============================ */

/** A dark channel chip in the channels list. Selected rows get an orange frame. */
@Composable
fun ChannelRow(
    name: String,
    members: Int,
    number: String,
    tint: Color,
    selected: Boolean,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) ChannelBgActive else ChannelBg)
            .then(
                if (selected) Modifier.border(1.5.dp, Accent, RoundedCornerShape(16.dp))
                else Modifier.border(1.dp, Color.Black.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Groups, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.size(14.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = ChannelText, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.size(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(LedGreen))
                Spacer(Modifier.size(6.dp))
                Text("$members members online", color = ChannelDim, fontSize = 12.sp)
            }
        }
        Text(
            number,
            fontFamily = BrandMono,
            fontWeight = FontWeight.Bold,
            fontSize = 22.sp,
            color = if (selected) Accent else ChannelText,
        )
        Spacer(Modifier.size(6.dp))
        Icon(
            Icons.Filled.MoreVert, "Options",
            tint = ChannelDim,
            modifier = Modifier.size(20.dp).clip(CircleShape).clickable(onClick = onMenu),
        )
    }
}

/* ============================ Raised pill button ============================ */

/** A raised, bone-plastic pill — used for SCAN NEARBY and similar actions. */
@Composable
fun RaisedButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = Icons.Filled.Sensors,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(6.dp, RoundedCornerShape(16.dp), clip = false)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(BodyTop, BodyBottom)))
            .border(1.dp, PanelLo.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Ink, modifier = Modifier.size(20.dp))
            Spacer(Modifier.size(10.dp))
        }
        Text(label, color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 1.sp)
    }
}
