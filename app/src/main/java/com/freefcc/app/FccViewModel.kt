package com.freefcc.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Immutable UI state for the entire app.
 *
 * The ViewModel updates this via copy() and the Compose layer observes it
 * with collectAsStateWithLifecycle(). Every field here represents something
 * the UI needs to render.
 */
data class AppState(
    val status: String = "idle",
    val message: String = "",
    val isConnected: Boolean = false,
    // Process-local proof of a completed FCC profile write, never RF readback.
    val isFccEnabled: Boolean = false,
    val fccLastWriteAtMs: Long? = null,
    val is4gBusy: Boolean = false,
    val fourGMessage: String = "",
    val isBusy: Boolean = false,
    val isHardwareBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val controllerModel: String = "",
    val deviceInfo: String = "",
    val isQueryingInfo: Boolean = false,
    val isLedBusy: Boolean = false,
    val ledState: LedState = LedState.UNKNOWN,
    val ledRawValue: Int? = null,
    val ledStatus: String = "",
    val isGpsBusy: Boolean = false,
    val gpsState: GpsState = GpsState.UNKNOWN,
    val gpsRawValue: Int? = null,
    val gpsStatus: String = "",
    val logMessages: List<String> = emptyList(),
    // Update state
    val updateInfo: UpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val updateDownloadProgress: Float = 0f,
    val isUpdateDownloaded: Boolean = false,
    val updateAvailable: Boolean = false,
    val updateChecked: Boolean = false,
    // Keepalive state
    val isKeepaliveRunning: Boolean = false,
    // Opt-in LAN diagnostic bridge state
    val isLanLogStarting: Boolean = false,
    val lanLogUrl: String = "",
    val lanLogMessage: String = ""
)

internal data class FourGIdentity(
    val payloadSerial: String,
    val modelCode: String?
)

private class LanWriteLease(
    private val hardwareLease: HardwareLock.Lease,
    private val releaseLedGate: () -> Unit
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            hardwareLease.close()
            releaseLedGate()
        }
    }
}

/**
 * Manages all app state and business logic.
 *
 * The UI never touches the transport layer directly. It calls methods on
 * this ViewModel, which runs operations on a background thread (Dispatchers.IO)
 * and updates the observable [state] flow. The UI reacts to state changes
 * automatically via Compose's collectAsStateWithLifecycle().
 *
 * @param app The Application context, used for SharedPreferences and asset loading
 */
class FccViewModel(private val app: Application) : AndroidViewModel(app) {

    companion object {
        val APP_VERSION: String = BuildConfig.VERSION_NAME

        private const val MAX_LOG_ENTRIES = 200
        private const val PREF_GPS_STATE = "gps_last_verified_state"
        private const val PREF_GPS_RAW = "gps_last_verified_raw"
        private const val PREF_GPS_AT = "gps_last_verified_at"
        private const val PREF_LED_STATE = "led_last_verified_state"
        private const val PREF_LED_RAW = "led_last_verified_raw"
        private const val PREF_LED_AT = "led_last_verified_at"
        private val processLogLock = Any()
        private val processLogs = ArrayDeque<String>()
        private val lanDiagnosticBusy = AtomicBoolean(false)
        private val ledOperationBusy = AtomicBoolean(false)
        private val gpsOperationBusy = AtomicBoolean(false)
        @Volatile private var activeLanController: FccViewModel? = null
        private val networkLogServer = NetworkLogServer(
            logSnapshot = {
                synchronized(processLogLock) { processLogs.toList() }
            },
            apiStatusSnapshot = {
                activeLanController?.lanStatusJson()
                    ?: LanJson.objectOf("ok" to false, "error" to "app_not_ready")
            },
            apiCommandHandler = { params ->
                activeLanController?.handleLanCommand(params)
                    ?: NetworkApiResponse(
                        503,
                        LanJson.objectOf("ok" to false, "error" to "app_not_ready")
                    )
            }
        )

        private val LAN_COMMANDS = listOf(
            "ping",
            "connect",
            "fcc_enable",
            "ce_restore",
            "keepalive_start",
            "keepalive_stop",
            "home_point_wait_start",
            "home_point_wait_stop",
            "led_read",
            "led_on",
            "led_off",
            "gps_read",
            "gps_on",
            "gps_off",
            "device_info",
            "serial_probe",
            "four_g_probe",
            "four_g_activate",
            "update_check",
            "update_download",
            "launch_dji_fly",
            "duml_send",
            "duml_request",
            "duml_capture",
            "wire_exchange"
        )

        private val FULL_SERIAL_PATTERN = Regex("^1581[0-9A-Z]{12,18}$")
        private val MODEL_CODE_PATTERN = Regex("^W[AM][0-9]{3}")

        /**
         * Aircraft model codes known to support DJI Cellular Dongle 2 / 4G.
         * The Mini series (wa150, wa140, wm16x) does NOT support 4G — the
         * cellular module is enterprise hardware only. Sending 4G frames to a
         * non-4G aircraft wastes the user's time and produces a confusing
         * "frames written but 4G didn't activate" message.
         *
         * Sources: DJI product list, captured profiles (only wa341 confirmed
         * working on real hardware). wa233/wa234 = Matrice 300/350 series,
         * wm630 = Inspire 3, wa341 = Mavic 4 Pro. All are DJI enterprise models
         * that ship with or accept the Cellular Dongle 2.
         */
        private val MODELS_WITH_4G = setOf("wa341", "wa233", "wa234", "wm630")

        /**
         * Normalizes an identity returned by [DumlTransport.probeSerial]. A
         * full factory serial does not contain a model code, so model gating
         * is only possible for the short WA/WM form. The 4G socket check still
         * protects the full-serial path from sending when no 4G DUSS endpoint
         * is available.
         */
        internal fun parseFourGIdentity(raw: String): FourGIdentity? {
            val normalized = raw.trim().uppercase(Locale.US)
            if (FULL_SERIAL_PATTERN.matches(normalized)) {
                return FourGIdentity(normalized, null)
            }
            val modelCode = MODEL_CODE_PATTERN.find(normalized)?.value?.lowercase(Locale.US)
                ?: return null
            return FourGIdentity(normalized, modelCode)
        }

        internal fun isSupportedFourGModel(modelCode: String): Boolean =
            modelCode.lowercase(Locale.US) in MODELS_WITH_4G

        private fun processLogSnapshot(): List<String> =
            synchronized(processLogLock) { processLogs.toList() }

        private fun appendProcessLog(entry: String) {
            synchronized(processLogLock) {
                processLogs.addLast(entry)
                while (processLogs.size > MAX_LOG_ENTRIES) processLogs.removeFirst()
            }
        }

        /** Allows the foreground service to publish lifecycle evidence to LAN logs. */
        internal fun logServiceEvent(message: String) {
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            appendProcessLog("[$time] $message")
            activeLanController?.refreshProcessLogs()
        }
    }

    private val _state = MutableStateFlow(
        AppState(logMessages = processLogSnapshot().asReversed())
    )
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val transport = DumlTransport()
    private val prefs = app.getSharedPreferences("freefcc", Context.MODE_PRIVATE)
    private var initialized = false
    @Volatile private var aircraftIdentityVerified = false

    init {
        // MainActivity.onCreate() calls init() below on every Activity re-creation
        // (e.g. config change), but this class init{} runs exactly once per
        // ViewModel instance — the collector must live here, not in init().
        viewModelScope.launch {
            HardwareLock.busy.collect { busy -> update { copy(isHardwareBusy = busy) } }
        }
        viewModelScope.launch {
            FccRuntime.tracker.state.collect { runtime ->
                val hasWriteEvidence = runtime.lastSuccessfulWriteAtMs != null
                val keepaliveActive = runtime.keepaliveStatus == KeepaliveRuntimeStatus.STARTING ||
                    runtime.keepaliveStatus == KeepaliveRuntimeStatus.RUNNING
                update {
                    copy(
                        isKeepaliveRunning = keepaliveActive,
                        isFccEnabled = hasWriteEvidence,
                        fccLastWriteAtMs = runtime.lastSuccessfulWriteAtMs,
                        message = when {
                            runtime.keepaliveStatus == KeepaliveRuntimeStatus.FAILED && runtime.error != null ->
                                runtime.error
                            runtime.keepaliveStatus == KeepaliveRuntimeStatus.STOPPED &&
                                runtime.lastAutomaticApplyAttempt?.outcome == FccApplyOutcome.ALL_WRITES_FLUSHED ->
                                "FCC request written after Home Point — verify RF mode in DJI Fly"
                            else -> message
                        },
                        status = resolveFccRuntimeStatus(
                            currentStatus = status,
                            isConnected = isConnected,
                            keepaliveStatus = runtime.keepaliveStatus,
                            hasWriteEvidence = hasWriteEvidence
                        )
                    )
                }
            }
        }
        // Restore the cached aircraft serial from a previous session so the
        // user does not have to re-probe before 4G if the drone is the same.
        val cachedSerial = prefs.getString("aircraft_serial", "").orEmpty()
        if (cachedSerial.isNotEmpty()) {
            update { copy(aircraftSerial = cachedSerial) }
        }
        restorePersistedControlReadbacks()
    }

    private fun restorePersistedControlReadbacks() {
        val cachedGps = loadPersistedGpsReadback()
        val cachedLed = loadPersistedLedReadback()
        if (cachedGps == null && cachedLed == null) return

        update {
            copy(
                gpsState = cachedGps?.first?.state ?: gpsState,
                gpsRawValue = cachedGps?.first?.rawValue ?: gpsRawValue,
                gpsStatus = cachedGps?.let { persistedStatus("Last verified", gpsLabel(it.first), it.second) }
                    ?: gpsStatus,
                ledState = cachedLed?.first?.state ?: ledState,
                ledRawValue = cachedLed?.first?.rawValue ?: ledRawValue,
                ledStatus = cachedLed?.let { persistedStatus("Last verified", ledLabel(it.first), it.second) }
                    ?: ledStatus
            )
        }
    }

    private fun loadPersistedGpsReadback(): Pair<GpsReadback, Long>? {
        if (!prefs.contains(PREF_GPS_STATE) || !prefs.contains(PREF_GPS_RAW)) return null
        val state = runCatching {
            GpsState.valueOf(prefs.getString(PREF_GPS_STATE, null).orEmpty())
        }.getOrNull() ?: return null
        return GpsReadback(state, prefs.getInt(PREF_GPS_RAW, 0)) to prefs.getLong(PREF_GPS_AT, 0L)
    }

    private fun loadPersistedLedReadback(): Pair<LedReadback, Long>? {
        if (!prefs.contains(PREF_LED_STATE) || !prefs.contains(PREF_LED_RAW)) return null
        val state = runCatching {
            LedState.valueOf(prefs.getString(PREF_LED_STATE, null).orEmpty())
        }.getOrNull() ?: return null
        return LedReadback(state, prefs.getInt(PREF_LED_RAW, 0)) to prefs.getLong(PREF_LED_AT, 0L)
    }

    private fun persistGpsReadback(readback: GpsReadback, verifiedAtMs: Long) {
        prefs.edit()
            .putString(PREF_GPS_STATE, readback.state.name)
            .putInt(PREF_GPS_RAW, readback.rawValue)
            .putLong(PREF_GPS_AT, verifiedAtMs)
            .apply()
    }

    private fun persistLedReadback(readback: LedReadback, verifiedAtMs: Long) {
        prefs.edit()
            .putString(PREF_LED_STATE, readback.state.name)
            .putInt(PREF_LED_RAW, readback.rawValue)
            .putLong(PREF_LED_AT, verifiedAtMs)
            .apply()
    }

    private fun persistedStatus(prefix: String, label: String, verifiedAtMs: Long): String {
        val timestamp = if (verifiedAtMs > 0L) {
            SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(verifiedAtMs))
        } else {
            "unknown time"
        }
        return "$prefix $label at $timestamp"
    }

    private fun gpsLabel(readback: GpsReadback): String = when (readback.state) {
        GpsState.ON -> "ON"
        GpsState.OFF -> "OFF"
        GpsState.UNEXPECTED -> "UNEXPECTED (0x%02X)".format(Locale.US, readback.rawValue)
        GpsState.UNKNOWN -> "UNKNOWN"
    }

    private fun ledLabel(readback: LedReadback): String = when (readback.state) {
        LedState.ON -> "ON"
        LedState.OFF -> "OFF"
        LedState.PARTIAL -> "PARTIAL (0x%02X)".format(Locale.US, readback.rawValue)
        LedState.UNKNOWN -> "UNKNOWN"
    }

    /** Claims the shared hardware lock for one operation, or null if it is busy. */
    private fun beginHardwareOp(): HardwareLock.Lease? = HardwareLock.tryBegin()

    /** Serializes LAN probes and holds hardware gates only through socket flush. */
    private fun beginLanWrite(): LanWriteLease? {
        val hardwareLease = beginHardwareOp() ?: return null
        if (!ledOperationBusy.compareAndSet(false, true)) {
            hardwareLease.close()
            return null
        }
        return LanWriteLease(hardwareLease) { ledOperationBusy.set(false) }
    }

    fun init() {
        activeLanController = this
        if (initialized) return
        initialized = true
        val model = try { Build.DEVICE } catch (_: Exception) { "unknown" }
        prefs.edit().remove("auto_fcc").apply()
        // v1.5.12 and earlier persisted a write as if it were current FCC
        // state. Ignore and remove that stale cross-process evidence.
        prefs.edit().remove("fcc_sequence_written").apply()
        val keepaliveRequested = FccKeepaliveService.isRunningFlagSet(app)
        val runtime = FccRuntime.tracker.state.value
        val liveMonitor = keepaliveRequested &&
            (runtime.keepaliveStatus == KeepaliveRuntimeStatus.STARTING ||
                runtime.keepaliveStatus == KeepaliveRuntimeStatus.RUNNING)
        if (keepaliveRequested && !liveMonitor) {
            FccKeepaliveService.clearStaleRequest(app)
            log("Cleared stale Auto FCC marker")
        }
        update {
            val connected = runtime.controllerSessionEstablished
            val restoredStatus = resolveFccRuntimeStatus(
                currentStatus = if (connected) "connected" else "disconnected",
                isConnected = connected,
                keepaliveStatus = runtime.keepaliveStatus,
                hasWriteEvidence = runtime.lastSuccessfulWriteAtMs != null
            )
            copy(
                controllerModel = model,
                status = restoredStatus,
                isConnected = connected,
                isKeepaliveRunning = liveMonitor,
                isFccEnabled = runtime.lastSuccessfulWriteAtMs != null,
                fccLastWriteAtMs = runtime.lastSuccessfulWriteAtMs,
                message = when {
                    liveMonitor -> "Waiting for current Home Point..."
                    runtime.keepaliveStatus == KeepaliveRuntimeStatus.FAILED ->
                        runtime.error ?: "Auto FCC failed. Start the preferred mode again."
                    runtime.lastSuccessfulWriteAtMs != null ->
                        "FCC request was written — verify RF mode in DJI Fly"
                    else -> ""
                }
            )
        }
        log("SkylabFCCfree v$APP_VERSION started on $model")

        if (prefs.getBoolean("lan_log_enabled", true)) {
            setLanLoggingEnabled(true)
        }

        if (liveMonitor) {
            log("Existing Auto FCC run retained")
        }

        checkForUpdates()
    }

    // --- LAN logging ---

    fun setLanLoggingEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("lan_log_enabled", enabled).apply()
        if (!enabled) {
            networkLogServer.close()
            update { copy(isLanLogStarting = false, lanLogUrl = "", lanLogMessage = "LAN control bridge stopped") }
            log("LAN control bridge stopped")
            return
        }
        if (_state.value.isLanLogStarting) return

        update { copy(isLanLogStarting = true, lanLogMessage = "Finding the private Wi-Fi address...") }
        runOnIO {
            try {
                val endpoint = networkLogServer.start()
                update {
                    copy(
                        isLanLogStarting = false,
                        lanLogUrl = endpoint.url,
                        lanLogMessage = "Ready on ${endpoint.address}:${endpoint.port}"
                    )
                }
                log("LAN control bridge ready at ${endpoint.address}:${endpoint.port} (password hidden from log)")
            } catch (e: Exception) {
                networkLogServer.close()
                val reason = e.message ?: e.javaClass.simpleName
                update { copy(isLanLogStarting = false, lanLogUrl = "", lanLogMessage = "LAN control bridge failed: $reason") }
                log("LAN control bridge failed: $reason")
            }
        }
    }

    /** Rebinds the process-wide bridge when the controller's Wi-Fi address changed. */
    fun refreshLanBridgeBinding() {
        activeLanController = this
        if (!prefs.getBoolean("lan_log_enabled", true) || _state.value.isLanLogStarting) return
        val current = networkLogServer.currentEndpoint()
        if (current != null) {
            update {
                copy(
                    lanLogUrl = current.url,
                    lanLogMessage = "Ready on ${current.address}:${current.port}"
                )
            }
            return
        }
        setLanLoggingEnabled(true)
    }

    // --- Connection ---

    /**
     * Connects to the DUML proxy, auto-detecting the correct port.
     * Probes for the aircraft serial number after connecting.
     */
    fun connect(
        launchFlightAppAfterConnect: Boolean = false,
        autoMode: AutoFccMode = AutoFccMode.HOME_POINT_TEXT
    ): Boolean {
        val hardwareLease = beginHardwareOp()
        if (hardwareLease == null) {
            log("Hardware busy — please wait for the current operation to finish.")
            return false
        }
        update { copy(status = "connecting", message = "Connecting to controller...") }
        log("Connecting to controller...")

        runOnIO {
            var connected = false
            var portLease: Port40007Lock.Lease? = null
            try {
                portLease = Port40007Lock.acquireForLed()
                if (portLease == null) {
                    update {
                        copy(
                            status = "disconnected",
                            message = "DUML port busy — try Connect again.",
                            isConnected = false
                        )
                    }
                    log("Connection probe could not reserve controller port 40007")
                    return@runOnIO
                }
                if (transport.connect()) {
                    connected = true
                    val detectedPort = transport.getDetectedPort()
                    FccRuntime.tracker.beginHardwareSession(
                        detectedPort.takeIf { it > 0 } ?: DumlTransport.PORT
                    )
                    log("Controller connected")
                    if (detectedPort > 0) {
                        log("DUML port detected: $detectedPort")
                    }
                    val serialSessionLease = DumlPortSessionLock.tryBegin(DumlTransport.PORT_LED)
                    val serial = if (serialSessionLease != null) {
                        try {
                            transport.probeSerial(timeoutMs = 2_500, port = DumlTransport.PORT_LED)
                        } finally {
                            serialSessionLease.close()
                        }
                    } else {
                        log("Aircraft S/N probe skipped — port 40007 is busy")
                        ""
                    }
                    if (serial.isNotEmpty()) {
                        aircraftIdentityVerified = true
                        prefs.edit().putString("aircraft_serial", serial).apply()
                    } else {
                        aircraftIdentityVerified = false
                    }
                    ledOperationBusy.set(true)
                    update { copy(isLedBusy = true, ledStatus = "Reading LED state after Connect...") }
                    try {
                        applyLedReadback(
                            readLedState(DumlTransport()),
                            "LED state unavailable after Connect"
                        )
                    } finally {
                        ledOperationBusy.set(false)
                        update { copy(isLedBusy = false) }
                    }
                    update {
                        copy(
                            status = "connected",
                            message = if (serial.isNotEmpty()) {
                                "Connected — $serial. Starting Auto FCC..."
                            } else {
                                "Connected — starting Auto FCC..."
                            },
                            isConnected = true,
                            aircraftSerial = serial
                        )
                    }
                    if (serial.isNotEmpty()) log("Aircraft serial: $serial")
                } else {
                    FccRuntime.tracker.controllerSessionLost()
                    update {
                        copy(
                            status = "disconnected",
                            message = "Controller not found. Make sure the drone is powered on and linked.",
                            isConnected = false
                        )
                    }
                    log("Connection failed — is the drone powered on?")
                }
            } finally {
                portLease?.let(Port40007Lock::releaseFromLed)
                hardwareLease.close()
            }
            if (connected) {
                if (startKeepalive(autoMode)) {
                    log(
                        if (autoMode == AutoFccMode.HOME_POINT_TEXT) {
                            "Connect completed — waiting for DJI Fly Home Point text"
                        } else {
                            "Connect completed — periodic FCC mode started (5s)"
                        }
                    )
                    if (launchFlightAppAfterConnect) {
                        launchDjiFly()
                    }
                }
            }
        }
        return true
    }

    // --- FCC ---

    /**
     * Sends the 21-frame FCC unlock profile using the timing from fcc.json.
     * The profile already runs 2 rounds internally for reliability.
     */
    fun enableFcc(): Boolean {
        val hardwareLease = beginHardwareOp()
        if (hardwareLease == null) {
            log("Hardware busy — please wait for the current operation to finish.")
            return false
        }
        val runtime = FccRuntime.tracker.state.value
        val effectivePort = runtime.controllerPort
            ?: transport.getDetectedPort().takeIf { it > 0 }
            ?: DumlTransport.PORT
        val sessionLease = DumlPortSessionLock.tryBegin(effectivePort)
        if (sessionLease == null) {
            hardwareLease.close()
            update { copy(message = "DUML port $effectivePort busy — wait for the current session to finish.") }
            log("DUML port $effectivePort busy — wait for Auto FCC or diagnostics to finish.")
            return false
        }
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Sending FCC request...") }
        log("Sending FCC request...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "fcc.json")
                val expectedWrites = profile.frames.size * profile.rounds
                val attempt = FccRuntime.tracker.beginApply(
                    origin = FccApplyOrigin.MANUAL,
                    port = effectivePort,
                    expectedWrites = expectedWrites
                )
                log("Loaded FCC profile: ${profile.frames.size} frames, ${profile.rounds} rounds, port $effectivePort")

                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = effectivePort
                ) { progress -> update { copy(busyProgress = progress) } }

                FccRuntime.tracker.finishApply(
                    startedAtMs = attempt.startedAtMs,
                    flushedWrites = if (success) expectedWrites else 0,
                    matchingAcks = null,
                    outcome = if (success) {
                        FccApplyOutcome.ALL_WRITES_FLUSHED
                    } else {
                        FccApplyOutcome.PARTIAL_WRITE_FAILURE
                    }
                )

                if (success) {
                    update {
                        copy(
                            status = "fcc_written",
                            message = "FCC request written — RF mode unknown; verify in DJI Fly",
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log("FCC apply: all ${profile.frames.size * profile.rounds} frame writes completed; verify in DJI Fly")
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = "FCC apply failed — RC link unreachable. Make sure the drone is on and linked.",
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log("FCC apply failed — writes failed")
                }
            } catch (e: Exception) {
                FccRuntime.tracker.state.value.lastApplyAttempt
                    ?.takeIf { it.origin == FccApplyOrigin.MANUAL && it.finishedAtMs == null }
                    ?.let { attempt ->
                        FccRuntime.tracker.finishApply(
                            startedAtMs = attempt.startedAtMs,
                            flushedWrites = attempt.flushedWrites,
                            matchingAcks = attempt.matchingAcks,
                            outcome = FccApplyOutcome.ERROR
                        )
                    }
                log("FCC apply error: ${e.message}")
                update { copy(status = "connected", message = "FCC apply error: ${e.message}", isBusy = false, busyProgress = 0f) }
            } finally {
                sessionLease.close()
                hardwareLease.close()
            }
        }
        return true
    }

    /** Sends the experimental CE/default-region request from ce_restore.json. */
    fun disableFcc(): Boolean {
        val hardwareLease = beginHardwareOp()
        if (hardwareLease == null) {
            log("Hardware busy — please wait for the current operation to finish.")
            return false
        }
        // Stop an in-progress Home Point wait before the explicit CE restore.
        try {
            if (_state.value.isKeepaliveRunning || FccKeepaliveService.isRunningFlagSet(app)) {
                stopKeepalive()
            }
        } catch (e: Exception) {
            hardwareLease.close()
            update { copy(message = "Could not stop Auto FCC: ${e.message}") }
            log("CE restore aborted — Auto FCC stop failed: ${e.message}")
            return false
        }
        val runtimePort = FccRuntime.tracker.state.value.controllerPort
            ?: transport.getDetectedPort().takeIf { it > 0 }
            ?: DumlTransport.PORT
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = "Sending CE restore request...") }
        log("Sending CE restore request...")

        runOnIO {
            var sessionLease: DumlPortSessionLock.Lease? = null
            try {
                val deadline = System.nanoTime() + 3_000_000_000L
                while (sessionLease == null && System.nanoTime() < deadline) {
                    sessionLease = DumlPortSessionLock.tryBegin(runtimePort)
                    if (sessionLease == null) delay(50)
                }
                if (sessionLease == null) {
                    update {
                        copy(
                            status = "connected",
                            message = "CE restore could not acquire DUML port $runtimePort",
                            isBusy = false
                        )
                    }
                    log("CE restore failed — DUML port $runtimePort remained busy")
                    return@runOnIO
                }
                val profile = Profiles.load(app, "ce_restore.json")
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    readWindowMs = profile.readWindowMs,
                    port = runtimePort
                )

                if (success) {
                    FccRuntime.tracker.clearWriteEvidence()
                    update {
                        copy(
                            status = "connected",
                            message = "CE restore command sent — verify the region in DJI Fly",
                            isFccEnabled = false,
                            isBusy = false
                        )
                    }
                    log("CE restore command written — result is not readable; verify in DJI Fly")
                } else {
                    update { copy(status = "connected", message = "CE restore failed — RC link unreachable", isBusy = false) }
                    log("CE restore failed")
                }
            } catch (e: Exception) {
                log("CE restore error: ${e.message}")
                update { copy(status = "connected", message = "CE restore error: ${e.message}", isBusy = false) }
            } finally {
                sessionLease?.close()
                hardwareLease.close()
            }
        }
        return true
    }

    // --- Auto FCC foreground modes ---

    /**
     * Starts either the one-shot DJI Fly text mode or the legacy periodic mode.
     */
    fun startKeepalive(mode: AutoFccMode = AutoFccMode.HOME_POINT_TEXT): Boolean {
        if (!_state.value.isConnected) {
            log("Connect to the controller before starting Auto FCC")
            update { copy(status = "disconnected", message = "Connect to the controller first.") }
            return false
        }
        if (_state.value.isKeepaliveRunning) {
            log("Auto FCC already running")
            return true
        }
        return try {
            FccKeepaliveService.start(app, mode)
            log(
                if (mode == AutoFccMode.HOME_POINT_TEXT) {
                    "Started Auto FCC — waiting for DJI Fly Home Point text"
                } else {
                    "Started Auto FCC — full profile then legacy keepalive every 5s"
                }
            )
            true
        } catch (e: Exception) {
            log("Could not start Auto FCC: ${e.message}")
            update {
                copy(
                    status = "monitor_failed",
                    message = e.message ?: "Could not start Auto FCC."
                )
            }
            false
        }
    }

    /** Stops either foreground Auto FCC mode. */
    fun stopKeepalive() {
        FccKeepaliveService.stop(app)
        log("Auto FCC stopped")
    }

    // --- Launch DJI Fly ---

    /**
     * Launches DJI Fly on consumer controllers, with DJI Go 4 and DJI Pilot 2
     * fallbacks for older/enterprise controllers. Auto FCC can continue in the
     * background while the flight app runs.
     */
    fun launchDjiFly(): Boolean {
        val pm = app.packageManager
        // Try the standard launch intent first
        var intent = pm.getLaunchIntentForPackage("dji.go.v5")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log("Launched DJI Fly")
                return true
            } catch (_: Exception) {}
        }

        // Fallback: try explicit component — DJI Fly's main activity
        for (activityName in listOf(
            "dji.pilot2.lite.LauncherActivity",
            "dji.go.v5.MainActivity",
            "dji.pilot2.lite.LiteLauncherActivity",
            "dji.go.v5.SplashActivity"
        )) {
            val explicitIntent = android.content.Intent().apply {
                component = android.content.ComponentName("dji.go.v5", activityName)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                app.startActivity(explicitIntent)
                log("Launched DJI Fly")
                return true
            } catch (_: Exception) {}
        }

        // Fallback 2: try dji.go.v4
        intent = pm.getLaunchIntentForPackage("dji.go.v4")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log("Launched DJI Go 4")
                return true
            } catch (_: Exception) {}
        }

        // Fallback 3: DJI Pilot 2 on RC Plus / enterprise controllers.
        intent = pm.getLaunchIntentForPackage("com.dji.industry.pilot")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log("Launched DJI Pilot 2")
                return true
            } catch (_: Exception) {}
        }

        log("DJI Fly, DJI Go 4, or DJI Pilot 2 is not installed or cannot launch")
        return false
    }

    // --- 4G ---

    /**
     * Sends the 128-frame 4G activation profile.
     * The aircraft serial is embedded in each frame's payload at runtime.
     * 4G frames are sent via Unix domain socket (/duss/mb/0x205), not TCP.
     *
     * The socket does not respond, so this can only confirm the frames were
     * written — never confirm the aircraft actually activated 4G. There is
     * no "off" action: no send-only command exists to reliably deactivate it.
     *
     * Guards (added to stop the most common failure modes early):
     * 1. Identity must be either a full 1581... factory serial or a short
     *    W[AM]xxx model identity. Both are valid probeSerial() results.
     * 2. A short model identity must be in the 4G-capable set. A full serial
     *    does not expose the model code, so it proceeds to the socket check.
     * 3. The controller's 4G endpoint must be present (the abstract socket
     *    must be connectable). This does not identify external vs integrated
     *    cellular hardware; it only proves the DUSS route is exposed.
     */
    fun send4gActivationFrames(): Boolean {
        val hardwareLease = beginHardwareOp()
        if (hardwareLease == null) {
            log("Hardware busy — please wait for the current operation to finish.")
            return false
        }
        update { copy(is4gBusy = true, busyProgress = 0f, fourGMessage = "") }
        log("Sending 4G activation frames...")

        runOnIO {
            try {
                val identity = parseFourGIdentity(getOrProbeSerial())
                if (identity == null) {
                    update {
                        copy(
                            is4gBusy = false,
                            fourGMessage = "No usable aircraft identity. Power on and link the drone, then refresh its S/N."
                        )
                    }
                    log("4G activation failed — probe did not return a full 1581... serial or WA/WM model identity")
                    return@runOnIO
                }

                val modelCode = identity.modelCode
                if (modelCode != null && !isSupportedFourGModel(modelCode)) {
                    update {
                        copy(is4gBusy = false, fourGMessage = "The captured 4G profile is not verified for $modelCode. Probe the endpoint and collect logs before testing activation.")
                    }
                    log("4G activation aborted — model $modelCode is not in the 4G-capable set $MODELS_WITH_4G")
                    return@runOnIO
                }
                if (modelCode == null) {
                    log("4G model guard: full factory S/N detected; model will be validated by endpoint availability")
                }

                // Guard 3: endpoint pre-check — fast-fail if the DUSS route does not exist.
                if (!transport.is4gEndpointReachable()) {
                    update {
                        copy(is4gBusy = false, fourGMessage = "4G DUSS endpoint /duss/mb/0x205 is not reachable for the current link.")
                    }
                    log("4G activation aborted — DUSS endpoint /duss/mb/0x205 is not connectable")
                    return@runOnIO
                }

                val profile = Profiles.load4g(app, identity.payloadSerial)
                log("Loaded 4G profile: ${profile.frames.size} frames (identity: ${identity.payloadSerial}, model: ${modelCode ?: "unknown"})")

                // 4G uses Unix domain socket, not TCP
                val success = transport.sendFramesUnix(
                    frames = profile.frames,
                    interFrameDelayMs = profile.interFrameDelay
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            is4gBusy = false,
                            busyProgress = 0f,
                            fourGMessage = "All activation frames written successfully — check 4G status on the aircraft."
                        )
                    }
                    log("4G activation: all ${profile.frames.size} frames written successfully via Unix socket")
                } else {
                    update { copy(is4gBusy = false, fourGMessage = "4G apply failed while writing to the DUSS endpoint") }
                    log("4G activation failed — at least one frame write failed on the Unix socket")
                }
            } catch (e: Exception) {
                log("4G activation error: ${e.message}")
                update { copy(is4gBusy = false, fourGMessage = "4G error: ${e.message}") }
            } finally {
                hardwareLease.close()
            }
        }
        return true
    }

    /** Checks only whether the controller exposes the local 4G DUSS endpoint. */
    fun probe4gEndpoint() {
        runOnIO {
            val reachable = transport.is4gEndpointReachable()
            val message = if (reachable) {
                "4G endpoint reachable — hardware type and activation compatibility are still unknown"
            } else {
                "4G endpoint not reachable for the current aircraft/controller state"
            }
            update { copy(fourGMessage = message) }
            log("4G endpoint probe: /duss/mb/0x205 ${if (reachable) "reachable" else "not reachable"}")
        }
    }

    // --- GPS ---

    /** Reads the aircraft master `gps_enable` parameter without changing it. */
    fun refreshGpsState(): Boolean {
        if (!gpsOperationBusy.compareAndSet(false, true)) {
            log("GPS busy — please wait.")
            return false
        }
        update {
            copy(
                isGpsBusy = true,
                gpsState = GpsState.UNKNOWN,
                gpsRawValue = null,
                gpsStatus = "Reading GPS state..."
            )
        }
        log("Reading GPS state...")

        runOnIO {
            try {
                var readback: GpsReadback? = null
                var attemptedRead = false

                for (attempt in 1..3) {
                    val portLease = Port40007Lock.acquireForLed()
                    if (portLease == null) {
                        log("GPS status attempt $attempt/3 failed to acquire port 40007")
                    } else {
                        try {
                            attemptedRead = true
                            readback = readGpsState(DumlTransport(), attempts = 1)
                        } finally {
                            Port40007Lock.releaseFromLed(portLease)
                        }
                    }

                    if (readback != null) break
                    if (attempt < 3) {
                        log("GPS status attempt $attempt/3 missing; reopening port")
                        delay(150)
                    }
                }

                applyGpsReadback(
                    readback,
                    if (attemptedRead) "GPS state unavailable" else "GPS port busy"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("GPS read error: ${e.message}")
                update {
                    copy(
                        gpsState = GpsState.UNKNOWN,
                        gpsRawValue = null,
                        gpsStatus = "State unavailable"
                    )
                }
            } finally {
                gpsOperationBusy.set(false)
                update { copy(isGpsBusy = false) }
            }
        }
        return true
    }

    private fun readGpsState(
        gpsTransport: DumlTransport,
        attempts: Int = 3
    ): GpsReadback? {
        repeat(attempts) { attempt ->
            val request = GpsControlProtocol.buildReadRequest()
            val exchange = gpsTransport.sendAndReceiveRaw(
                frame = request,
                wireFrame = Profiles.wrapFrame(request),
                readWindowMs = 2_500,
                port = DumlTransport.PORT_LED,
                autoDetectPort = false
            )
            GpsControlProtocol.parse(exchange.validatedPayload)?.let { return it }
            if (attempt < attempts - 1) {
                log("GPS readback missing; retrying")
                Thread.sleep(150)
            }
        }
        return null
    }

    private fun applyGpsReadback(readback: GpsReadback?, unavailableMessage: String) {
        if (readback == null) {
            val cached = loadPersistedGpsReadback()
            update {
                copy(
                    gpsState = cached?.first?.state ?: GpsState.UNKNOWN,
                    gpsRawValue = cached?.first?.rawValue,
                    gpsStatus = cached?.let {
                        persistedStatus("$unavailableMessage; last verified", gpsLabel(it.first), it.second)
                    } ?: unavailableMessage
                )
            }
            log("GPS state readback unavailable")
            return
        }

        val verifiedAtMs = System.currentTimeMillis()
        val label = gpsLabel(readback)
        persistGpsReadback(readback, verifiedAtMs)
        update {
            copy(
                gpsState = readback.state,
                gpsRawValue = readback.rawValue,
                gpsStatus = "Verified $label"
            )
        }
        log("GPS state read back: $label")
    }

    /** Writes an explicit master GPS state five times, then verifies on fresh leases. */
    fun setGps(enabled: Boolean): Boolean {
        if (!gpsOperationBusy.compareAndSet(false, true)) {
            log("GPS busy — please wait.")
            return false
        }
        val requestedLabel = if (enabled) "ON" else "OFF"
        update {
            copy(
                isGpsBusy = true,
                gpsState = GpsState.UNKNOWN,
                gpsRawValue = null,
                gpsStatus = "Turning GPS ${requestedLabel.lowercase(Locale.US)}..."
            )
        }
        log("Turning GPS ${requestedLabel.lowercase(Locale.US)}...")

        runOnIO {
            var portLease: Port40007Lock.Lease? = null
            var followUpRead = false
            try {
                portLease = Port40007Lock.acquireForLed()
                if (portLease == null) {
                    update { copy(gpsStatus = "GPS port busy") }
                    log("GPS command failed to acquire port 40007")
                    return@runOnIO
                }

                var anyWriteSucceeded = false

                for (attempt in 1..5) {
                    update { copy(gpsStatus = "GPS $requestedLabel attempt $attempt/5...") }
                    log("GPS $requestedLabel attempt $attempt/5")

                    val request = GpsControlProtocol.buildWriteRequest(enabled)
                    val writeSucceeded = DumlTransport().sendFrame(
                        frame = Profiles.wrapFrame(request),
                        readWindowMs = 150,
                        port = DumlTransport.PORT_LED
                    )
                    anyWriteSucceeded = anyWriteSucceeded || writeSucceeded
                    if (attempt < 5) {
                        log("GPS $requestedLabel write sent; repeating bounded command")
                        delay(100)
                    }
                }

                if (!anyWriteSucceeded) {
                    log("GPS $requestedLabel command transport failed")
                    update { copy(gpsStatus = "GPS command transport failed") }
                } else {
                    // Live rc520 evidence: the command can apply physically,
                    // while reads in the same port lease never return. Clear
                    // stale state now and refresh only after releasing 40007.
                    prefs.edit()
                        .remove(PREF_GPS_STATE)
                        .remove(PREF_GPS_RAW)
                        .remove(PREF_GPS_AT)
                        .apply()
                    update {
                        copy(
                            gpsState = GpsState.UNKNOWN,
                            gpsRawValue = null,
                            gpsStatus = "$requestedLabel commands sent; waiting for fresh status..."
                        )
                    }
                    followUpRead = true
                    log("GPS $requestedLabel commands sent; scheduling fresh-port status read")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("GPS error: ${e.message}")
                update {
                    copy(
                        gpsState = GpsState.UNKNOWN,
                        gpsRawValue = null,
                        gpsStatus = "Error: ${e.message}"
                    )
                }
            } finally {
                portLease?.let(Port40007Lock::releaseFromLed)
                gpsOperationBusy.set(false)
                update { copy(isGpsBusy = false) }
            }

            if (followUpRead) {
                delay(250)
                log("Starting GPS status refresh on a fresh port 40007 lease")
                refreshGpsState()
            }
        }
        return true
    }

    // --- LED ---

    /** Reads the current aircraft lamp parameter without changing it. */
    fun refreshLedState(): Boolean = refreshLedState(onWireAttemptFinished = null)

    private fun refreshLedState(
        onWireAttemptFinished: ((Boolean) -> Unit)?
    ): Boolean {
        if (!ledOperationBusy.compareAndSet(false, true)) {
            log("LED busy — please wait.")
            return false
        }
        update {
            copy(
                isLedBusy = true,
                ledState = LedState.UNKNOWN,
                ledRawValue = null,
                ledStatus = "Reading LED state..."
            )
        }
        log("Reading LED state...")

        runOnIO {
            var portLease: Port40007Lock.Lease? = null
            var wireAttempted = false
            try {
                portLease = Port40007Lock.acquireForLed()
                if (portLease == null) {
                    update { copy(ledStatus = "LED port busy") }
                    log("LED read failed to pause the Home Point listener")
                    return@runOnIO
                }
                wireAttempted = true
                applyLedReadback(
                    readLedState(DumlTransport()),
                    "LED state unavailable"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("LED read error: ${e.message}")
                update {
                    copy(
                        ledState = LedState.UNKNOWN,
                        ledRawValue = null,
                        ledStatus = "State unavailable"
                    )
                }
            } finally {
                portLease?.let(Port40007Lock::releaseFromLed)
                ledOperationBusy.set(false)
                update { copy(isLedBusy = false) }
                onWireAttemptFinished?.invoke(wireAttempted)
            }
        }
        return true
    }

    private fun readLedState(
        ledTransport: DumlTransport,
        attempts: Int = 3
    ): LedReadback? {
        repeat(attempts) { attempt ->
            val request = LedReadbackProtocol.buildRequest()
            val exchange = ledTransport.sendAndReceiveRaw(
                frame = request,
                wireFrame = Profiles.wrapFrame(request),
                readWindowMs = 2_500,
                port = DumlTransport.PORT_LED,
                autoDetectPort = false
            )
            LedReadbackProtocol.parse(exchange.validatedPayload)?.let { return it }
            if (attempt < attempts - 1) {
                log("LED readback missing; retrying")
                Thread.sleep(150)
            }
        }
        return null
    }

    private fun applyLedReadback(readback: LedReadback?, unavailableMessage: String) {
        if (readback == null) {
            val cached = loadPersistedLedReadback()
            update {
                copy(
                    ledState = cached?.first?.state ?: LedState.UNKNOWN,
                    ledRawValue = cached?.first?.rawValue,
                    ledStatus = cached?.let {
                        persistedStatus("$unavailableMessage; last verified", ledLabel(it.first), it.second)
                    } ?: unavailableMessage
                )
            }
            log("LED state readback unavailable")
            return
        }

        val verifiedAtMs = System.currentTimeMillis()
        val label = ledLabel(readback)
        persistLedReadback(readback, verifiedAtMs)
        update {
            copy(
                ledState = readback.state,
                ledRawValue = readback.rawValue,
                ledStatus = "Verified $label"
            )
        }
        log("LED state read back: $label")
    }

    /**
     * Turns the aircraft arm LEDs on or off.
     * Uses port 40007 (different from the standard 40009 DUML port).
     * Requires DJI Fly running with the aircraft connected.
     *
     * Sends the LED command in up to two verified cycles. Each cycle uses
     * 2 bursts of 5 writes (10 total), matching the reference app's pattern.
     *
     * **Does NOT hold HardwareLock.** LED uses a dedicated process-wide port
     * 40007 lease, so it cannot overlap the one-shot Home Point listener.
     *
     * @param on true for LED ON, false for LED OFF
     */
    fun setLed(on: Boolean): Boolean {
        if (!ledOperationBusy.compareAndSet(false, true)) {
            log("LED busy — please wait.")
            return false
        }
        update {
            copy(
                isLedBusy = true,
                ledState = LedState.UNKNOWN,
                ledRawValue = null,
                ledStatus = if (on) "Turning LEDs on..." else "Turning LEDs off..."
            )
        }
        log(if (on) "Turning LEDs on..." else "Turning LEDs off...")

        runOnIO {
            var portLease: Port40007Lock.Lease? = null
            try {
                portLease = Port40007Lock.acquireForLed()
                if (portLease == null) {
                    update { copy(ledStatus = "LED port busy") }
                    log("LED command failed to pause the Home Point listener")
                    return@runOnIO
                }
                val fileName = if (on) "led_on.json" else "led_off.json"
                val profile = Profiles.load(app, fileName)
                log("Loaded LED profile: ${profile.frames.size} frames (port ${profile.port})")

                // Separate transport instance — the LED command on port 40007
                // must not share state with the FCC transport on port 40009.
                val ledTransport = DumlTransport()

                val expectedState = if (on) LedState.ON else LedState.OFF
                val requestedLabel = if (on) "ON" else "OFF"
                var allWritesSucceeded = true
                var readback: LedReadback? = null

                // One manual press may perform two complete reference-pattern
                // command cycles. Live RC Pro 2 testing needed the second manual
                // press, so stop as soon as a matching readback confirms state.
                for (commandAttempt in 1..2) {
                    update { copy(ledStatus = "LED $requestedLabel attempt $commandAttempt/2...") }
                    log("LED $requestedLabel attempt $commandAttempt/2")

                    // 2 connection bursts × 5 writes each = 10 total sends.
                    for (burst in 0 until 2) {
                        if (burst > 0) delay(100)
                        val success = ledTransport.sendFrames(
                            frames = profile.frames,
                            rounds = 5,
                            interFrameDelayMs = 100,
                            interRoundDelayMs = 0,
                            readWindowMs = 100,
                            port = profile.port
                        )
                        if (!success) allWritesSucceeded = false
                    }

                    delay(200)
                    readback = readLedState(ledTransport, attempts = 1)
                    if (readback?.state == expectedState) {
                        log("LED $requestedLabel verified after attempt $commandAttempt/2")
                        break
                    }
                    if (commandAttempt < 2) {
                        log("LED $requestedLabel not verified; retrying command")
                        delay(250)
                    }
                }

                if (!allWritesSucceeded) {
                    log("LED command transport was incomplete; reading actual state anyway")
                } else {
                    log("LED $requestedLabel command cycle finished")
                }
                applyLedReadback(
                    readback,
                    if (allWritesSucceeded) {
                        if (on) "ON command sent; state unknown" else "OFF command sent; state unknown"
                    } else {
                        "Command transport incomplete; state unknown"
                    }
                )
                val finalReadback = readback
                if (finalReadback != null && finalReadback.state != expectedState) {
                    log("LED verification mismatch after 2 attempts: requested $requestedLabel, read ${finalReadback.state}")
                    update {
                        copy(ledStatus = "Mismatch after 2 attempts: requested $requestedLabel, read ${finalReadback.state.name}")
                    }
                }
            } catch (e: Exception) {
                log("LED error: ${e.message}")
                update {
                    copy(
                        ledState = LedState.UNKNOWN,
                        ledRawValue = null,
                        ledStatus = "Error: ${e.message}"
                    )
                }
            } finally {
                portLease?.let(Port40007Lock::releaseFromLed)
                ledOperationBusy.set(false)
                update { copy(isLedBusy = false) }
            }
        }
        return true
    }

    // --- Device Info ---

    /**
     * Queries the controller for hardware version, bootloader version, and
     * firmware version via the GENERAL VersionInquiry command
     * (cmd_set=0, cmd_id=1). Uses sendAndReceive to capture the response.
     */
    fun queryDeviceInfo(): Boolean {
        if (!isControllerReachable()) return false
        val hardwareLease = beginHardwareOp()
        if (hardwareLease == null) {
            log("Hardware busy — please wait for the current operation to finish.")
            return false
        }
        val effectivePort = FccRuntime.tracker.state.value.controllerPort
            ?: transport.getDetectedPort().takeIf { it > 0 }
            ?: DumlTransport.PORT
        val sessionLease = DumlPortSessionLock.tryBegin(effectivePort)
        if (sessionLease == null) {
            hardwareLease.close()
            log("Device info unavailable while DUML port $effectivePort is busy")
            update { copy(deviceInfo = "DUML port busy — try again after Home Point monitoring") }
            return false
        }

        update { copy(isQueryingInfo = true) }
        log("Querying device info...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "device_info.json")
                if (profile.frames.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = "device_info.json is empty") }
                    log("Device info: profile has no frames")
                    return@runOnIO
                }
                val frame = profile.frames.first()

                val response = transport.sendAndReceive(frame, profile.readWindowMs, effectivePort)

                if (response == null || response.isEmpty()) {
                    update { copy(isQueryingInfo = false, deviceInfo = "No response from controller") }
                    log("Device info: no response")
                    return@runOnIO
                }

                val info = formatVersionResponse(response)
                update { copy(isQueryingInfo = false, deviceInfo = info) }
                log("Device info received: ${response.size} bytes")
            } catch (e: Exception) {
                log("Device info error: ${e.message}")
                update { copy(isQueryingInfo = false, deviceInfo = "Error: ${e.message}") }
            } finally {
                sessionLease.close()
                hardwareLease.close()
            }
        }
        return true
    }

    fun probeSerial(): Boolean {
        val hardwareLease = beginHardwareOp()
        if (hardwareLease == null) {
            log("Hardware busy — please wait for the current operation to finish.")
            return false
        }
        log("Probing for aircraft serial...")
        runOnIO {
            var portLease: Port40007Lock.Lease? = null
            var sessionLease: DumlPortSessionLock.Lease? = null
            try {
                val effectivePort = DumlTransport.PORT_LED
                portLease = Port40007Lock.acquireForLed()
                if (portLease == null) {
                    log("Serial probe could not reserve port 40007")
                    return@runOnIO
                }
                sessionLease = DumlPortSessionLock.tryBegin(effectivePort)
                if (sessionLease == null) {
                    log("Serial probe skipped — DUML port $effectivePort is busy")
                    return@runOnIO
                }
                val serial = transport.probeSerial(2_500, effectivePort)
                if (serial.isNotEmpty()) {
                    aircraftIdentityVerified = true
                    update { copy(aircraftSerial = serial) }
                    prefs.edit().putString("aircraft_serial", serial).apply()
                    log("Aircraft serial: $serial (cached)")
                } else {
                    aircraftIdentityVerified = false
                    log("No serial detected — is the aircraft powered on?")
                }
            } finally {
                sessionLease?.close()
                portLease?.let(Port40007Lock::releaseFromLed)
                hardwareLease.close()
            }
        }
        return true
    }

    // --- Updates ---

    fun checkForUpdates(force: Boolean = false) {
        if (_state.value.isCheckingUpdate) return
        // Rate-limit: don't hit GitHub API more than once per hour.
        // Unauthenticated limit is 60 requests/hour per IP.
        // The timestamp is saved ONLY on success — a failed check does NOT
        // consume the rate-limit window, so the user can retry immediately.
        val lastCheck = prefs.getLong("last_update_check", 0)
        val now = System.currentTimeMillis()
        if (!force && now - lastCheck < 60 * 60 * 1000 && _state.value.updateChecked && _state.value.updateInfo != null) {
            return
        }
        update { copy(isCheckingUpdate = true) }
        log("Checking for updates...")

        runOnIO {
            val info = UpdateChecker.fetchLatest()
            if (info == null) {
                // Don't save lastCheck on failure — let the user retry immediately.
                update { copy(isCheckingUpdate = false, updateChecked = true) }
                log("Update check failed — no internet or GitHub unreachable. Tap Retry to try again.")
                return@runOnIO
            }

            // Save the timestamp only on success.
            prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply()

            val isNewer = info.isNewerThan(APP_VERSION)
            update {
                copy(
                    updateInfo = info,
                    isCheckingUpdate = false,
                    updateChecked = true,
                    updateAvailable = isNewer
                )
            }
            if (isNewer) {
                log("Update available: v${info.version}")
            } else {
                log("App is up to date (v$APP_VERSION)")
            }
        }
    }

    private var downloadedApk: java.io.File? = null

    /**
     * Checks if the app can install packages. If not, opens the system
     * Settings page for "Install unknown apps" so the user can grant it.
     * Returns true if permission is already granted (proceed with download),
     * false if we opened Settings (user needs to grant, then tap Download again).
     */
    fun ensureInstallPermission(): Boolean {
        val pm = app.packageManager
        if (!pm.canRequestPackageInstalls()) {
            log("Install permission needed — opening Settings. Grant 'Install unknown apps' for SkylabFCCfree, then tap Download again.")
            val settingsIntent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
            ).apply {
                data = android.net.Uri.parse("package:${app.packageName}")
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
            }
            try {
                app.startActivity(settingsIntent)
            } catch (_: Exception) {
                log("Settings page not available — you may need to install updates via SD card + FileManager.")
            }
            return false
        }
        return true
    }

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        if (_state.value.isDownloadingUpdate) return
        // Check install permission BEFORE downloading so the user can grant
        // it first, then come back and tap Download again.
        if (!ensureInstallPermission()) {
            return
        }
        update { copy(isDownloadingUpdate = true, updateDownloadProgress = 0f, isUpdateDownloaded = false) }
        log("Downloading update v${info.version}...")

        runOnIO {
            val file = UpdateChecker.downloadApk(app, info) { progress ->
                update { copy(updateDownloadProgress = progress) }
            }

            if (file == null) {
                update { copy(isDownloadingUpdate = false, updateDownloadProgress = 0f) }
                log("Update download failed — check your Wi-Fi connection. The RC2 needs Wi-Fi to download updates.")
                return@runOnIO
            }

            downloadedApk = file
            update { copy(isDownloadingUpdate = false, updateDownloadProgress = 1f, isUpdateDownloaded = true) }
            log("Update downloaded — tap Install to apply")
        }
    }

    /** Re-downloads the update after a failed install. Resets the downloaded state first. */
    fun reDownloadUpdate() {
        if (_state.value.isDownloadingUpdate) return
        downloadedApk = null
        update { copy(isUpdateDownloaded = false) }
        downloadUpdate()
    }

    fun installUpdate() {
        val file = downloadedApk ?: run {
            log("No downloaded APK found — download first")
            return
        }
        if (!file.exists()) {
            log("Downloaded APK file missing — download again")
            downloadedApk = null
            update { copy(isUpdateDownloaded = false) }
            return
        }
        update { copy(isBusy = true, message = "Preparing install...") }
        runOnIO {
            try {
                val pm = app.packageManager

                // Check 1: does this app have permission to install packages?
                // On Android 8+ the user must grant "Install unknown apps"
                // per-app. The RC2 may hide this Settings page — if so, the
                // user needs to install via SD card + FileManager instead.
                if (!pm.canRequestPackageInstalls()) {
                    log("Install blocked — SkylabFCCfree needs 'Install unknown apps' permission.")
                    log("Opening Settings to grant it. If the Settings page doesn't appear,")
                    log("install the update via SD card + FileManager instead.")
                    val settingsIntent = android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES
                    ).apply {
                        data = android.net.Uri.parse("package:${app.packageName}")
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    try {
                        app.startActivity(settingsIntent)
                    } catch (_: Exception) {
                        log("Settings page unavailable — install via SD card + FileManager.")
                    }
                    update { copy(isBusy = false, message = "Grant install permission in Settings, then tap Install again. Or install via SD card.") }
                    return@runOnIO
                }

                // Copy the APK to a location the installer can access.
                // The RC2's package installer may not handle content:// URIs
                // from app-private cacheDir (a known Android issue). Copying
                // to the app-specific external directory is more reliable.
                val extFile = app.getExternalFilesDir(null)?.let { extBase ->
                    val extDir = java.io.File(extBase, "updates").apply { mkdirs() }
                    java.io.File(extDir, "freefcc_update.apk")
                }
                val stagedFile = if (extFile != null) {
                    try {
                        file.copyTo(extFile, overwrite = true)
                        extFile
                    } catch (e: Exception) {
                        log("Could not copy APK to external storage: ${e.message}")
                        null
                    }
                } else {
                    null
                }
                val installFile = stagedFile ?: file

                val uri = androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", installFile
                )
                val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                }

                // Check 2: does a package installer actually exist?
                if (viewIntent.resolveActivity(pm) == null) {
                    log("No package installer found. Sideload 01_PackageInstaller from the SD card, reboot, then retry.")
                    update { copy(isBusy = false, message = "No installer. Sideload 01_PackageInstaller from SD card, reboot, retry.") }
                    return@runOnIO
                }

                // Grant URI permission to all resolved installer activities —
                // some OEM forks don't honor FLAG_GRANT_READ_URI_PERMISSION
                // alone for the staging step.
                val targets = pm.queryIntentActivities(viewIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                for (info in targets) {
                    app.grantUriPermission(
                        info.activityInfo.packageName, uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }

                try {
                    app.startActivity(viewIntent)
                    log("Launching installer...")
                    update { copy(isBusy = false, message = "Installer launched — follow the on-screen prompts.") }
                } catch (e: android.content.ActivityNotFoundException) {
                    log("No package installer on this device. Install via SD card + FileManager.")
                    update { copy(isBusy = false, message = "No installer. Install via SD card + FileManager.") }
                } catch (e: Exception) {
                    log("Install failed: ${e.message}")
                    update { copy(isBusy = false, message = "Install failed: ${e.message}") }
                }
            } catch (e: Exception) {
                log("Install error: ${e.message}")
                update { copy(isBusy = false, message = "Install error: ${e.message}") }
            }
        }
    }

    // --- LAN control API ---

    private fun lanStatusJson(): String {
        val current = _state.value
        val runtime = FccRuntime.tracker.state.value
        return LanJson.objectOf(
            "ok" to true,
            "app_version" to APP_VERSION,
            "controller_model" to current.controllerModel,
            "status" to current.status,
            "message" to current.message,
            "connected" to current.isConnected,
            // The proxy exposes no physical RF-region readback. Keep the old
            // field present but unknown, and publish the actual evidence under
            // an explicit name.
            "fcc_enabled" to null,
            "fcc_sequence_written" to current.isFccEnabled,
            "fcc_write_state" to if (current.isFccEnabled) "written_current_process" else "unknown",
            "fcc_last_write_at_ms" to current.fccLastWriteAtMs,
            "fcc_last_attempt_succeeded" to runtime.lastAttemptSucceeded,
            "controller_duml_port" to runtime.controllerPort,
            "fcc_last_attempt_origin" to runtime.lastApplyAttempt?.origin?.name?.lowercase(Locale.US),
            "fcc_last_attempt_outcome" to runtime.lastApplyAttempt?.outcome?.name?.lowercase(Locale.US),
            "fcc_last_attempt_port" to runtime.lastApplyAttempt?.port,
            "fcc_last_attempt_expected_writes" to runtime.lastApplyAttempt?.expectedWrites,
            "fcc_last_attempt_flushed_writes" to runtime.lastApplyAttempt?.flushedWrites,
            "fcc_last_attempt_matching_acks" to runtime.lastApplyAttempt?.matchingAcks,
            "fcc_last_attempt_started_at_ms" to runtime.lastApplyAttempt?.startedAtMs,
            "fcc_last_attempt_finished_at_ms" to runtime.lastApplyAttempt?.finishedAtMs,
            "fcc_auto_home_point_observed_at_ms" to runtime.lastAutomaticApplyAttempt?.homePointObservedAtMs,
            "fcc_auto_attempt_outcome" to runtime.lastAutomaticApplyAttempt?.outcome?.name?.lowercase(Locale.US),
            "fcc_auto_attempt_port" to runtime.lastAutomaticApplyAttempt?.port,
            "fcc_auto_attempt_flushed_writes" to runtime.lastAutomaticApplyAttempt?.flushedWrites,
            "fcc_auto_attempt_expected_writes" to runtime.lastAutomaticApplyAttempt?.expectedWrites,
            "fcc_auto_attempt_matching_acks" to runtime.lastAutomaticApplyAttempt?.matchingAcks,
            "auto_fcc" to false,
            "keepalive_running" to current.isKeepaliveRunning,
            "keepalive_status" to runtime.keepaliveStatus.name.lowercase(Locale.US),
            "keepalive_requested" to FccKeepaliveService.isRunningFlagSet(app),
            "home_point_monitor_running" to current.isKeepaliveRunning,
            "home_point_monitor_requested" to FccKeepaliveService.isRunningFlagSet(app),
            "home_point_monitor_status" to runtime.keepaliveStatus.name.lowercase(Locale.US),
            "home_point_monitor_error" to runtime.error,
            "hardware_busy" to current.isHardwareBusy,
            "led_busy" to current.isLedBusy,
            "led_state" to current.ledState.name.lowercase(Locale.US),
            "led_value" to current.ledRawValue,
            "led_status" to current.ledStatus,
            "gps_busy" to current.isGpsBusy,
            "gps_state" to current.gpsState.name.lowercase(Locale.US),
            "gps_value" to current.gpsRawValue,
            "gps_status" to current.gpsStatus,
            "aircraft_serial" to current.aircraftSerial,
            "device_info" to current.deviceInfo,
            "four_g_message" to current.fourGMessage,
            "update_available" to current.updateAvailable,
            "update_downloading" to current.isDownloadingUpdate,
            "update_progress" to current.updateDownloadProgress
        )
    }

    private fun handleLanCommand(params: Map<String, String>): NetworkApiResponse {
        val command = params["command"]?.trim()?.lowercase(Locale.US)
            ?: return lanError(400, "missing_command")

        if (command == "help") {
            return NetworkApiResponse(
                200,
                LanJson.objectOf(
                    "ok" to true,
                    "commands" to LAN_COMMANDS,
                    "raw_duml_parameters" to listOf(
                        "sender (default 0x2a)",
                        "dst",
                        "cmd_set",
                        "cmd_id",
                        "cmd_type (default 0x40)",
                        "payload (hex)",
                        "port (default 40009)",
                        "timeout_ms (duml_request)",
                        "wrapper=true|false (duml_send only)",
                        "duration_ms=100..10000 (duml_capture)",
                        "max_frames=1..128 (duml_capture)",
                        "wire_hex, duration_ms, max_bytes (wire_exchange)"
                    )
                )
            )
        }

        log("LAN command received: $command")
        return try {
            when (command) {
                "ping" -> NetworkApiResponse(
                    200,
                    LanJson.objectOf("ok" to true, "command" to command, "reply" to "pong")
                )
                "connect" -> acceptedHardware(command) { connect() }
                "fcc_enable" -> acceptedHardware(command, requireConnected = true) { enableFcc() }
                "ce_restore" -> acceptedHardware(command, requireConnected = true) { disableFcc() }
                "keepalive_start" -> if (!_state.value.isConnected) {
                    lanError(412, "controller_not_connected")
                } else {
                    acceptedBoolean(command, startKeepalive(), "monitor_start_failed")
                }
                "keepalive_stop" -> accepted(command) { stopKeepalive() }
                "home_point_wait_start" -> if (!_state.value.isConnected) {
                    lanError(412, "controller_not_connected")
                } else {
                    acceptedBoolean(command, startKeepalive(), "monitor_start_failed")
                }
                "home_point_wait_stop" -> accepted(command) { stopKeepalive() }
                "led_read" -> acceptedBoolean(command, refreshLedState(), "led_busy")
                "led_on" -> acceptedBoolean(command, setLed(true), "led_busy")
                "led_off" -> acceptedBoolean(command, setLed(false), "led_busy")
                "gps_read" -> acceptedBoolean(command, refreshGpsState(), "gps_busy")
                "gps_on" -> acceptedBoolean(command, setGps(true), "gps_busy")
                "gps_off" -> acceptedBoolean(command, setGps(false), "gps_busy")
                "device_info" -> acceptedHardware(command, requireConnected = true) { queryDeviceInfo() }
                "serial_probe" -> acceptedHardware(command, requireConnected = true) { probeSerial() }
                "four_g_probe" -> accepted(command) { probe4gEndpoint() }
                "four_g_activate" -> acceptedHardware(command, requireConnected = true) { send4gActivationFrames() }
                "update_check" -> accepted(command) { checkForUpdates(force = true) }
                "update_download" -> when {
                    _state.value.isDownloadingUpdate -> lanError(409, "update_download_busy")
                    _state.value.updateInfo == null -> lanError(412, "update_not_checked")
                    else -> accepted(command) { downloadUpdate() }
                }
                "launch_dji_fly" -> accepted(command) { launchDjiFly() }
                "duml_send" -> handleLanDuml(params, expectResponse = false)
                "duml_request" -> handleLanDuml(params, expectResponse = true)
                "duml_capture" -> handleLanDumlCapture(params)
                "wire_exchange" -> handleLanWireExchange(params)
                else -> lanError(400, "unknown_command")
            }
        } catch (e: IllegalArgumentException) {
            lanError(400, e.message ?: "invalid_parameters")
        }
    }

    private fun accepted(command: String, action: () -> Unit): NetworkApiResponse {
        action()
        return NetworkApiResponse(
            202,
            LanJson.objectOf("ok" to true, "command" to command, "state" to "accepted")
        )
    }

    private fun acceptedHardware(
        command: String,
        requireConnected: Boolean = false,
        action: () -> Boolean
    ): NetworkApiResponse {
        if (requireConnected && !_state.value.isConnected) {
            return lanError(412, "controller_not_connected")
        }
        return acceptedBoolean(command, action(), "hardware_busy")
    }

    private fun acceptedBoolean(command: String, accepted: Boolean, error: String): NetworkApiResponse =
        if (accepted) {
            NetworkApiResponse(
                202,
                LanJson.objectOf("ok" to true, "command" to command, "state" to "accepted")
            )
        } else {
            lanError(409, error)
        }

    private fun handleLanDuml(
        params: Map<String, String>,
        expectResponse: Boolean
    ): NetworkApiResponse {
        val sender = LanCommandCodec.optionalByte(params, "sender", 0x2A)
        val destination = LanCommandCodec.requiredByte(params, "dst")
        val cmdSet = LanCommandCodec.requiredByte(params, "cmd_set")
        val cmdId = LanCommandCodec.requiredByte(params, "cmd_id")
        val cmdType = LanCommandCodec.optionalByte(params, "cmd_type", 0x40)
        val payload = LanCommandCodec.optionalHex(params)
        val port = LanCommandCodec.optionalPort(params)
        val timeoutMs = LanCommandCodec.optionalTimeout(params)
        val wrapper = LanCommandCodec.optionalBoolean(params, "wrapper")
        require(!expectResponse || !wrapper) { "wrapped_response_not_supported" }

        val request = DumlBuilder().buildFrame(
            DumlFrame(
                sender = sender,
                cmdType = cmdType,
                cmdSet = cmdSet,
                cmdId = cmdId,
                dst = destination,
                payload = payload
            )
        )
        val wireRequest = if (wrapper) Profiles.wrapFrame(request) else request
        val requestHex = LanCommandCodec.bytesToHex(request)
        if (!lanDiagnosticBusy.compareAndSet(false, true)) {
            return lanError(409, "diagnostic_busy")
        }
        val writeLease = beginLanWrite()
        if (writeLease == null) {
            lanDiagnosticBusy.set(false)
            return lanError(409, "hardware_busy")
        }
        val portLease = if (port == DumlTransport.PORT_LED) {
            Port40007Lock.acquireForExternalBlocking()
        } else {
            null
        }
        if (port == DumlTransport.PORT_LED && portLease == null) {
            writeLease.close()
            lanDiagnosticBusy.set(false)
            return lanError(409, "port_40007_busy")
        }
        val sessionLease = DumlPortSessionLock.tryBegin(port)
        if (sessionLease == null) {
            portLease?.let(Port40007Lock::releaseFromLed)
            writeLease.close()
            lanDiagnosticBusy.set(false)
            return lanError(409, "duml_port_busy")
        }

        val commandLabel = "%02x:%02x".format(Locale.US, cmdSet, cmdId)
        log(
            "LAN DUML ${if (expectResponse) "request" else "send"} $commandLabel " +
                "dst=%02x port=$port payload=${LanCommandCodec.bytesToHex(payload)}"
                    .format(Locale.US, destination)
        )

        return try {
            if (!expectResponse) {
                val sent = transport.sendFrame(
                    frame = wireRequest,
                    readWindowMs = timeoutMs,
                    port = port,
                    onWriteFlushed = writeLease::close
                )
                if (sent) {
                    log("LAN DUML send $commandLabel completed")
                    NetworkApiResponse(
                        200,
                        LanJson.objectOf(
                            "ok" to true,
                            "command" to "duml_send",
                            "request_hex" to requestHex,
                            "wire_hex" to LanCommandCodec.bytesToHex(wireRequest),
                            "port" to port
                        )
                    )
                } else {
                    log("LAN DUML send $commandLabel failed")
                    lanError(502, "send_failed", requestHex)
                }
            } else {
                val startedAt = System.currentTimeMillis()
                val exchange = transport.sendAndReceiveRaw(
                    frame = request,
                    readWindowMs = timeoutMs,
                    port = port,
                    autoDetectPort = false,
                    onWriteFlushed = writeLease::close
                )
                val elapsedMs = System.currentTimeMillis() - startedAt
                val responseHex = exchange.responseFrame?.let(LanCommandCodec::bytesToHex)
                val responsePayload = exchange.validatedPayload
                val unmatchedHex = exchange.lastCompleteUnmatchedFrame
                    ?.let(LanCommandCodec::bytesToHex)
                val partialHex = exchange.partialTail?.let(LanCommandCodec::bytesToHex)

                when {
                    !exchange.writeCompleted -> {
                        log("LAN DUML request $commandLabel write failed at ${exchange.failureStage}")
                        NetworkApiResponse(
                            502,
                            LanJson.objectOf(
                                "ok" to false,
                                "command" to "duml_request",
                                "error" to "send_failed",
                                "failure_stage" to exchange.failureStage,
                                "request_hex" to requestHex,
                                "port" to port,
                                "elapsed_ms" to elapsedMs
                            )
                        )
                    }
                    exchange.matchedFrame == null &&
                        exchange.lastCompleteUnmatchedFrame == null &&
                        exchange.partialTail == null -> {
                        log("LAN DUML request $commandLabel timed out after ${elapsedMs}ms")
                        NetworkApiResponse(
                            504,
                            LanJson.objectOf(
                                "ok" to false,
                                "command" to "duml_request",
                                "error" to "no_response",
                                "request_hex" to requestHex,
                                "port" to port,
                                "elapsed_ms" to elapsedMs
                            )
                        )
                    }
                    exchange.matchedFrame == null || responsePayload == null -> {
                        log("LAN DUML request $commandLabel returned evidence but no matching response")
                        NetworkApiResponse(
                            502,
                            LanJson.objectOf(
                                "ok" to false,
                                "command" to "duml_request",
                                "error" to "no_matching_response",
                                "request_hex" to requestHex,
                                "response_hex" to responseHex,
                                "last_unmatched_hex" to unmatchedHex,
                                "partial_tail_hex" to partialHex,
                                "port" to port,
                                "elapsed_ms" to elapsedMs
                            )
                        )
                    }
                    else -> {
                        val payloadHex = LanCommandCodec.bytesToHex(responsePayload)
                        log("LAN DUML response $commandLabel payload=$payloadHex")
                        NetworkApiResponse(
                            200,
                            LanJson.objectOf(
                                "ok" to true,
                                "command" to "duml_request",
                                "request_hex" to requestHex,
                                "response_hex" to responseHex,
                                "payload_hex" to payloadHex,
                                "last_unmatched_hex" to unmatchedHex,
                                "partial_tail_hex" to partialHex,
                                "port" to port,
                                "elapsed_ms" to elapsedMs
                            )
                        )
                    }
                }
            }
        } finally {
            sessionLease.close()
            portLease?.let(Port40007Lock::releaseFromLed)
            writeLease.close()
            lanDiagnosticBusy.set(false)
        }
    }

    private fun handleLanDumlCapture(params: Map<String, String>): NetworkApiResponse {
        val port = LanCommandCodec.optionalPort(params)
        val durationMs = LanCommandCodec.optionalCaptureDuration(params)
        val maxFrames = LanCommandCodec.optionalCaptureMaxFrames(params)
        if (!lanDiagnosticBusy.compareAndSet(false, true)) {
            return lanError(409, "diagnostic_busy")
        }
        log("LAN DUML capture started: port=$port duration=${durationMs}ms max=$maxFrames")
        val portLease = if (port == DumlTransport.PORT_LED) {
            Port40007Lock.acquireForExternalBlocking()
        } else {
            null
        }
        if (port == DumlTransport.PORT_LED && portLease == null) {
            lanDiagnosticBusy.set(false)
            return lanError(409, "port_40007_busy")
        }
        val sessionLease = DumlPortSessionLock.tryBegin(port)
        if (sessionLease == null) {
            portLease?.let(Port40007Lock::releaseFromLed)
            lanDiagnosticBusy.set(false)
            return lanError(409, "duml_port_busy")
        }

        return try {
            val frames = transport.captureFrames(
                durationMs = durationMs,
                maxFrames = maxFrames,
                port = port
            )
            val decoded = frames.map { frame ->
                val totalLength = frame.size
                linkedMapOf<String, Any?>(
                    "hex" to LanCommandCodec.bytesToHex(frame),
                    "length" to totalLength,
                    "sender" to "0x%02x".format(Locale.US, frame[4].toInt() and 0xFF),
                    "dst" to "0x%02x".format(Locale.US, frame[5].toInt() and 0xFF),
                    "seq" to ((frame[6].toInt() and 0xFF) or ((frame[7].toInt() and 0xFF) shl 8)),
                    "cmd_type" to "0x%02x".format(Locale.US, frame[8].toInt() and 0xFF),
                    "cmd_set" to "0x%02x".format(Locale.US, frame[9].toInt() and 0xFF),
                    "cmd_id" to "0x%02x".format(Locale.US, frame[10].toInt() and 0xFF),
                    "payload_hex" to if (totalLength > 13) {
                        LanCommandCodec.bytesToHex(frame.copyOfRange(11, totalLength - 2))
                    } else {
                        ""
                    }
                )
            }
            log("LAN DUML capture completed: ${frames.size} frame(s)")
            NetworkApiResponse(
                200,
                LanJson.objectOf(
                    "ok" to true,
                    "command" to "duml_capture",
                    "port" to port,
                    "duration_ms" to durationMs,
                    "captured" to frames.size,
                    "frames" to decoded
                )
            )
        } finally {
            sessionLease.close()
            portLease?.let(Port40007Lock::releaseFromLed)
            lanDiagnosticBusy.set(false)
        }
    }

    private fun handleLanWireExchange(params: Map<String, String>): NetworkApiResponse {
        val wire = LanCommandCodec.requiredWireHex(params)
        val port = LanCommandCodec.optionalPort(params)
        val durationMs = LanCommandCodec.optionalCaptureDuration(params)
        val maxBytes = LanCommandCodec.optionalMaxBytes(params)
        if (!lanDiagnosticBusy.compareAndSet(false, true)) {
            return lanError(409, "diagnostic_busy")
        }
        val writeLease = beginLanWrite()
        if (writeLease == null) {
            lanDiagnosticBusy.set(false)
            return lanError(409, "hardware_busy")
        }
        val portLease = if (port == DumlTransport.PORT_LED) {
            Port40007Lock.acquireForExternalBlocking()
        } else {
            null
        }
        if (port == DumlTransport.PORT_LED && portLease == null) {
            writeLease.close()
            lanDiagnosticBusy.set(false)
            return lanError(409, "port_40007_busy")
        }
        val sessionLease = DumlPortSessionLock.tryBegin(port)
        if (sessionLease == null) {
            portLease?.let(Port40007Lock::releaseFromLed)
            writeLease.close()
            lanDiagnosticBusy.set(false)
            return lanError(409, "duml_port_busy")
        }

        log("LAN wire exchange started: port=$port tx=${wire.size}B window=${durationMs}ms max=$maxBytes")
        return try {
            val startedAt = System.currentTimeMillis()
            val exchange = transport.exchangeWire(
                wire = wire,
                durationMs = durationMs,
                maxBytes = maxBytes,
                port = port,
                onWriteFlushed = writeLease::close
            )
            val elapsedMs = System.currentTimeMillis() - startedAt
            if (!exchange.writeCompleted) {
                log("LAN wire exchange write failed at ${exchange.failureStage}")
                NetworkApiResponse(
                    502,
                    LanJson.objectOf(
                        "ok" to false,
                        "command" to "wire_exchange",
                        "error" to "send_failed",
                        "failure_stage" to exchange.failureStage,
                        "port" to port,
                        "request_hex" to LanCommandCodec.bytesToHex(wire),
                        "elapsed_ms" to elapsedMs
                    )
                )
            } else if (exchange.failureStage == "read") {
                log("LAN wire exchange read failed after ${exchange.responseBytes.size}B")
                NetworkApiResponse(
                    502,
                    LanJson.objectOf(
                        "ok" to false,
                        "command" to "wire_exchange",
                        "error" to "read_failed",
                        "failure_stage" to exchange.failureStage,
                        "termination" to exchange.termination?.name?.lowercase(Locale.US),
                        "truncated" to true,
                        "port" to port,
                        "request_hex" to LanCommandCodec.bytesToHex(wire),
                        "response_hex" to LanCommandCodec.bytesToHex(exchange.responseBytes),
                        "response_bytes" to exchange.responseBytes.size,
                        "elapsed_ms" to elapsedMs
                    )
                )
            } else {
                log("LAN wire exchange completed: rx=${exchange.responseBytes.size}B elapsed=${elapsedMs}ms")
                NetworkApiResponse(
                    200,
                    LanJson.objectOf(
                        "ok" to true,
                        "command" to "wire_exchange",
                        "port" to port,
                        "request_hex" to LanCommandCodec.bytesToHex(wire),
                        "response_hex" to LanCommandCodec.bytesToHex(exchange.responseBytes),
                        "response_bytes" to exchange.responseBytes.size,
                        "termination" to exchange.termination?.name?.lowercase(Locale.US),
                        "truncated" to (exchange.termination == WireReadTermination.MAX_BYTES),
                        "elapsed_ms" to elapsedMs
                    )
                )
            }
        } finally {
            sessionLease.close()
            portLease?.let(Port40007Lock::releaseFromLed)
            writeLease.close()
            lanDiagnosticBusy.set(false)
        }
    }

    private fun lanError(code: Int, error: String, requestHex: String? = null): NetworkApiResponse =
        NetworkApiResponse(
            code,
            if (requestHex == null) {
                LanJson.objectOf("ok" to false, "error" to error)
            } else {
                LanJson.objectOf("ok" to false, "error" to error, "request_hex" to requestHex)
            }
        )

    // --- Helpers ---

    /** Returns true if the controller is connected, logs a hint if not. */
    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected) return true
        log("Connect to the controller first")
        return false
    }

    /**
     * Returns an identity freshly verified in this process, probing when the
     * current value came only from persistent cache. A cached identity may
     * belong to a previously linked aircraft and must not be used blindly in
     * serial-specific 4G frames.
     */
    private suspend fun getOrProbeSerial(): String {
        val current = _state.value.aircraftSerial
        if (aircraftIdentityVerified && current.isNotEmpty()) return current

        log("Probing current aircraft identity for 4G...")
        val effectivePort = DumlTransport.PORT_LED
        val portLease = Port40007Lock.acquireForLed()
        if (portLease == null) {
            log("4G identity probe could not reserve port 40007")
            return ""
        }
        val sessionLease = DumlPortSessionLock.tryBegin(effectivePort)
        if (sessionLease == null) {
            log("4G identity probe skipped — DUML port $effectivePort is busy")
            Port40007Lock.releaseFromLed(portLease)
            return ""
        }
        val serial = try {
            transport.probeSerial(2_500, effectivePort)
        } finally {
            sessionLease.close()
            Port40007Lock.releaseFromLed(portLease)
        }
        if (serial.isNotEmpty()) {
            aircraftIdentityVerified = true
            update { copy(aircraftSerial = serial) }
            prefs.edit().putString("aircraft_serial", serial).apply()
            log("Aircraft identity: $serial (verified and cached)")
        }
        return serial
    }

    /**
     * Parses a DUML VersionInquiry response payload into a human-readable string.
     *
     * Response layout (from dji-firmware-tools DJIPayload_General_VersionInquiryRe):
     *   byte  0-1    unknown
     *   bytes 2-17   hardware version (16-char ASCII string)
     *   bytes 18-21  bootloader version (uint32 LE)
     *   bytes 22-25  firmware version (uint32 LE)
     */
    private fun formatVersionResponse(payload: ByteArray): String {
        val lines = mutableListOf<String>()

        if (payload.size >= 18) {
            val hwVersion = String(payload, 2, 16, Charsets.US_ASCII).trimEnd('\u0000')
            lines.add("Hardware: $hwVersion")
        }

        if (payload.size >= 22) {
            val ldrVersion = readUInt32LE(payload, 18)
            lines.add("Bootloader: ${formatVersion(ldrVersion)}")
        }

        if (payload.size >= 26) {
            val appVersion = readUInt32LE(payload, 22)
            lines.add("Firmware: ${formatVersion(appVersion)}")
        }

        lines.add("")
        lines.add("Raw payload (${payload.size} bytes):")
        lines.add(payload.joinToString(" ") { "%02x".format(it) })

        return lines.joinToString("\n")
    }

    /** Reads a 32-bit little-endian unsigned integer from a byte array. */
    private fun readUInt32LE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF)) or
               ((data[offset + 1].toLong() and 0xFF) shl 8) or
               ((data[offset + 2].toLong() and 0xFF) shl 16) or
               ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** Formats a DJI firmware version uint32 as major.minor.patch.build. */
    private fun formatVersion(version: Long): String {
        val major = (version shr 24) and 0xFF
        val minor = (version shr 16) and 0xFF
        val patch = (version shr 8) and 0xFF
        val build = version and 0xFF
        return "$major.$minor.$patch.$build"
    }

    /** Atomically updates the state via a copy() block. */
    private fun update(block: AppState.() -> AppState) {
        _state.update(block)
    }

    private fun refreshProcessLogs() {
        update { copy(logMessages = processLogSnapshot().asReversed()) }
    }

    /** Adds a timestamped entry to the activity log (most recent first, max 200). */
    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $message"
        appendProcessLog(entry)
        refreshProcessLogs()
    }

    /** Launches a coroutine on Dispatchers.IO for network operations. */
    private fun runOnIO(block: suspend () -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) { block() }

    override fun onCleared() {
        if (activeLanController === this) activeLanController = null
        super.onCleared()
    }

}
