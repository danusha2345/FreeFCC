package com.freefcc.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.math.PI

// ═══════════════════════════════════════════════════════════════════════
// Color palette — deep dark + neon accents
// ═══════════════════════════════════════════════════════════════════════

private val BgDark = Color(0xFF070A14)
private val BgMid = Color(0xFF0D1220)
private val BgLight = Color(0xFF121830)
private val CardBg = Color(0xFF10162A)
private val CardBorder = Color(0xFF1C2848)
private val Cyan = Color(0xFF4FC3F7)
private val Green = Color(0xFF34D399)
private val Amber = Color(0xFFF59E0B)
private val Red = Color(0xFFEF4444)
private val Purple = Color(0xFFA78BFA)
private val TextWhite = Color(0xFFF0F4FF)
private val TextGray = Color(0xFF7A85A3)
private val TextDim = Color(0xFF4A5374)

// ═══════════════════════════════════════════════════════════════════════
// Main activity
// ═══════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private val viewModel: FccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan, onPrimary = BgDark,
                    background = BgDark, onBackground = TextWhite,
                    surface = CardBg, onSurface = TextWhite,
                    error = Red, secondary = Green, tertiary = Amber
                )
            ) {
                AppRoot(viewModel)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Root — gradient background, pager, bottom nav
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AppRoot(viewModel: FccViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 4 }
    val scope = rememberCoroutineScope()

    // Entrance fade
    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(700, easing = EaseOutCubic))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BgDark, BgMid, BgDark, BgDark),
                    startY = 0f,
                    endY = 2000f
                )
            )
            .alpha(entrance.value)
    ) {
        // Ambient glow at top
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        listOf(Cyan.copy(0.06f), Color.Transparent),
                        center = Offset(Float.POSITIVE_INFINITY, 0f),
                        radius = 600f
                    )
                )
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 76.dp),
            userScrollEnabled = true
        ) { page ->
            when (page) {
                0 -> FccPage(state, viewModel)
                1 -> InfoPage(state, viewModel)
                2 -> LogPage(state)
                3 -> SupportPage()
            }
        }

        BottomNavBar(
            currentPage = pagerState.currentPage,
            onPageSelected = { index ->
                scope.launch { pagerState.animateScrollToPage(index) }
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 1: FCC
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FccPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))
        AppHeader(state.controllerModel)
        Spacer(Modifier.height(24.dp))
        ConnectionPill(state)
        Spacer(Modifier.height(24.dp))

        // ── Main FCC card ──
        GlowCard {
            ModeBadge(state)
            Spacer(Modifier.height(18.dp))

            when {
                state.isBusy -> ProgressDisplay(state.busyProgress, state.message)
                !state.isConnected -> {
                    StatusText("Connect your drone to the controller, then power it on.", TextGray)
                    Spacer(Modifier.height(18.dp))
                    GlowButton("Connect", Cyan) { viewModel.connect() }
                }
                state.isFccEnabled -> {
                    StatusText("FCC mode is active.", Green)
                    Spacer(Modifier.height(16.dp))
                    GlowButton("Stop FCC Mode", Red) { viewModel.disableFcc() }
                    Spacer(Modifier.height(10.dp))
                    GlowButton("Re-Apply FCC", Cyan, filled = false) { viewModel.enableFcc() }
                }
                else -> {
                    if (state.message.isNotEmpty()) StatusText(state.message, TextGray)
                    Spacer(Modifier.height(18.dp))
                    GlowButton("Enable FCC Mode", Cyan) { viewModel.enableFcc() }
                }
            }

            if (state.aircraftSerial.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SerialRow(state.aircraftSerial) { viewModel.probeSerial() }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 4G card ──
        AnimatedVisibility(
            visible = state.isConnected,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Column {
                GlowCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SignalWaveIcon(
                            active = state.is4gEnabled,
                            color = Amber,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("4G Mode", color = TextWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (state.is4gEnabled) {
                            StatusDot(Green)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    StatusText(
                        if (state.is4gEnabled) "4G transmission is active." else "Enable 4G transmission on the aircraft.",
                        if (state.is4gEnabled) Green else TextGray
                    )
                    Spacer(Modifier.height(18.dp))

                    if (state.is4gBusy) {
                        ProgressDisplay(state.busyProgress, "Sending 4G frames...")
                    } else {
                        GlowButton(
                            if (state.is4gEnabled) "Turn 4G OFF" else "Turn 4G ON",
                            Amber,
                            filled = state.is4gEnabled
                        ) {
                            if (state.is4gEnabled) viewModel.disable4g() else viewModel.enable4g()
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 2: Info
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InfoPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))
        PageTitle("Device Info", Icons.Outlined.Info)
        Spacer(Modifier.height(24.dp))

        // Connection status
        GlowCard {
            Text("Connection", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            InfoRow("Controller", state.controllerModel.ifEmpty { "—" })
            DividerLine()
            InfoRow("Status", if (state.isConnected) "Connected" else "Disconnected",
                valueColor = if (state.isConnected) Green else TextGray)
            DividerLine()
            InfoRow("Aircraft S/N", state.aircraftSerial.ifEmpty { "—" })
        }

        Spacer(Modifier.height(16.dp))

        // Version info
        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Version Info", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { viewModel.queryDeviceInfo() },
                    enabled = state.isConnected && !state.isQueryingInfo,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (state.isQueryingInfo) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp, color = Cyan,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "Query", tint = Cyan, modifier = Modifier.size(22.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            if (state.deviceInfo.isNotEmpty()) {
                Text(
                    state.deviceInfo,
                    color = TextGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 18.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!state.isConnected) {
                StatusText("Connect to the controller first.", TextDim)
            } else {
                StatusText("Tap the refresh button to query version info.", TextGray)
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 3: Log
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LogPage(state: AppState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))
        PageTitle("Activity Log", Icons.Outlined.History)
        Spacer(Modifier.height(24.dp))

        GlowCard {
            if (state.logMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    StatusText("No activity yet.", TextDim)
                }
            } else {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 550.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    state.logMessages.forEachIndexed { index, entry ->
                        val color = when {
                            entry.contains("enabled", true) ||
                            entry.contains("connected", true) ||
                            entry.contains("restored", true) ||
                            entry.contains("received", true) -> Green
                            entry.contains("fail", true) ||
                            entry.contains("error", true) -> Red
                            entry.contains("Enabling", true) ||
                            entry.contains("Disabling", true) ||
                            entry.contains("Probing", true) ||
                            entry.contains("Querying", true) ||
                            entry.contains("Loaded", true) -> Amber
                            else -> Cyan.copy(0.6f)
                        }
                        if (index > 0) DividerLine(alpha = 0.3f)
                        Text(
                            entry,
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 5.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 4: Support
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SupportPage() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(52.dp))
        PageTitle("Support FreeFCC", Icons.Outlined.FavoriteBorder)
        Spacer(Modifier.height(24.dp))

        // Heart icon with pulse
        val heartPulse = rememberInfiniteTransition(label = "heart")
        val heartScale by heartPulse.animateFloat(
            1f, 1.15f,
            infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse),
            label = "heartScale"
        )

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(100.dp)) {
            Box(
                Modifier
                    .size(80.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Red.copy(0.08f), Color.Transparent),
                            radius = 80f
                        )
                    )
            )
            Icon(
                Icons.Filled.Favorite,
                null,
                tint = Red.copy(0.7f),
                modifier = Modifier.size(48.dp).scale(heartScale)
            )
        }

        Spacer(Modifier.height(16.dp))
        StatusText("FreeFCC is free and open source.", TextWhite)
        Spacer(Modifier.height(4.dp))
        StatusText("If it helped you, consider supporting development.", TextGray)
        Spacer(Modifier.height(24.dp))

        // Ko-fi button
        GlowButton(
            "Support on Ko-fi",
            Cyan,
            filled = true
        ) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/freefcc"))
            context.startActivity(intent)
        }

        Spacer(Modifier.height(16.dp))

        // Link text
        Text(
            "ko-fi.com/freefcc",
            color = Cyan.copy(0.6f),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.clickable {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/freefcc"))
                context.startActivity(intent)
            }
        )

        Spacer(Modifier.height(32.dp))

        // About card
        GlowCard {
            Text("About", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            StatusText("FreeFCC is an open-source tool for unlocking FCC mode on DJI controllers. It uses the publicly documented DUMPL protocol and requires no server, license, or internet connection.", TextGray)
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(12.dp))
            InfoRow("Version", "1.0")
            Spacer(Modifier.height(6.dp))
            InfoRow("License", "Open Source")
            Spacer(Modifier.height(6.dp))
            InfoRow("Protocol", "DUMPL (0x55)")
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(12.dp))
            StatusText("Not affiliated with DJI. Use at your own risk.", TextDim)
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Components
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AppHeader(model: String) {
    // Pulsing glow behind title
    val glow = rememberInfiniteTransition(label = "hdr")
    val glowAlpha by glow.animateFloat(
        0.4f, 0.8f,
        infiniteRepeatable(tween(2800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "hdrGlow"
    )
    // Title slide-in
    val slideIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { slideIn.animateTo(1f, tween(800, delayMillis = 200, easing = EaseOutCubic)) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(slideIn.value)
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Glow backdrop
            Box(
                Modifier
                    .size(160.dp, 50.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Cyan.copy(glowAlpha * 0.15f), Color.Transparent),
                            radius = 120f
                        )
                    )
            )
            Text(
                "FreeFCC",
                color = Cyan.copy(alpha = glowAlpha),
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            if (model.isNotEmpty()) "v1.0 · $model" else "v1.0",
            color = TextDim, fontSize = 11.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun PageTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val slideIn = remember { Animatable(0f) }
    LaunchedEffect(Unit) { slideIn.animateTo(1f, tween(500, easing = EaseOutCubic)) }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.alpha(slideIn.value)
    ) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, color = TextWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ConnectionPill(state: AppState) {
    val (label, color) = when {
        state.status == "connecting" -> "Connecting..." to Amber
        state.isConnected -> "Connected" to Green
        state.status == "error" -> "Error" to Red
        else -> "Disconnected" to TextGray
    }

    // Pop animation on state change
    val popScale = remember { Animatable(1f) }
    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            popScale.animateTo(1.2f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            popScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        }
    }

    // Glow for connected state
    val glowAlpha: Float = if (state.isConnected) {
        val t = rememberInfiniteTransition(label = "pill")
        val a by t.animateFloat(0.15f, 0.35f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pillGlow")
        a
    } else 0f

    Surface(
        color = color.copy(0.1f),
        shape = CircleShape,
        border = BorderStroke(1.dp, color.copy(0.3f)),
        modifier = Modifier
            .padding(4.dp)
            .scale(popScale.value)
            .drawBehind {
                if (glowAlpha > 0f) {
                    drawCircle(color.copy(glowAlpha), radius = size.maxDimension * 0.8f)
                }
            }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, CircleShape)
                    .drawBehind { drawCircle(color.copy(0.4f), radius = 12f) }
            )
            Spacer(Modifier.width(10.dp))
            Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ModeBadge(state: AppState) {
    val active = state.isFccEnabled
    val bgBrush = if (active) {
        Brush.horizontalGradient(listOf(Color(0xFF0A2540), Color(0xFF0E3050), Color(0xFF0A2540)))
    } else {
        Brush.horizontalGradient(listOf(BgLight.copy(0.5f), BgLight.copy(0.3f)))
    }

    // Bouncy checkmark
    val checkScale = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            checkScale.snapTo(0f)
            checkScale.animateTo(1.2f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
            checkScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
        } else {
            checkScale.snapTo(0f)
        }
    }

    // Animated border glow when active
    val borderGlow: Float = if (active) {
        val t = rememberInfiniteTransition(label = "modeBorder")
        val a by t.animateFloat(0.1f, 0.3f, infiniteRepeatable(tween(2000), RepeatMode.Reverse), label = "modeGlow")
        a
    } else 0f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bgBrush)
            .drawBehind {
                if (borderGlow > 0f) {
                    drawRoundRect(
                        Green.copy(borderGlow),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Column {
            Text(
                "MODE",
                color = TextDim,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (active) "FCC" else "CE",
                color = if (active) Green else TextWhite,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (active) "High-power region active" else "Default region",
                color = if (active) Green.copy(0.8f) else TextGray,
                fontSize = 12.sp
            )
        }
        if (active) {
            Icon(
                Icons.Filled.CheckCircle, null, tint = Green,
                modifier = Modifier.size(44.dp).scale(checkScale.value)
            )
        } else {
            Icon(
                Icons.Outlined.Radio, null, tint = TextDim,
                modifier = Modifier.size(36.dp)
            )
        }
    }
}

@Composable
private fun ProgressDisplay(progress: Float, label: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(14.dp))
        // Custom progress bar with gradient
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BgLight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(listOf(Cyan, Green))
                    )
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${(progress * 100).toInt()}%",
            color = TextGray, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun StatusText(text: String, color: Color = TextGray) {
    Text(text, color = color, fontSize = 13.sp, lineHeight = 19.sp)
}

@Composable
private fun SerialRow(serial: String, onRefresh: () -> Unit) {
    Surface(
        color = BgLight.copy(0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Icon(Icons.Filled.Flight, null, tint = Cyan.copy(0.7f), modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text("S/N: ", color = TextGray, fontSize = 12.sp)
            Text(serial, color = TextWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRefresh, modifier = Modifier.size(20.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", tint = TextGray, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = TextWhite) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextGray, fontSize = 13.sp)
        Text(value, color = valueColor, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DividerLine(alpha: Float = 0.5f) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CardBorder.copy(alpha))
    )
}

@Composable
private fun StatusDot(color: Color) {
    val pulse = rememberInfiniteTransition(label = "dot")
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "dotPulse")
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color.copy(alpha), CircleShape)
            .drawBehind { drawCircle(color.copy(0.3f), radius = 16f) }
    )
}

@Composable
private fun GlowCard(content: @Composable () -> Unit) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    Brush.verticalGradient(
                        listOf(Cyan.copy(0.04f), Color.Transparent),
                        startY = 0f,
                        endY = size.height * 0.3f
                    ),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())
                )
            }
    ) {
        Box(Modifier.padding(20.dp)) { content() }
    }
}

@Composable
private fun GlowButton(
    text: String,
    color: Color,
    filled: Boolean = true,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val pressScale = remember { Animatable(1f) }

    Button(
        onClick = {
            if (enabled) {
                onClick()
            }
        },
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) color else Color.Transparent,
            contentColor = if (filled) BgDark else color,
            disabledContainerColor = color.copy(0.2f),
            disabledContentColor = color.copy(0.4f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = if (!filled && enabled) BorderStroke(1.5.dp, color.copy(0.6f))
                 else if (filled && enabled) BorderStroke(1.dp, color.copy(0.3f))
                 else null,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .scale(pressScale.value)
            .drawBehind {
                if (enabled && filled) {
                    drawRoundRect(
                        color.copy(0.1f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx())
                    )
                }
            }
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.5.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Animated signal wave icon
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SignalWaveIcon(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "wavePhase"
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2
        val amplitude = if (active) h * 0.25f else h * 0.1f
        val lineColor = if (active) color else color.copy(0.4f)

        // Draw sine wave
        val path = androidx.compose.ui.graphics.Path()
        for (x in 0..w.toInt() step 2) {
            val y = centerY + amplitude * sin((x / w).toDouble() * 2.0 * PI + phase.toDouble()).toFloat()
            if (x == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
        }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Bottom navigation bar
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun BottomNavBar(
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        Triple("FCC", Icons.Filled.Wifi, Cyan),
        Triple("Info", Icons.Filled.Info, Green),
        Triple("Log", Icons.Filled.History, Amber),
        Triple("Support", Icons.Filled.Favorite, Red)
    )

    Surface(
        color = BgDark.copy(0.97f),
        modifier = modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .drawBehind {
                    drawLine(
                        CardBorder,
                        start = Offset(0f, 0f),
                        end = Offset(size.width, 0f),
                        strokeWidth = 1.dp.toPx()
                    )
                }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                tabs.forEachIndexed { index, (label, icon, color) ->
                    val selected = currentPage == index

                    // Indicator line above selected tab
                    val indicatorProgress = remember { Animatable(if (selected) 1f else 0f) }
                    LaunchedEffect(selected) {
                        indicatorProgress.animateTo(if (selected) 1f else 0f, tween(250))
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onPageSelected(index) }
                            .padding(vertical = 10.dp)
                    ) {
                        Box(contentAlignment = Alignment.BottomCenter) {
                            Icon(
                                icon, label,
                                tint = if (selected) color else TextDim,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            label,
                            color = if (selected) color else TextDim,
                            fontSize = 10.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Spacer(Modifier.height(3.dp))
                        // Indicator bar
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (selected) color else Color.Transparent)
                        )
                    }
                }
            }
        }
    }
}