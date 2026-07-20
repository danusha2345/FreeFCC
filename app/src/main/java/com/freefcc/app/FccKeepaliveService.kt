package com.freefcc.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

internal enum class AutoFccServiceStartResult {
    DISABLED,
    STARTED,
    REASSERTED
}

/**
 * Foreground service that waits for the aircraft to record Home Point, applies
 * the complete FCC profile once, then stops. It runs independently of the
 * Activity lifecycle while the user is in DJI Fly.
 *
 * Home Point is read from passive direct/wrapped `03:44` telemetry through one
 * port-40007 connection. No periodic FCC writes are performed.
 */
class FccKeepaliveService : Service() {

    companion object {
        const val CHANNEL_ID = "fcc_keepalive"
        const val NOTIFICATION_ID = 9012
        const val ACTION_START = "com.freefcc.app.START_KEEPALIVE"
        const val ACTION_STOP = "com.freefcc.app.STOP_KEEPALIVE"
        private const val EXTRA_REQUEST_GENERATION = "request_generation"
        private const val INITIAL_CONNECT_RETRY_DELAY_MS = 15_000L
        private const val MAX_INITIAL_CONNECT_ATTEMPTS = 2
        private const val ARMED_STREAM_RETRY_DELAY_MS = 2_000L
        internal const val POST_HOME_POINT_SETTLE_DELAY_MS = 2_000L
        private const val APPLY_CONNECT_RETRY_DELAY_MS = 5_000L
        private const val PREFS_NAME = "freefcc"
        private const val PREF_AUTO_FCC = "auto_fcc"
        private const val PREF_KEEPALIVE = "keepalive_running"
        private val requestGate = AutoFccRequestGate()

        @Synchronized
        fun start(context: Context): Boolean {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val hadPersistentRequest = preferences.getBoolean(PREF_KEEPALIVE, false)
            val request = requestGate.request()
            val intent = Intent(context, FccKeepaliveService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_REQUEST_GENERATION, request.generation)
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

        /** Atomically rejects a late auto-start after the user toggled Auto-FCC off. */
        @Synchronized
        internal fun startAutoIfEnabled(context: Context): AutoFccServiceStartResult {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!preferences.getBoolean(PREF_AUTO_FCC, false)) {
                return AutoFccServiceStartResult.DISABLED
            }
            return if (start(context)) {
                AutoFccServiceStartResult.STARTED
            } else {
                AutoFccServiceStartResult.REASSERTED
            }
        }

        @Synchronized
        fun stop(context: Context) {
            // Set this before posting ACTION_STOP. The service intent is
            // asynchronous, while disableFcc() must prevent another keepalive
            // write immediately after its hardware lease is released.
            requestGate.cancel()
            FccRuntime.tracker.serviceStopped()
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

        /** Returns whether the explicit one-shot request is currently marked in flight. */
        fun isRunningFlagSet(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_KEEPALIVE, false)

        internal fun isPersistentAutoRequest(context: Context): Boolean {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return PersistentAutoFccRecoveryPolicy.shouldRestoreService(
                autoEnabled = preferences.getBoolean(PREF_AUTO_FCC, false),
                inFlightMarker = preferences.getBoolean(PREF_KEEPALIVE, false)
            )
        }

        internal fun requiresImmediateForeground(action: String?): Boolean =
            action == ACTION_START

        internal fun deliveredStartGeneration(action: String?, encodedGeneration: Long): Long? =
            encodedGeneration.takeIf { action == ACTION_START && it > 0L }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepaliveJob: Job? = null
    @Volatile private var latestStartId: Int = 0
    @Volatile private var destroyed = false
    private val transport = DumlTransport()

    /** Loaded once for fail-fast validation; apply builds a fresh profile after Home Point. */
    private var cachedBootstrapProfile: Profiles.Profile? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        runCatching {
            cachedBootstrapProfile = Profiles.load(this, "fcc.json")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Only an explicit ACTION_START has a foreground-service start contract.
        // Null-intent restarts fail closed below and never resurrect the monitor.
        if (requiresImmediateForeground(intent?.action)) {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        return synchronized(Companion) {
            latestStartId = maxOf(latestStartId, startId)
            onStartCommandLocked(intent, startId)
        }
    }

    /** Runs under the same monitor as companion start()/stop(). */
    private fun onStartCommandLocked(intent: Intent?, startId: Int): Int {
        if (intent == null) {
            requestGate.cancel()
            keepaliveJob?.cancel()
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_KEEPALIVE, false).apply()
            FccRuntime.tracker.serviceStopped()
            stopForeground(STOP_FOREGROUND_REMOVE)
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
                stopForeground(STOP_FOREGROUND_REMOVE)
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
                    if (intentGeneration == null || !requestGate.markDelivered(intentGeneration)) {
                        if (requestGate.currentGeneration() != null) {
                            // A newer request exists, but only its own exact
                            // ACTION_START may deliver and launch it.
                            return START_NOT_STICKY
                        }
                        keepaliveJob?.cancel()
                        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        FccRuntime.tracker.serviceStopped()
                        stopSelfResult(startId)
                        return START_NOT_STICKY
                    }
                } else {
                    requestGate.cancel()
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    FccRuntime.tracker.serviceStopped()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                if (requestGate.currentGeneration() == null) {
                    FccRuntime.tracker.serviceStopped()
                    stopForeground(STOP_FOREGROUND_REMOVE)
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
                    stopForeground(STOP_FOREGROUND_REMOVE)
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
        if (keepaliveJob?.isActive == true) return@synchronized

        val worker = scope.launch(start = CoroutineStart.LAZY) {
            cachedBootstrapProfile ?: return@launch
            val workerJob = currentCoroutineContext()[Job]
            FccRuntime.tracker.serviceRunning()
            val homePointSession = HomePointSessionGate()
            var homePointRecorded = false
            var initialConnectAttempts = 0
            var armedStreamReconnectUsed = false
            var monitorConnectionOrdinal = 0
            var monitorFailure: String? = null
            while (requestGate.isCurrent(generation) && !homePointRecorded) {
                if (Port40007Lock.shouldYieldToLed()) {
                    delay(25)
                    continue
                }
                val portLease = Port40007Lock.tryBegin()
                if (portLease == null) {
                    delay(250)
                    continue
                }
                monitorConnectionOrdinal++
                val waitResult = try {
                    HomePointMonitor().waitUntilRecorded(homePointSession) {
                        requestGate.isCurrent(generation) &&
                            workerJob?.isActive != false &&
                            !Port40007Lock.shouldYieldToLed()
                    }
                } finally {
                    portLease.close()
                }
                if (waitResult == HomePointWaitResult.CONNECT_FAILED) {
                    initialConnectAttempts++
                }
                val sessionArmed = homePointSession.isArmedForRecordedEdge()
                val waitDecision = HomePointRetryPolicy.decide(
                    result = waitResult,
                    initialConnectAttempts = initialConnectAttempts,
                    maxInitialConnectAttempts = MAX_INITIAL_CONNECT_ATTEMPTS,
                    sessionArmed = sessionArmed,
                    armedStreamReconnectUsed = armedStreamReconnectUsed
                )
                when (waitDecision) {
                    HomePointWaitDecision.RECORDED -> homePointRecorded = true
                    HomePointWaitDecision.RETRY_INITIAL_CONNECT -> {
                        delay(INITIAL_CONNECT_RETRY_DELAY_MS)
                    }
                    HomePointWaitDecision.RETRY_ARMED_STREAM_ONCE -> {
                        // Consume the single recovery budget before backoff so
                        // cancellation/re-entry cannot manufacture extra opens.
                        armedStreamReconnectUsed = true
                        delay(ARMED_STREAM_RETRY_DELAY_MS)
                    }
                    HomePointWaitDecision.FAIL_CLOSED -> {
                        // Reopening an established 40007 stream repeatedly was
                        // proven to disrupt the aircraft/controller link.
                        monitorFailure = if (waitResult == HomePointWaitResult.STREAM_DISCONNECTED) {
                            "Home Point stream disconnected; tap Retry Home Point"
                        } else {
                            "Home Point stream unavailable; tap Retry Home Point"
                        }
                        monitorFailure += " [connection=$monitorConnectionOrdinal, armed=$sessionArmed, recovery=$armedStreamReconnectUsed]"
                        break
                    }
                    HomePointWaitDecision.COOPERATIVE_STOP -> {
                        if (!requestGate.isCurrent(generation) || workerJob?.isActive == false) return@launch
                        while (requestGate.isCurrent(generation) && Port40007Lock.shouldYieldToLed()) {
                            delay(25)
                        }
                    }
                }
            }
            if (!requestGate.isCurrent(generation)) return@launch
            if (!homePointRecorded) {
                val failure = monitorFailure ?: "Home Point monitor stopped"
                synchronized(Companion) {
                    if (!requestGate.complete(generation)) return@synchronized
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    FccRuntime.tracker.serviceFailed(failure)
                    stopSelfResult(latestStartId)
                }
                return@launch
            }

            val homePointObservedAtMs = System.currentTimeMillis()
            FccViewModel.logServiceEvent(
                "AUTO FCC: Home Point=true observed; settling ${POST_HOME_POINT_SETTLE_DELAY_MS / 1_000}s"
            )
            delay(POST_HOME_POINT_SETTLE_DELAY_MS)
            if (!requestGate.isCurrent(generation)) return@launch

            val pinnedPort = FccRuntime.tracker.state.value.controllerPort
            if (pinnedPort == null) {
                FccViewModel.logServiceEvent(
                    "AUTO FCC: stopped before write — Connect did not provide a pinned DUML port"
                )
                synchronized(Companion) {
                    if (!requestGate.complete(generation)) return@synchronized
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    FccRuntime.tracker.serviceFailed("AUTO FCC has no pinned Connect port")
                    stopSelfResult(latestStartId)
                }
                return@launch
            }

            val bootstrapProfile = try {
                Profiles.load(this@FccKeepaliveService, "fcc.json")
            } catch (e: Exception) {
                FccViewModel.logServiceEvent("AUTO FCC: fresh profile load failed: ${e.message}")
                synchronized(Companion) {
                    if (!requestGate.complete(generation)) return@synchronized
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    FccRuntime.tracker.serviceFailed("FCC profile unavailable after Home Point")
                    stopSelfResult(latestStartId)
                }
                return@launch
            }

            var finalReport: BootstrapApplyReport? = null
            while (requestGate.isCurrent(generation) && finalReport == null) {
                var hardwareLease: HardwareLock.Lease? = null
                while (requestGate.isCurrent(generation) && hardwareLease == null) {
                    hardwareLease = HardwareLock.tryBegin()
                    if (hardwareLease == null) delay(200)
                }
                if (!requestGate.isCurrent(generation) || hardwareLease == null) return@launch

                val expectedWrites = bootstrapProfile.frames.size * bootstrapProfile.rounds
                val runtimeAttempt = FccRuntime.tracker.beginApply(
                    origin = FccApplyOrigin.HOME_POINT_AUTO,
                    port = pinnedPort,
                    expectedWrites = expectedWrites,
                    homePointObservedAtMs = homePointObservedAtMs
                )
                FccViewModel.logServiceEvent(
                    "AUTO FCC: applying $expectedWrites writes on pinned port $pinnedPort"
                )
                val report = try {
                    sendBootstrapProfile(bootstrapProfile, generation, pinnedPort)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    FccViewModel.logServiceEvent("AUTO FCC: apply error: ${e.message}")
                    BootstrapApplyReport(
                        result = BootstrapApplyResult.PARTIAL_FAILURE,
                        port = pinnedPort,
                        expectedWrites = expectedWrites,
                        flushedWrites = 0,
                        matchingAcks = 0
                    )
                } finally {
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
                    "AUTO FCC: ${report.result} on port ${report.port}; " +
                        "writes=${report.flushedWrites}/${report.expectedWrites}, " +
                        "matching_acks=${report.matchingAcks}; RF state unknown"
                )

                when {
                    BootstrapRetryPolicy.shouldRetry(report.result) -> {
                        // Safe retry: no profile frame was sent.
                        delay(APPLY_CONNECT_RETRY_DELAY_MS)
                    }
                    report.result == BootstrapApplyResult.CANCELLED -> return@launch
                    else -> finalReport = report
                }
            }

            val written = finalReport?.result == BootstrapApplyResult.SUCCESS
            synchronized(Companion) {
                // Completion and all terminal side effects are one lifecycle
                // transaction. A stop/start that creates a newer generation
                // either happens before this block (and makes complete fail)
                // or after it (and cannot be torn down by this worker).
                if (!requestGate.complete(generation)) return@synchronized
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit().putBoolean(PREF_KEEPALIVE, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (written) FccRuntime.tracker.serviceStopped()
                else FccRuntime.tracker.serviceFailed("FCC apply failed after Home Point")
                stopSelfResult(latestStartId)
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Home Point → FCC",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Waits for Home Point before applying FCC"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
        // Tapping the notification opens the app
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, openIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return builder
            .setContentTitle("FreeFCC")
            .setContentText("Waiting for Home Point...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
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
