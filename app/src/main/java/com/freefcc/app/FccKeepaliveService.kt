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

    /** Cached at onCreate — JSON parsing and CRC building do not belong in the worker. */
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
            val bootstrapProfile = cachedBootstrapProfile ?: return@launch
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

            var finalResult: BootstrapApplyResult? = null
            while (requestGate.isCurrent(generation) && finalResult == null) {
                var hardwareLease: HardwareLock.Lease? = null
                while (requestGate.isCurrent(generation) && hardwareLease == null) {
                    hardwareLease = HardwareLock.tryBegin()
                    if (hardwareLease == null) delay(200)
                }
                if (!requestGate.isCurrent(generation) || hardwareLease == null) return@launch

                val attempt = try {
                    sendBootstrapProfile(bootstrapProfile, generation)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    BootstrapApplyResult.PARTIAL_FAILURE
                } finally {
                    hardwareLease.close()
                }

                when {
                    BootstrapRetryPolicy.shouldRetry(attempt) -> {
                        // Safe retry: no profile frame was sent.
                        delay(APPLY_CONNECT_RETRY_DELAY_MS)
                    }
                    attempt == BootstrapApplyResult.CANCELLED -> return@launch
                    else -> finalResult = attempt
                }
            }

            val written = finalResult == BootstrapApplyResult.SUCCESS
            synchronized(Companion) {
                // Completion and all terminal side effects are one lifecycle
                // transaction. A stop/start that creates a newer generation
                // either happens before this block (and makes complete fail)
                // or after it (and cannot be torn down by this worker).
                if (!requestGate.complete(generation)) return@synchronized
                FccRuntime.tracker.recordWrite(written)
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
        generation: Long
    ): BootstrapApplyResult {
        if (!requestGate.isCurrent(generation)) return BootstrapApplyResult.CANCELLED
        if (profile.port == DumlTransport.PORT && !transport.connect()) {
            return BootstrapApplyResult.PRE_WRITE_CONNECT_FAILED
        }
        val port = if (profile.port == DumlTransport.PORT) {
            transport.getDetectedPort().takeIf { it > 0 } ?: DumlTransport.PORT
        } else {
            profile.port
        }

        var allSuccess = true
        for (round in 0 until profile.rounds) {
            for ((frameIndex, frame) in profile.frames.withIndex()) {
                if (!requestGate.isCurrent(generation)) return BootstrapApplyResult.CANCELLED
                if (!transport.sendFrame(frame, profile.readWindowMs, port)) {
                    allSuccess = false
                }
                if (!requestGate.isCurrent(generation)) return BootstrapApplyResult.CANCELLED
                if (profile.interFrameDelay > 0 && frameIndex < profile.frames.lastIndex) {
                    delay(profile.interFrameDelay)
                }
            }
            if (profile.interRoundDelay > 0 && round < profile.rounds - 1) {
                delay(profile.interRoundDelay)
            }
        }
        return if (allSuccess) BootstrapApplyResult.SUCCESS else BootstrapApplyResult.PARTIAL_FAILURE
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
