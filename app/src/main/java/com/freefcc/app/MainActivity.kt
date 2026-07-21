package com.freefcc.app

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
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
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlin.math.sin
import kotlin.math.PI

// ═══════════════════════════════════════════════════════════════════════
// Colors
// ═══════════════════════════════════════════════════════════════════════

private val BgDark = Color(0xFF0C0E11)
private val BgMid = Color(0xFF11151A)
private val BgLight = Color(0xFF1A2027)
private val CardBg = Color(0xFF151A20)
private val CardBorder = Color(0xFF303842)
private val Cyan = Color(0xFFFF9D4D)
private val Green = Color(0xFF4ED69A)
private val Amber = Color(0xFFFFD166)
private val Red = Color(0xFFFF5C70)
private val TextWhite = Color(0xFFF5F7FA)
private val TextGray = Color(0xFFA5AFBA)
private val TextDim = Color(0xFF687581)

private val BottomNavHeight = 60.dp
private val PageHorizontalPadding = 16.dp
private val PageTopPadding = 8.dp
private val PageBottomPadding = 12.dp
private val SectionSpacing = 8.dp

// ═══════════════════════════════════════════════════════════════════════
// Activity
// ═══════════════════════════════════════════════════════════════════════

class MainActivity : ComponentActivity() {

    private val viewModel: FccViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel.init()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        })

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

    override fun onStart() {
        super.onStart()
        viewModel.refreshLanBridgeBinding()
    }

}

// ═══════════════════════════════════════════════════════════════════════
// Root layout
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun AppRoot(viewModel: FccViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState(initialPage = 0) { 4 }
    val scope = rememberCoroutineScope()

    val entrance = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        entrance.animateTo(1f, tween(700, easing = EaseOutCubic))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(BgDark, BgMid, BgDark),
                    startY = 0f,
                    endY = Float.POSITIVE_INFINITY
                )
            )
            .alpha(entrance.value)
    ) {
        // Ambient glow — decorative only
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.radialGradient(
                        listOf(Cyan.copy(0.05f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 600f
                    )
                )
        )

        // Page content — fills space above the bottom nav
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = true
        ) { page ->
            when (page) {
                0 -> FccPage(state, viewModel)
                1 -> InfoPage(state, viewModel)
                2 -> LogPage(state, viewModel)
                3 -> UpdatePage(state, viewModel)
            }
        }

        // Bottom nav — fixed at the bottom, on top of everything
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
    val updateInfo = state.updateInfo
    val fccPresentation = fccUiPresentation(state.isFccEnabled)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontalPadding)
            .padding(bottom = BottomNavHeight + PageBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(4.dp))
        FccHeader(state)

        // Update-available banner — shows on the FCC page so the user
        // doesn't have to manually check the Update tab.
        if (state.updateAvailable && updateInfo != null && !state.isCheckingUpdate) {
            Spacer(Modifier.height(SectionSpacing))
            GlowCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Update available — v${updateInfo.version}",
                            color = Green, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "Tap Update to install",
                            color = TextDim, fontSize = 12.sp
                        )
                    }
                    Icon(
                        Icons.Filled.NewReleases,
                        contentDescription = "Update available",
                        tint = Green,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        GlowCard {
            ModeBadge(state)
            Spacer(Modifier.height(6.dp))

            when {
                state.isBusy -> {
                    ProgressDisplay(state.busyProgress, state.message)
                }
                !state.isConnected -> {
                    val connectLabel = if (state.status == "monitor_failed") "Retry Auto FCC" else "Auto FCC"
                    if (state.message.isNotEmpty()) {
                        BodyText(state.message)
                        Spacer(Modifier.height(8.dp))
                    }
                    GlowButton(connectLabel, Cyan, enabled = !state.isHardwareBusy) {
                        viewModel.connect(launchFlightAppAfterConnect = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    GlowButton("Send FCC Request", Cyan, filled = false, enabled = !state.isHardwareBusy) {
                        viewModel.enableFcc()
                    }
                }
                state.isKeepaliveRunning -> {
                    BodyText("Waiting for current Home Point. FCC will be applied automatically.", Amber)
                    Spacer(Modifier.height(8.dp))
                    GlowButton("Cancel Auto FCC", Red, filled = false) {
                        viewModel.stopKeepalive()
                    }
                }
                state.isFccEnabled -> {
                    BodyText("FCC request was written. RF mode is unknown; verify in DJI Fly.", Amber)
                    Spacer(Modifier.height(8.dp))
                    GlowButton("Auto FCC", Cyan, enabled = !state.isHardwareBusy) {
                        viewModel.connect(launchFlightAppAfterConnect = true)
                    }
                    Spacer(Modifier.height(8.dp))
                    GlowButton(fccPresentation.primaryActionLabel, Red, enabled = !state.isHardwareBusy) { viewModel.disableFcc() }
                    Spacer(Modifier.height(8.dp))
                    GlowButton("Re-Send FCC Request", Cyan, filled = false, enabled = !state.isHardwareBusy) { viewModel.enableFcc() }
                    Spacer(Modifier.height(8.dp))
                    GlowButton("Launch DJI Fly", Green, filled = false, enabled = !state.isHardwareBusy) {
                        viewModel.launchDjiFly()
                    }
                }
                else -> {
                    if (state.message.isNotEmpty()) {
                        BodyText(state.message)
                        Spacer(Modifier.height(8.dp))
                    } else {
                        BodyText("Send the FCC request, then verify RF mode in DJI Fly.")
                        Spacer(Modifier.height(8.dp))
                    }
                    if (state.status == "monitor_failed") {
                        GlowButton("Retry Home Point", Cyan, enabled = !state.isHardwareBusy) {
                            viewModel.startKeepalive()
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    GlowButton(fccPresentation.primaryActionLabel, Cyan, enabled = !state.isHardwareBusy) { viewModel.enableFcc() }
                }
            }

            if (state.aircraftSerial.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SerialRow(state.aircraftSerial, enabled = !state.isHardwareBusy) { viewModel.probeSerial() }
            }
        }

        Spacer(Modifier.height(SectionSpacing))

        AnimatedVisibility(
            visible = state.isConnected,
            enter = fadeIn(tween(300)) + expandVertically(tween(300)),
            exit = fadeOut(tween(200)) + shrinkVertically(tween(200))
        ) {
            Column {
                GlowCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SignalWaveIcon(
                            active = false,
                            color = Amber,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "4G Mode",
                            color = TextWhite,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    BodyText(
                        if (state.fourGMessage.isNotEmpty()) state.fourGMessage
                        else "Experimental. Probe the DUSS endpoint first; reachability does not prove activation.",
                        TextGray
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { viewModel.probe4gEndpoint() },
                        enabled = !state.isHardwareBusy && !state.is4gBusy,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Amber)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Probe 4G Endpoint")
                    }
                    Spacer(Modifier.height(8.dp))

                    if (state.is4gBusy) {
                        ProgressDisplay(state.busyProgress, "Sending 4G activation frames...")
                    } else {
                        GlowButton("Send 4G Activation Frames", Amber, enabled = !state.isHardwareBusy) {
                            viewModel.send4gActivationFrames()
                        }
                    }
                }
            }
        }

        // LED control card
        Spacer(Modifier.height(SectionSpacing))
        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Aircraft LEDs", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "State: ${state.ledState.name}",
                        color = when (state.ledState) {
                            LedState.ON -> Green
                            LedState.OFF -> TextGray
                            LedState.PARTIAL -> Amber
                            LedState.UNKNOWN -> TextDim
                        },
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (state.ledStatus.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(
                            state.ledStatus,
                            color = TextGray,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.refreshLedState() },
                    enabled = state.isConnected && !state.isLedBusy && !state.isHardwareBusy,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (state.isLedBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Cyan
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "Read LED state", tint = Cyan)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { viewModel.setLed(true) },
                    enabled = state.isConnected && !state.isLedBusy && !state.isHardwareBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Green,
                        contentColor = BgDark,
                        disabledContainerColor = Green.copy(0.2f),
                        disabledContentColor = Green.copy(0.4f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Green.copy(0.3f)),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("LED ON", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Button(
                    onClick = { viewModel.setLed(false) },
                    enabled = state.isConnected && !state.isLedBusy && !state.isHardwareBusy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = TextGray,
                        disabledContainerColor = TextGray.copy(0.1f),
                        disabledContentColor = TextGray.copy(0.3f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.5.dp, TextGray.copy(0.5f)),
                    modifier = Modifier.weight(1f).height(44.dp)
                ) {
                    Text("LED OFF", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }

    }
}
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun InfoPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontalPadding)
            .padding(bottom = BottomNavHeight + PageBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(PageTopPadding))
        PageTitle("Device Info", Icons.Outlined.Info)
        Spacer(Modifier.height(8.dp))

        GlowCard {
            Text("Connection", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            InfoRow("Controller", state.controllerModel.ifEmpty { "Unknown" })
            Spacer(Modifier.height(4.dp))
            DividerLine()
            Spacer(Modifier.height(4.dp))
            InfoRow(
                "Status",
                if (state.isConnected) "Connected" else "Disconnected",
                valueColor = if (state.isConnected) Green else TextGray
            )
            Spacer(Modifier.height(4.dp))
            DividerLine()
            Spacer(Modifier.height(4.dp))
            InfoRow("Aircraft S/N", state.aircraftSerial.ifEmpty { "Not detected" })
        }

        Spacer(Modifier.height(SectionSpacing))

        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Version Info", color = TextWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = { viewModel.queryDeviceInfo() },
                    enabled = state.isConnected && !state.isQueryingInfo && !state.isHardwareBusy,
                    modifier = Modifier.size(40.dp)
                ) {
                    if (state.isQueryingInfo) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            color = Cyan,
                            modifier = Modifier.size(22.dp)
                        )
                    } else {
                        Icon(Icons.Default.Refresh, "Query", tint = Cyan, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Spacer(Modifier.height(4.dp))

            if (state.deviceInfo.isNotEmpty()) {
                Text(
                    state.deviceInfo,
                    color = TextGray,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!state.isConnected) {
                BodyText("Connect to the controller first.", TextDim)
            } else {
                BodyText("Tap the refresh button to query version info.")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 3: Log
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun LogPage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontalPadding)
            .padding(bottom = BottomNavHeight + PageBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(PageTopPadding))
        PageTitle("Activity Log", Icons.Outlined.History)
        Spacer(Modifier.height(8.dp))

        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Wifi, null, tint = Cyan, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("LAN Control Bridge", color = TextWhite, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Live status, commands and logs · private Wi-Fi",
                        color = TextGray,
                        fontSize = 11.sp
                    )
                }
                if (state.isLanLogStarting) {
                    CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                } else {
                    Switch(
                        checked = state.lanLogUrl.isNotEmpty(),
                        onCheckedChange = viewModel::setLanLoggingEnabled
                    )
                }
            }
            if (state.lanLogMessage.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                BodyText(
                    state.lanLogMessage,
                    if (state.lanLogMessage.contains("failed", ignoreCase = true)) Red else TextGray
                )
            }
            if (state.lanLogUrl.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                BodyText("Codex can discover this controller through the UDP beacon; no link copying is needed.", TextDim)
            }
        }

        Spacer(Modifier.height(SectionSpacing))

        GlowCard {
            if (state.logMessages.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    BodyText("No activity yet.", TextDim)
                }
            } else {
                Column(
                    Modifier.fillMaxWidth()
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
                        if (index > 0) {
                            Spacer(Modifier.height(2.dp))
                            DividerLine(alpha = 0.3f)
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            entry,
                            color = color,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Page 4: Update
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun UpdatePage(state: AppState, viewModel: FccViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = PageHorizontalPadding)
            .padding(bottom = BottomNavHeight + PageBottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(PageTopPadding))
        PageTitle("Updates", Icons.Outlined.SystemUpdate)

        if (state.isCheckingUpdate) {
            Spacer(Modifier.height(8.dp))
            GlowCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(strokeWidth = 2.5.dp, color = Cyan, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    BodyText("Checking GitHub for latest release...", Cyan)
                }
            }
            return@Column
        }

        val info = state.updateInfo
        if (info == null && state.updateChecked) {
            Spacer(Modifier.height(8.dp))
            GlowCard {
                Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.CloudOff, null, tint = TextDim, modifier = Modifier.size(44.dp))
                    Spacer(Modifier.height(8.dp))
                    BodyText("Could not check for updates.", TextGray)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Make sure you're connected to Wi-Fi and try again.",
                        color = TextDim, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    GlowButton("Retry", Cyan) { viewModel.checkForUpdates(force = true) }
                }
            }
            return@Column
        }

        if (info == null) return@Column

        Spacer(Modifier.height(8.dp))

        GlowCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (state.updateAvailable) "Update Available" else "Up to Date",
                        color = if (state.updateAvailable) Green else TextGray,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Current: v${FccViewModel.APP_VERSION}",
                        color = TextDim, fontSize = 12.sp
                    )
                }
                Icon(
                    if (state.updateAvailable) Icons.Filled.NewReleases else Icons.Filled.CheckCircle,
                    null,
                    tint = if (state.updateAvailable) Green else TextDim,
                    modifier = Modifier.size(36.dp)
                )
            }

            if (state.updateAvailable) {
                Spacer(Modifier.height(10.dp))
                DividerLine()
                Spacer(Modifier.height(10.dp))
            }

            if (state.updateAvailable) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Latest:", color = TextGray, fontSize = 13.sp)
                    Text("v${info.version}", color = Green, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Released:", color = TextGray, fontSize = 13.sp)
                    Text(
                        info.publishedAt.split("T").firstOrNull() ?: "",
                        color = TextWhite, fontSize = 13.sp
                    )
                }
                if (info.apkSize > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Size:", color = TextGray, fontSize = 13.sp)
                        Text(
                            "%.1f MB".format(info.apkSize / 1048576.0),
                            color = TextWhite, fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            DividerLine()
            Spacer(Modifier.height(8.dp))

            Text("Changelog", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (info.changelog.isNotEmpty()) {
                Text(
                    info.changelog,
                    color = TextGray,
                    fontSize = 12.sp,
                    lineHeight = 19.sp
                )
            } else {
                BodyText("No changelog provided.", TextDim)
            }

            if (state.updateAvailable) {
                Spacer(Modifier.height(8.dp))
                when {
                    state.isDownloadingUpdate -> {
                        if (state.updateDownloadProgress <= 0f) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.5.dp,
                                    color = Cyan,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(Modifier.height(8.dp))
                                BodyText("Connecting to GitHub...", Cyan)
                            }
                        } else {
                            ProgressDisplay(
                                state.updateDownloadProgress,
                                "Downloading... (${(state.updateDownloadProgress * 100).toInt()}%)"
                            )
                        }
                    }
                    state.isUpdateDownloaded -> {
                        GlowButton("Install Update", Green) {
                            viewModel.installUpdate()
                        }
                        Spacer(Modifier.height(8.dp))
                        GlowButton("Download Again", Cyan, filled = false) {
                            viewModel.reDownloadUpdate()
                        }
                    }
                    else -> {
                        GlowButton("Download", Green) {
                            viewModel.downloadUpdate()
                        }
                    }
                }
            }

            // "Check Again" button — always visible at the bottom of the
            // update card, whether up-to-date or an update is available.
            // Uses force=true to bypass the rate-limit so the user can
            // recheck immediately at any time.
            Spacer(Modifier.height(12.dp))
            DividerLine()
            Spacer(Modifier.height(10.dp))
            GlowButton("Check Again", Cyan, filled = false) {
                viewModel.checkForUpdates(force = true)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Shared components
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun FccHeader(state: AppState) {
    val (connectionLabel, connectionColor) = when {
        state.status == "connecting" -> "Connecting" to Amber
        state.isConnected -> "Session ready" to Green
        state.status == "error" -> "Error" to Red
        else -> "Offline" to TextGray
    }
    val versionAndModel = if (state.controllerModel.isNotEmpty()) {
        "v${FccViewModel.APP_VERSION} · ${state.controllerModel}"
    } else {
        "v${FccViewModel.APP_VERSION}"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "FreeFCC",
            color = Cyan,
            fontSize = 22.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 0.5.sp
        )
        Spacer(Modifier.width(14.dp))
        Text(
            versionAndModel,
            color = TextDim,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Surface(
            color = connectionColor.copy(0.1f),
            shape = CircleShape,
            border = BorderStroke(1.dp, connectionColor.copy(0.3f))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Box(Modifier.size(6.dp).background(connectionColor, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    connectionLabel,
                    color = connectionColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun PageTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(9.dp))
        Text(title, color = TextWhite, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ModeBadge(state: AppState) {
    val active = state.isFccEnabled
    val presentation = fccUiPresentation(active)
    val bgBrush = if (active) {
        Brush.horizontalGradient(listOf(Color(0xFF2A1A10), Color(0xFF3A2113), Color(0xFF2A1A10)))
    } else {
        Brush.horizontalGradient(listOf(BgLight.copy(0.4f), BgLight.copy(0.2f)))
    }

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgBrush)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "MODE",
                color = TextDim,
                fontSize = 9.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.5.sp
            )
            Spacer(Modifier.width(8.dp))
            Text(
                presentation.badgeTitle,
                color = if (active) Amber else TextWhite,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.weight(1f))
            if (active) {
                Icon(
                    Icons.Outlined.Info, null, tint = Amber,
                    modifier = Modifier.size(24.dp).scale(checkScale.value)
                )
            } else {
                Icon(
                    Icons.Outlined.Radio, null, tint = TextDim,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            presentation.detail,
            color = if (active) Amber.copy(0.8f) else TextGray,
            fontSize = 10.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun ProgressDisplay(progress: Float, label: String) {
    val safeProgress = progress.coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(BgLight)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(safeProgress)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Brush.horizontalGradient(listOf(Cyan, Green)))
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${(safeProgress * 100).toInt()}%",
            color = TextGray,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun BodyText(text: String, color: Color = TextGray) {
    Text(
        text,
        color = color,
        fontSize = 13.sp,
        lineHeight = 20.sp
    )
}

@Composable
private fun SerialRow(serial: String, enabled: Boolean = true, onRefresh: () -> Unit) {
    val identityLabel = if (serial.startsWith("W")) "Model: " else "S/N: "
    Surface(
        color = BgLight.copy(0.4f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Filled.Flight, null, tint = Cyan.copy(0.6f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(identityLabel, color = TextGray, fontSize = 12.sp)
            Text(
                serial,
                color = TextWhite,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRefresh, enabled = enabled, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.Refresh, "Refresh", tint = TextGray, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, valueColor: Color = TextWhite) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextGray, fontSize = 13.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f)
        )
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
private fun GlowCard(content: @Composable () -> Unit) {
    Surface(
        color = CardBg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, CardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .fillMaxWidth()
        ) {
            content()
        }
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
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (filled) color else Color.Transparent,
            contentColor = if (filled) BgDark else color,
            disabledContainerColor = color.copy(0.2f),
            disabledContentColor = color.copy(0.4f)
        ),
        shape = RoundedCornerShape(10.dp),
        border = when {
            !filled && enabled -> BorderStroke(1.5.dp, color.copy(0.6f))
            filled && enabled -> BorderStroke(1.dp, color.copy(0.3f))
            else -> null
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
    ) {
        Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 0.3.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Signal wave icon
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun SignalWaveIcon(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val phase = if (active) {
        val transition = rememberInfiniteTransition(label = "wave")
        val animatedPhase by transition.animateFloat(
            0f, (2 * PI).toFloat(),
            infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
            label = "wavePhase"
        )
        animatedPhase
    } else {
        0f
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val centerY = h / 2
        val amplitude = if (active) h * 0.25f else h * 0.08f
        val lineColor = if (active) color else color.copy(0.35f)

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
        Triple("Update", Icons.Filled.SystemUpdate, Color(0xFF79A8FF))
    )

    Surface(
        color = BgDark.copy(0.98f),
        shadowElevation = 8.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(BottomNavHeight)
                .padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEachIndexed { index, (label, icon, color) ->
                val selected = currentPage == index

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .weight(1f)
                        .selectable(
                            selected = selected,
                            onClick = { onPageSelected(index) },
                            role = Role.Tab
                        )
                        .padding(vertical = 4.dp)
                ) {
                    Icon(
                        icon, label,
                        tint = if (selected) color else TextDim,
                        modifier = Modifier.size(21.dp)
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        color = if (selected) color else TextDim,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(2.dp))
                    Box(
                        modifier = Modifier
                            .width(24.dp)
                            .height(2.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (selected) color else Color.Transparent)
                    )
                }
            }
        }
    }
}
