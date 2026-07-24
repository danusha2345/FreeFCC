package com.freefcc.app

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AutoFccMode(val wireValue: String) {
    HOME_POINT_TEXT("home_point_text"),
    PERIODIC_5S("periodic_5s");

    companion object {
        fun fromWireValue(value: String?): AutoFccMode =
            entries.firstOrNull { it.wireValue == value } ?: HOME_POINT_TEXT

        fun fromPersistedValue(value: String?): AutoFccMode? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal enum class BootstrapApplyResult {
    SUCCESS,
    PRE_WRITE_CONNECT_FAILED,
    PARTIAL_FAILURE,
    CANCELLED
}

internal data class BootstrapApplyReport(
    val result: BootstrapApplyResult,
    val port: Int,
    val expectedWrites: Int,
    val flushedWrites: Int,
    val matchingAcks: Int
)

internal object BootstrapRetryPolicy {
    fun shouldRetry(result: BootstrapApplyResult): Boolean =
        result == BootstrapApplyResult.PRE_WRITE_CONNECT_FAILED
}

internal object HomePointSignalPolicy {
    fun shouldAccept(
        lastGeneration: Long?,
        lastSignalAtMs: Long,
        generation: Long,
        nowMs: Long,
        debounceMs: Long
    ): Boolean =
        lastGeneration != generation || nowMs - lastSignalAtMs >= debounceMs
}

/**
 * Foreground Auto FCC service. Home Point mode keeps waiting for original DJI
 * Fly accessibility text and applies the complete profile after every new
 * flight-session Home Point event. Periodic mode applies the complete profile
 * once and then sends the original four-frame keepalive every five seconds.
 * Both modes remain active until the user turns their switch off.
 */
class FccKeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID = AppForegroundService.CHANNEL_ID
        const val NOTIFICATION_ID = AppForegroundService.NOTIFICATION_ID
        const val ACTION_START = "com.freefcc.app.START_KEEPALIVE"
        const val ACTION_STOP = "com.freefcc.app.STOP_KEEPALIVE"
        private const val EXTRA_REQUEST_GENERATION = "request_generation"
        private const val EXTRA_AUTO_MODE = "auto_mode"
        internal const val PERIODIC_INTERVAL_MS = 5_000L
        internal const val HOME_POINT_DEBOUNCE_MS = 30_000L
        private const val APPLY_CONNECT_RETRY_DELAY_MS = 5_000L
        private const val PREFS_NAME = "freefcc"
        private const val PREF_KEEPALIVE = "keepalive_running"
        private val requestGate = AutoFccRequestGate()
        private val homePointDetections = Channel<Long>(Channel.CONFLATED)
        private var lastSignaledGeneration: Long? = null
        private var lastHomePointSignalAtMs = 0L
        private var activeMode = AutoFccMode.HOME_POINT_TEXT

        @Synchronized
        fun start(context: Context, mode: AutoFccMode = AutoFccMode.HOME_POINT_TEXT): Boolean {
            check(mode != AutoFccMode.HOME_POINT_TEXT || isDjiFlyTextAccessEnabled(context)) {
                "Enable SkylabFCCfree Home Point Test in Accessibility settings first"
            }
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hadPersistentRequest = preferences.getBoolean(PREF_KEEPALIVE, false)
            val request = requestGate.request()
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_REQUEST_GENERATION, request.generation)
                putExtra(EXTRA_AUTO_MODE, mode.wireValue)
            }
            if (request.newlyRequested) FccRuntime.tracker.serviceStartRequested()
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                requestGate.rollbackNewRequest(request)
                if (request.newlyRequested) FccRuntime.tracker.serviceFailed(e.message)
                throw e
            }
            // Keep only an in-flight marker after Android accepts the explicit
            // request. The service is non-sticky and this marker never restarts it.
            preferences.edit().putBoolean(PREF_KEEPALIVE, true).apply()
            return !hadPersistentRequest
        }

        fun startSelectedMode(context: Context): Boolean =
            try {
                val mode = AutoFccSelection.load(context)
                if (mode == null) {
                    false
                } else if (
                    mode == AutoFccMode.HOME_POINT_TEXT &&
                    !isDjiFlyTextAccessEnabled(context)
                ) {
                    FccViewModel.logServiceEvent(
                        "AUTO FCC STARTUP: Home Point mode selected, but Accessibility is disabled"
                    )
                    false
                } else {
                    start(context, mode)
                    true
                }
            } catch (e: Exception) {
                FccViewModel.logServiceEvent(
                    "AUTO FCC STARTUP: ${e.javaClass.simpleName}: ${e.message}"
                )
                false
            }

        internal fun isDjiFlyTextAccessEnabled(context: Context): Boolean {
            val expected = ComponentName(context, DjiFlyAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            return enabled.split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == expected }
        }

        @Synchronized
        internal fun notifyHomePointDetected(): Boolean {
            if (activeMode != AutoFccMode.HOME_POINT_TEXT) return false
            val generation = requestGate.currentDeliveredGeneration() ?: return false
            val now = System.currentTimeMillis()
            if (!HomePointSignalPolicy.shouldAccept(
                    lastGeneration = lastSignaledGeneration,
                    lastSignalAtMs = lastHomePointSignalAtMs,
                    generation = generation,
                    nowMs = now,
                    debounceMs = HOME_POINT_DEBOUNCE_MS
                )
            ) {
                return false
            }
            if (!homePointDetections.trySend(generation).isSuccess) return false
            lastSignaledGeneration = generation
            lastHomePointSignalAtMs = now
            return true
        }

        @Synchronized
        fun stop(context: Context, clearSelection: Boolean = true) {
            // Set this before posting ACTION_STOP. The service intent is
            // asynchronous, while disableFcc() must prevent another keepalive
            // write immediately after its hardware lease is released.
            requestGate.cancel()
            lastSignaledGeneration = null
            lastHomePointSignalAtMs = 0L
            FccRuntime.tracker.serviceStopped()
            if (clearSelection) AutoFccSelection.save(context, null)
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /** Clears an obsolete in-flight marker without starting the service. */
        @Synchronized
        fun clearStaleRequest(context: Context) {
            requestGate.cancel()
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            FccRuntime.tracker.serviceStopped()
        }

        /** Returns whether an explicit Auto FCC request is currently marked active. */
        fun isRunningFlagSet(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEEPALIVE, false)

        internal fun requiresImmediateForeground(action: String?): Boolean =
            action == ACTION_START

        internal fun deliveredStartGeneration(action: String?, encodedGeneration: Long): Long? =
            encodedGeneration.takeIf { action == ACTION_START && it > 0L }

        internal fun deliveredAutoMode(action: String?, encodedMode: String?): AutoFccMode? =
            AutoFccMode.fromWireValue(encodedMode).takeIf { action == ACTION_START }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepaliveJob: Job? = null
    @Volatile private var latestStartId: Int = 0
    @Volatile private var destroyed = false
    private val transport = DumlTransport()

    /** Loaded once for fail-fast validation; apply builds a fresh profile after detection. */
    private var cachedBootstrapProfile: Profiles.Profile? = null

    override fun onCreate() {
        super.onCreate()
        AppForegroundNotification.createChannel(this)
        runCatching {
            cachedBootstrapProfile = Profiles.load(this, "fcc.json")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only an explicit ACTION_START has a foreground-service start contract.
        // Null-intent restarts fail closed below and never resurrect Auto FCC.
        if (requiresImmediateForeground(intent?.action)) {
            startForeground(
                NOTIFICATION_ID,
                AppForegroundNotification.create(this)
            )
        }
        return synchronized(Companion) {
            latestStartId = maxOf(latestStartId, startId)
            onStartCommandLocked(intent, startId)
        }
    }

    /** Runs under the same companion lock as start()/stop(). */
    private fun onStartCommandLocked(intent: Intent?, startId: Int): Int {
        if (intent == null) {
            requestGate.cancel()
            keepaliveJob?.cancel()
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            FccRuntime.tracker.serviceStopped()
            stopForeground(STOP_FOREGROUND_DETACH)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                // A newer start() may already have superseded this queued
                // stop intent. The process-wide desired state is updated
                // synchronously before either intent is posted.
                if (requestGate.currentGeneration() != null) {
                    // The replacement request cannot run until its own
                    // ACTION_START is delivered. Cancel the superseded worker
                    // now and let its completion wait for that delivery.
                    keepaliveJob?.cancel()
                    return START_NOT_STICKY
                }
                keepaliveJob?.cancel()
                stopForeground(STOP_FOREGROUND_DETACH)
                FccRuntime.tracker.serviceStopped()
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            else -> {
                if (intent?.action == ACTION_START) {
                    // Ignore an ACTION_START that was queued before a newer
                    // stop(). It must not resurrect the service or pref.
                    val intentGeneration = deliveredStartGeneration(
                        intent.action,
                        intent.getLongExtra(EXTRA_REQUEST_GENERATION, -1L)
                    )
                    val intentMode = deliveredAutoMode(
                        intent.action,
                        intent.getStringExtra(EXTRA_AUTO_MODE)
                    )
                    if (intentGeneration == null || !requestGate.markDelivered(intentGeneration)) {
                        if (requestGate.currentGeneration() != null) {
                            // A newer request exists, but only its own exact
                            // ACTION_START may deliver and launch it.
                            return START_NOT_STICKY
                        }
                        keepaliveJob?.cancel()
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                        stopForeground(STOP_FOREGROUND_DETACH)
                        FccRuntime.tracker.serviceStopped()
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }
                    activeMode = intentMode ?: AutoFccMode.HOME_POINT_TEXT
                } else {
                    requestGate.cancel()
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    FccRuntime.tracker.serviceStopped()
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                if (requestGate.currentGeneration() == null) {
                    FccRuntime.tracker.serviceStopped()
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                // If the profile failed to load, don't become a silent
                // foreground no-op — stop immediately.
                if (cachedBootstrapProfile == null) {
                    requestGate.cancel()
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    FccRuntime.tracker.serviceFailed("FCC profile unavailable")
                    stopForeground(STOP_FOREGROUND_DETACH)
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                startKeepaliveLoop(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startKeepaliveLoop(startId: Int): Unit = synchronized(Companion) {
        latestStartId = maxOf(latestStartId, startId)
        val generation = requestGate.currentDeliveredGeneration() ?: return@synchronized
        val mode = activeMode
        if (keepaliveJob?.isActive == true) return@synchronized

        val worker = scope.launch(start = CoroutineStart.LAZY) {
            if (cachedBootstrapProfile == null) return@launch
            FccRuntime.tracker.serviceRunning()
            val bootstrapProfile = try {
                Profiles.load(this@FccKeepaliveService, "fcc.json")
            } catch (e: Exception) {
                finishAutoRun(generation, false, "FCC profile unavailable: ${e.message}")
                return@launch
            }

            if (mode == AutoFccMode.HOME_POINT_TEXT) {
                FccViewModel.logServiceEvent(
                    "DJI FLY TEXT FCC: armed continuously; waiting for Home Point events"
                )
                while (requestGate.isCurrent(generation)) {
                    var homePointObservedAtMs: Long? = null
                    while (requestGate.isCurrent(generation) && homePointObservedAtMs == null) {
                        val detectedGeneration = homePointDetections.receive()
                        if (detectedGeneration == generation) {
                            homePointObservedAtMs = System.currentTimeMillis()
                        }
                    }
                    if (!requestGate.isCurrent(generation)) return@launch

                    val pinnedPort = awaitControllerPort(generation) ?: return@launch
                    FccViewModel.logServiceEvent(
                        "DJI FLY TEXT FCC: Home Point detected; applying full profile"
                    )
                    applyCountryRegionOnce(generation, pinnedPort)?.let { result ->
                        logCountryRegionResult("DJI FLY TEXT FCC", result)
                    }
                    val report = applyWithPreWriteRetry(
                        profile = bootstrapProfile,
                        generation = generation,
                        pinnedPort = pinnedPort,
                        label = "DJI FLY TEXT FCC",
                        homePointObservedAtMs = homePointObservedAtMs
                    ) ?: return@launch
                    when (report.result) {
                        BootstrapApplyResult.SUCCESS -> FccViewModel.logServiceEvent(
                            "DJI FLY TEXT FCC: apply complete; re-armed for the next flight session"
                        )
                        BootstrapApplyResult.CANCELLED -> return@launch
                        else -> FccViewModel.logServiceEvent(
                            "DJI FLY TEXT FCC: apply failed; waiting for the next Home Point event"
                        )
                    }
                }
                return@launch
            }

            FccViewModel.logServiceEvent(
                "PERIODIC FCC: starting full profile, then legacy keepalive every 5s"
            )
            val pinnedPort = awaitControllerPort(generation) ?: return@launch
            applyCountryRegionOnce(generation, pinnedPort)?.let { result ->
                logCountryRegionResult("PERIODIC FCC bootstrap", result)
            }
            val bootstrapReport = applyWithPreWriteRetry(
                profile = bootstrapProfile,
                generation = generation,
                pinnedPort = pinnedPort,
                label = "PERIODIC FCC bootstrap",
                homePointObservedAtMs = null
            ) ?: return@launch
            if (bootstrapReport.result != BootstrapApplyResult.SUCCESS) {
                finishAutoRun(generation, false, "PERIODIC FCC bootstrap failed")
                return@launch
            }

            val keepaliveProfile = try {
                Profiles.load(this@FccKeepaliveService, "fcc_keepalive.json")
            } catch (e: Exception) {
                finishAutoRun(generation, false, "Legacy FCC keepalive unavailable: ${e.message}")
                return@launch
            }
            while (requestGate.isCurrent(generation)) {
                delay(PERIODIC_INTERVAL_MS)
                if (!requestGate.isCurrent(generation)) return@launch
                val report = applyProfileOnce(
                    profile = keepaliveProfile,
                    generation = generation,
                    pinnedPort = pinnedPort,
                    label = "PERIODIC FCC tick",
                    homePointObservedAtMs = null
                )
                if (report.result == BootstrapApplyResult.CANCELLED) return@launch
            }
        }
        keepaliveJob = worker
        worker.invokeOnCompletion {
            scope.launch {
                synchronized(Companion) {
                    if (keepaliveJob === worker) {
                        keepaliveJob = null
                    }
                    if (!destroyed && requestGate.currentDeliveredGeneration() != null) {
                        startKeepaliveLoop(latestStartId)
                    }
                }
            }
        }
        worker.start()
    }

    private suspend fun awaitControllerPort(generation: Long): Int? {
        FccRuntime.tracker.state.value.controllerPort?.let { return it }
        while (requestGate.isCurrent(generation)) {
            val hardwareLease = HardwareLock.tryBegin()
            if (hardwareLease == null) {
                delay(200)
                continue
            }
            val connected = try {
                transport.connect()
            } finally {
                hardwareLease.close()
            }
            if (connected) {
                val port = transport.getDetectedPort().takeIf { it > 0 } ?: DumlTransport.PORT
                FccRuntime.tracker.beginHardwareSession(port)
                FccViewModel.logServiceEvent("AUTO FCC: controller connected on port $port")
                return port
            }
            FccViewModel.logServiceEvent(
                "AUTO FCC: controller unavailable; retrying in ${APPLY_CONNECT_RETRY_DELAY_MS / 1_000}s"
            )
            delay(APPLY_CONNECT_RETRY_DELAY_MS)
        }
        return null
    }

    private suspend fun applyCountryRegionOnce(
        generation: Long,
        pinnedPort: Int
    ): FccCountryRegionResult? {
        var hardwareLease: HardwareLock.Lease? = null
        var sessionLease: DumlPortSessionLock.Lease? = null
        while (requestGate.isCurrent(generation) && sessionLease == null) {
            hardwareLease = HardwareLock.tryBegin()
            if (hardwareLease == null) {
                delay(200)
                continue
            }
            sessionLease = DumlPortSessionLock.tryBegin(pinnedPort)
            if (sessionLease == null) {
                hardwareLease.close()
                hardwareLease = null
                delay(200)
            }
        }
        if (!requestGate.isCurrent(generation) || hardwareLease == null || sessionLease == null) {
            sessionLease?.close()
            hardwareLease?.close()
            return null
        }
        return try {
            FccCountryRegion.apply(transport, pinnedPort)
        } finally {
            sessionLease.close()
            hardwareLease.close()
        }
    }

    private fun logCountryRegionResult(label: String, result: FccCountryRegionResult) {
        FccViewModel.logServiceEvent(
            "$label country: write=${result.writeCompleted}, " +
                "write_ack=${result.writeAckMatched}, read=${result.readCompleted}, " +
                "read_ack=${result.readAckMatched}, " +
                "observed=${result.observedCountry ?: "unknown"}, verified=${result.verified}"
        )
    }

    private suspend fun applyWithPreWriteRetry(
        profile: Profiles.Profile,
        generation: Long,
        pinnedPort: Int,
        label: String,
        homePointObservedAtMs: Long?
    ): BootstrapApplyReport? {
        while (requestGate.isCurrent(generation)) {
            val report = applyProfileOnce(
                profile,
                generation,
                pinnedPort,
                label,
                homePointObservedAtMs
            )
            if (!BootstrapRetryPolicy.shouldRetry(report.result)) return report
            delay(APPLY_CONNECT_RETRY_DELAY_MS)
        }
        return null
    }

    private suspend fun applyProfileOnce(
        profile: Profiles.Profile,
        generation: Long,
        pinnedPort: Int,
        label: String,
        homePointObservedAtMs: Long?
    ): BootstrapApplyReport {
        var hardwareLease: HardwareLock.Lease? = null
        var sessionLease: DumlPortSessionLock.Lease? = null
        while (requestGate.isCurrent(generation) && sessionLease == null) {
            hardwareLease = HardwareLock.tryBegin()
            if (hardwareLease == null) {
                delay(200)
                continue
            }
            sessionLease = DumlPortSessionLock.tryBegin(pinnedPort)
            if (sessionLease == null) {
                hardwareLease.close()
                hardwareLease = null
                delay(200)
            }
        }
        if (!requestGate.isCurrent(generation) || hardwareLease == null || sessionLease == null) {
            sessionLease?.close()
            hardwareLease?.close()
            return BootstrapApplyReport(
                BootstrapApplyResult.CANCELLED,
                pinnedPort,
                profile.frames.size * profile.rounds,
                0,
                0
            )
        }

        val expectedWrites = profile.frames.size * profile.rounds
        val runtimeAttempt = FccRuntime.tracker.beginApply(
            origin = FccApplyOrigin.HOME_POINT_AUTO,
            port = pinnedPort,
            expectedWrites = expectedWrites,
            homePointObservedAtMs = homePointObservedAtMs
        )
        FccViewModel.logServiceEvent(
            "$label: applying $expectedWrites writes on pinned port $pinnedPort"
        )
        val report = try {
            sendBootstrapProfile(profile, generation, pinnedPort)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            FccViewModel.logServiceEvent("$label: apply error: ${e.message}")
            BootstrapApplyReport(
                BootstrapApplyResult.PARTIAL_FAILURE,
                pinnedPort,
                expectedWrites,
                0,
                0
            )
        } finally {
            sessionLease.close()
            hardwareLease.close()
        }
        val outcome = when (report.result) {
            BootstrapApplyResult.SUCCESS -> FccApplyOutcome.ALL_WRITES_FLUSHED
            BootstrapApplyResult.PRE_WRITE_CONNECT_FAILED -> FccApplyOutcome.PRE_WRITE_CONNECT_FAILED
            BootstrapApplyResult.PARTIAL_FAILURE -> FccApplyOutcome.PARTIAL_WRITE_FAILURE
            BootstrapApplyResult.CANCELLED -> FccApplyOutcome.CANCELLED
        }
        FccRuntime.tracker.finishApply(
            startedAtMs = runtimeAttempt.startedAtMs,
            flushedWrites = report.flushedWrites,
            matchingAcks = report.matchingAcks,
            outcome = outcome
        )
        FccViewModel.logServiceEvent(
            "$label: ${report.result} on port ${report.port}; " +
                "writes=${report.flushedWrites}/${report.expectedWrites}, " +
                "matching_acks=${report.matchingAcks}; RF state unknown"
        )
        return report
    }

    private fun finishAutoRun(generation: Long, success: Boolean, error: String?) {
        synchronized(Companion) {
            if (!requestGate.complete(generation)) return
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            stopForeground(STOP_FOREGROUND_DETACH)
            if (success) FccRuntime.tracker.serviceStopped()
            else FccRuntime.tracker.serviceFailed(error)
            stopSelfResult(latestStartId)
        }
    }

    /** Retries are allowed only before the first frame; partial profiles are final failures. */
    private suspend fun sendBootstrapProfile(
        profile: Profiles.Profile,
        generation: Long,
        pinnedPort: Int
    ): BootstrapApplyReport {
        val expectedWrites = profile.frames.size * profile.rounds
        if (!requestGate.isCurrent(generation)) {
            return BootstrapApplyReport(
                BootstrapApplyResult.CANCELLED, pinnedPort, expectedWrites, 0, 0
            )
        }
        if (!transport.isReachable(pinnedPort)) {
            return BootstrapApplyReport(
                BootstrapApplyResult.PRE_WRITE_CONNECT_FAILED, pinnedPort, expectedWrites, 0, 0
            )
        }

        var flushedWrites = 0
        var matchingAcks = 0
        for (round in 0 until profile.rounds) {
            for ((frameIndex, frame) in profile.frames.withIndex()) {
                if (!requestGate.isCurrent(generation)) {
                    return BootstrapApplyReport(
                        BootstrapApplyResult.CANCELLED,
                        pinnedPort,
                        expectedWrites,
                        flushedWrites,
                        matchingAcks
                    )
                }
                val exchange = transport.sendAndReceiveRaw(
                    frame = frame,
                    readWindowMs = profile.readWindowMs,
                    port = pinnedPort,
                    autoDetectPort = false
                )
                if (exchange.writeCompleted) flushedWrites++
                if (exchange.matchedFrame != null) matchingAcks++
                if (!requestGate.isCurrent(generation)) {
                    return BootstrapApplyReport(
                        BootstrapApplyResult.CANCELLED,
                        pinnedPort,
                        expectedWrites,
                        flushedWrites,
                        matchingAcks
                    )
                }
                if (profile.interFrameDelay > 0 && frameIndex < profile.frames.lastIndex) {
                    delay(profile.interFrameDelay)
                }
            }
            if (profile.interRoundDelay > 0 && round < profile.rounds - 1) {
                delay(profile.interRoundDelay)
            }
        }
        val result = if (flushedWrites == expectedWrites) {
            BootstrapApplyResult.SUCCESS
        } else {
            BootstrapApplyResult.PARTIAL_FAILURE
        }
        return BootstrapApplyReport(result, pinnedPort, expectedWrites, flushedWrites, matchingAcks)
    }

    override fun onDestroy() {
        destroyed = true
        keepaliveJob?.cancel()
        scope.cancel()
        if (FccRuntime.tracker.state.value.keepaliveStatus != KeepaliveRuntimeStatus.FAILED) {
            FccRuntime.tracker.serviceStopped()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
