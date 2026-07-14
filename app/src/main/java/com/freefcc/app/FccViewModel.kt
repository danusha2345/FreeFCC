package com.freefcc.app

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val isFccEnabled: Boolean = false,
    val is4gBusy: Boolean = false,
    val fourGMessage: String = "",
    val isBusy: Boolean = false,
    val isHardwareBusy: Boolean = false,
    val busyProgress: Float = 0f,
    val aircraftSerial: String = "",
    val controllerModel: String = "",
    val deviceInfo: String = "",
    val isQueryingInfo: Boolean = false,
    val autoFcc: Boolean = false,
    val isLedBusy: Boolean = false,
    val ledStatus: String = "",
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
    val isKeepaliveRunning: Boolean = false
)

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
        const val APP_VERSION = "1.4.6"
    }

    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()

    private val transport = DumlTransport()
    private val prefs = app.getSharedPreferences("freefcc", Context.MODE_PRIVATE)

    init {
        // MainActivity.onCreate() calls init() below on every Activity re-creation
        // (e.g. config change), but this class init{} runs exactly once per
        // ViewModel instance — the collector must live here, not in init().
        viewModelScope.launch {
            HardwareLock.busy.collect { busy -> update { copy(isHardwareBusy = busy) } }
        }
    }

    /** Claims the shared hardware lock for one operation. Returns false if another (including the keepalive service) is already running. */
    private fun beginHardwareOp(): Boolean = HardwareLock.tryBegin()

    /** Releases the shared hardware lock. Must run in a finally block covering every exit path. */
    private fun endHardwareOp() = HardwareLock.end()

    fun init() {
        val model = try { Build.DEVICE } catch (_: Exception) { "unknown" }
        val autoEnabled = prefs.getBoolean("auto_fcc", false)
        // Sync the keepalive toggle with the persistent flag so the UI is
        // correct after a process restart (e.g. low-memory kill + sticky restart).
        val keepaliveRunning = FccKeepaliveService.isRunningFlagSet(app)
        update { copy(controllerModel = model, status = "disconnected", autoFcc = autoEnabled, isKeepaliveRunning = keepaliveRunning) }

        if (autoEnabled) {
            log("Auto-FCC enabled — connecting and applying...")
            autoConnectAndApply()
        }

        checkForUpdates()
    }

    // --- Auto-FCC ---

    /**
     * Toggles auto-FCC on or off. When enabled, the app will automatically
     * connect to the controller and apply FCC mode every time it launches.
     * The setting is saved to SharedPreferences and persists across restarts.
     */
    fun toggleAutoFcc() {
        val newValue = !_state.value.autoFcc
        prefs.edit().putBoolean("auto_fcc", newValue).apply()
        update { copy(autoFcc = newValue) }
        log(if (newValue) "Auto-FCC enabled — will auto-connect on next launch" else "Auto-FCC disabled")
    }

    /**
     * Connects to the controller and applies FCC mode automatically.
     * Waits for connection, then sends the FCC profile, starts the keepalive
     * service, and launches DJI Fly.
     */
    private fun autoConnectAndApply() {
        if (!beginHardwareOp()) {
            log("Auto-FCC skipped — another hardware operation is already running")
            return
        }
        runOnIO {
            try {
                // Wait a moment for the UI to render
                delay(1000)

                // Try to connect — scans all known ports
                update { copy(status = "connecting", message = "Auto-connecting...") }
                if (!transport.connect()) {
                    log("Auto-FCC: controller not found — is the drone powered on?")
                    update { copy(status = "disconnected", message = "Controller not found. Auto-FCC will retry when you tap Connect.") }
                    return@runOnIO
                }

                log("Auto-FCC: controller connected")
                val detectedPort = transport.getDetectedPort()
                if (detectedPort > 0) {
                    log("DUML port detected: $detectedPort")
                }
                val serial = transport.probeSerial(1500)
                update {
                    copy(
                        status = "connected",
                        isConnected = true,
                        aircraftSerial = serial,
                        message = "Connected. Auto-applying FCC..."
                    )
                }
                if (serial.isNotEmpty()) log("Aircraft serial: $serial")

                // Apply FCC
                delay(500)
                update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Applying FCC mode...") }
                log("Auto-FCC: applying FCC mode...")

                val profile = Profiles.load(app, "fcc.json")
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = profile.port
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            status = "fcc_enabled",
                            message = "FCC enabled. Starting keepalive...",
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log("Auto-FCC: FCC mode enabled")

                    // Auto-start keepalive
                    delay(500)
                    update { copy(isKeepaliveRunning = true) }
                    FccKeepaliveService.start(app)
                    log("Auto-FCC: keepalive started (re-applying every 2s)")

                    // Auto-launch DJI Fly
                    delay(500)
                    update { copy(message = "FCC active. Launching DJI Fly...") }
                    log("Auto-FCC: launching DJI Fly")
                    launchDjiFly()
                } else {
                    update {
                        copy(
                            status = "connected",
                            message = "Auto-FCC failed — try manually",
                            isBusy = false,
                            busyProgress = 0f
                        )
                    }
                    log("Auto-FCC: apply failed — try manually")
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- Connection ---

    /**
     * Connects to the DUML proxy, auto-detecting the correct port.
     * Probes for the aircraft serial number after connecting.
     */
    fun connect() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(status = "connecting", message = "Connecting to controller...") }
        log("Connecting to controller...")

        runOnIO {
            try {
                if (transport.connect()) {
                    log("Controller connected")
                    val detectedPort = transport.getDetectedPort()
                    if (detectedPort > 0) {
                        log("DUML port detected: $detectedPort")
                    }
                    val serial = transport.probeSerial(1500)
                    update {
                        copy(
                            status = "connected",
                            message = if (serial.isNotEmpty()) "Connected — $serial" else "Connected. Ready to apply FCC.",
                            isConnected = true,
                            aircraftSerial = serial
                        )
                    }
                    if (serial.isNotEmpty()) log("Aircraft serial: $serial")
                } else {
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
                endHardwareOp()
            }
        }
    }

    // --- FCC ---

    /**
     * Sends the 21-frame FCC unlock profile (2 rounds, 150ms between frames).
     * The profile already runs 2 rounds internally for reliability.
     */
    fun enableFcc() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(status = "applying", isBusy = true, busyProgress = 0f, message = "Enabling FCC mode...") }
        log("Enabling FCC mode...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "fcc.json")
                log("Loaded FCC profile: ${profile.frames.size} frames, ${profile.rounds} rounds")

                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    interFrameDelayMs = profile.interFrameDelay,
                    interRoundDelayMs = profile.interRoundDelay,
                    readWindowMs = profile.readWindowMs,
                    port = profile.port
                ) { progress -> update { copy(busyProgress = progress) } }

                if (success) {
                    update {
                        copy(
                            status = "fcc_enabled",
                            message = "FCC mode enabled",
                            isFccEnabled = true,
                            isBusy = false,
                            busyProgress = 1f,
                            isConnected = true
                        )
                    }
                    log("FCC mode enabled — ${profile.frames.size} frames sent")
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
                log("FCC apply error: ${e.message}")
                update { copy(status = "connected", message = "FCC apply error: ${e.message}", isBusy = false, busyProgress = 0f) }
            } finally {
                endHardwareOp()
            }
        }
    }

    /** Sends the CE restore command: a single frame that resets to factory region. */
    fun disableFcc() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(status = "restoring", isBusy = true, busyProgress = 0f, message = "Restoring CE mode...") }
        log("Restoring CE mode...")

        runOnIO {
            try {
                val profile = Profiles.load(app, "ce_restore.json")
                val success = transport.sendFrames(
                    frames = profile.frames,
                    rounds = profile.rounds,
                    readWindowMs = profile.readWindowMs
                )

                if (success) {
                    update { copy(status = "connected", message = "CE mode restored", isFccEnabled = false, isBusy = false) }
                    log("CE mode restored")
                } else {
                    update { copy(status = "connected", message = "CE restore failed — RC link unreachable", isBusy = false) }
                    log("CE restore failed")
                }
            } catch (e: Exception) {
                log("CE restore error: ${e.message}")
                update { copy(status = "connected", message = "CE restore error: ${e.message}", isBusy = false) }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- FCC Keepalive ---

    /**
     * Starts a foreground service that re-applies the FCC profile every 2 seconds.
     * This prevents DJI Fly from resetting the radio back to CE mode when it
     * connects to the drone. The service runs independently of the Activity
     * lifecycle so it keeps working when the user switches to DJI Fly.
     */
    fun startKeepalive() {
        if (_state.value.isKeepaliveRunning) {
            log("Keepalive already running")
            return
        }
        update { copy(isKeepaliveRunning = true) }
        FccKeepaliveService.start(app)
        log("Started FCC keepalive — re-applying every 2s to prevent CE reset")
    }

    /** Stops the keepalive foreground service. */
    fun stopKeepalive() {
        FccKeepaliveService.stop(app)
        update { copy(isKeepaliveRunning = false) }
        log("FCC keepalive stopped")
    }

    // --- Launch DJI Fly ---

    /**
     * Launches the DJI Fly app (dji.go.v5) so the user can continue flying
     * with FCC mode active. The keepalive service keeps re-applying FCC in the
     * background while DJI Fly runs.
     */
    fun launchDjiFly() {
        val pm = app.packageManager
        // Try the standard launch intent first
        var intent = pm.getLaunchIntentForPackage("dji.go.v5")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log("Launched DJI Fly")
                return
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
                return
            } catch (_: Exception) {}
        }

        // Fallback 2: try dji.go.v4
        intent = pm.getLaunchIntentForPackage("dji.go.v4")
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                app.startActivity(intent)
                log("Launched DJI Go 4")
                return
            } catch (_: Exception) {}
        }

        log("DJI Fly not installed or cannot launch on this controller")
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
     */
    fun send4gActivationFrames() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(is4gBusy = true, busyProgress = 0f, fourGMessage = "") }
        log("Sending 4G activation frames...")

        runOnIO {
            try {
                val serial = getOrProbeSerial()
                if (serial.isEmpty()) {
                    update {
                        copy(is4gBusy = false, fourGMessage = "4G needs the aircraft connected. Power on the drone and try again.")
                    }
                    log("4G activation failed — no aircraft serial")
                    return@runOnIO
                }

                val profile = Profiles.load4g(app, serial)
                log("Loaded 4G profile: ${profile.frames.size} frames (serial: $serial)")

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
                    update { copy(is4gBusy = false, fourGMessage = "4G apply failed — is the 4G dongle connected?") }
                    log("4G activation failed — at least one frame write failed on the Unix socket")
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- LED ---

    /**
     * Turns the aircraft arm LEDs on or off.
     * Uses port 40007 (different from the standard 40009 DUML port).
     * Requires DJI Fly running with the aircraft connected.
     * Sends the command twice with a 500ms delay for reliability.
     *
     * @param on true for LED ON, false for LED OFF
     */
    fun setLed(on: Boolean) {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        update { copy(isLedBusy = true, ledStatus = if (on) "Turning LEDs on..." else "Turning LEDs off...") }
        log(if (on) "Turning LEDs on..." else "Turning LEDs off...")

        runOnIO {
            try {
                val fileName = if (on) "led_on.json" else "led_off.json"
                val profile = Profiles.load(app, fileName)
                log("Loaded LED profile: ${profile.frames.size} frames (port ${profile.port})")

                var anySuccess = false

                // Send the LED command twice with a 500ms delay for reliability
                for (attempt in 0 until 2) {
                    if (attempt > 0) {
                        delay(500)
                    }

                    val success = transport.sendFrames(
                        frames = profile.frames,
                        rounds = profile.rounds,
                        interFrameDelayMs = profile.interFrameDelay,
                        interRoundDelayMs = profile.interRoundDelay,
                        readWindowMs = profile.readWindowMs,
                        port = profile.port
                    )

                    if (success) anySuccess = true
                }

                if (anySuccess) {
                    update { copy(isLedBusy = false, ledStatus = if (on) "ON" else "OFF") }
                    log(if (on) "LEDs turned on" else "LEDs turned off")
                } else {
                    update { copy(isLedBusy = false, ledStatus = "Failed — is DJI Fly running?") }
                    log("LED command failed — make sure DJI Fly is running with aircraft connected")
                }
            } catch (e: Exception) {
                log("LED error: ${e.message}")
                update { copy(isLedBusy = false, ledStatus = "Error: ${e.message}") }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- Device Info ---

    /**
     * Queries the controller for hardware version, bootloader version, and
     * firmware version via the GENERAL VersionInquiry command
     * (cmd_set=0, cmd_id=1). Uses sendAndReceive to capture the response.
     */
    fun queryDeviceInfo() {
        if (!isControllerReachable()) return
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
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

                val response = transport.sendAndReceive(frame, profile.readWindowMs)

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
                endHardwareOp()
            }
        }
    }

    fun probeSerial() {
        if (!beginHardwareOp()) {
            log("Hardware busy — please wait for the current operation to finish.")
            return
        }
        log("Probing for aircraft serial...")
        runOnIO {
            try {
                val serial = transport.probeSerial(2000)
                if (serial.isNotEmpty()) {
                    update { copy(aircraftSerial = serial) }
                    log("Aircraft serial: $serial")
                } else {
                    log("No serial detected — is the aircraft powered on?")
                }
            } finally {
                endHardwareOp()
            }
        }
    }

    // --- Updates ---

    fun checkForUpdates() {
        update { copy(isCheckingUpdate = true) }
        log("Checking for updates...")

        runOnIO {
            val info = UpdateChecker.fetchLatest()
            if (info == null) {
                update { copy(isCheckingUpdate = false, updateChecked = true) }
                log("Update check failed — no internet or GitHub unreachable")
                return@runOnIO
            }

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

    fun downloadUpdate() {
        val info = _state.value.updateInfo ?: return
        update { copy(isDownloadingUpdate = true, updateDownloadProgress = 0f, isUpdateDownloaded = false) }
        log("Downloading update v${info.version}...")

        runOnIO {
            val file = UpdateChecker.downloadApk(app, info) { progress ->
                update { copy(updateDownloadProgress = progress) }
            }

            if (file == null) {
                update { copy(isDownloadingUpdate = false, updateDownloadProgress = 0f) }
                log("Update download failed — check your internet connection")
                return@runOnIO
            }

            downloadedApk = file
            update { copy(isDownloadingUpdate = false, updateDownloadProgress = 1f, isUpdateDownloaded = true) }
            log("Update downloaded — tap Install to apply")
        }
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
                // Copy APK to external storage where the file manager can see it
                val destDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "FreeFCC")
                destDir.mkdirs()
                val destFile = java.io.File(destDir, "FreeFCC_update.apk")
                file.copyTo(destFile, overwrite = true)
                log("Update saved to SD card: ${destFile.absolutePath}")

                // Launch the system installer via FileProvider ACTION_VIEW.
                // Previously this also fired ACTION_GET_CONTENT (file manager) which
                // competed with the installer for focus on DJI's custom launcher.
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    app, "${app.packageName}.fileprovider", destFile
                )
                val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                try {
                    app.startActivity(viewIntent)
                    log("Launching installer...")
                } catch (_: Exception) {
                    // Installer unavailable — fall back to telling the user where the file is.
                    log("Installer unavailable — open 02_FileManager and tap FreeFCC_update.apk")
                }
            } catch (e: Exception) {
                log("Install failed: ${e.message}")
            } finally {
                update { copy(isBusy = false) }
            }
        }
    }

    // --- Helpers ---

    /** Returns true if the controller is connected, logs a hint if not. */
    private fun isControllerReachable(): Boolean {
        if (_state.value.isConnected) return true
        log("Connect to the controller first")
        return false
    }

    /** Returns the cached serial, or probes the controller if not yet known. */
    private fun getOrProbeSerial(): String {
        var serial = _state.value.aircraftSerial
        if (serial.isEmpty()) {
            log("Probing for aircraft serial...")
            serial = transport.probeSerial(2000)
            if (serial.isNotEmpty()) {
                update { copy(aircraftSerial = serial) }
                log("Aircraft serial: $serial")
            }
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
        _state.value = _state.value.block()
    }

    /** Adds a timestamped entry to the activity log (most recent first, max 50). */
    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val entry = "[$time] $message"
        update { copy(logMessages = (listOf(entry) + logMessages).take(50)) }
    }

    /** Launches a coroutine on Dispatchers.IO for network operations. */
    private fun runOnIO(block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) { block() }
    }
}