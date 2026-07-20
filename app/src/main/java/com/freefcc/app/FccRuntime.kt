package com.freefcc.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class KeepaliveRuntimeStatus {
    STOPPED,
    STARTING,
    RUNNING,
    FAILED
}

internal enum class FccApplyOrigin {
    HOME_POINT_AUTO,
    MANUAL
}

internal enum class FccApplyOutcome {
    RUNNING,
    ALL_WRITES_FLUSHED,
    PARTIAL_WRITE_FAILURE,
    PRE_WRITE_CONNECT_FAILED,
    CANCELLED,
    ERROR
}

/** Diagnostic evidence about one profile attempt; RF state remains unknown. */
internal data class FccApplyAttempt(
    val origin: FccApplyOrigin,
    val homePointObservedAtMs: Long?,
    val startedAtMs: Long,
    val finishedAtMs: Long? = null,
    val port: Int,
    val expectedWrites: Int,
    val flushedWrites: Int = 0,
    val matchingAcks: Int? = null,
    val outcome: FccApplyOutcome = FccApplyOutcome.RUNNING
)

/** Process-local evidence. A successful write is not a physical FCC readback. */
internal data class FccRuntimeSnapshot(
    val keepaliveStatus: KeepaliveRuntimeStatus = KeepaliveRuntimeStatus.STOPPED,
    val controllerSessionEstablished: Boolean = false,
    val controllerPort: Int? = null,
    val lastSuccessfulWriteAtMs: Long? = null,
    val lastAttemptSucceeded: Boolean? = null,
    val lastApplyAttempt: FccApplyAttempt? = null,
    val lastAutomaticApplyAttempt: FccApplyAttempt? = null,
    val error: String? = null
)

internal fun resolveFccRuntimeStatus(
    currentStatus: String,
    isConnected: Boolean,
    keepaliveStatus: KeepaliveRuntimeStatus,
    hasWriteEvidence: Boolean
): String = when {
    currentStatus == "applying" || currentStatus == "restoring" -> currentStatus
    keepaliveStatus == KeepaliveRuntimeStatus.FAILED -> {
        if (isConnected) "monitor_failed" else "disconnected"
    }
    (keepaliveStatus == KeepaliveRuntimeStatus.STARTING ||
        keepaliveStatus == KeepaliveRuntimeStatus.RUNNING) && isConnected -> "waiting_home_point"
    hasWriteEvidence && isConnected -> "fcc_written"
    !hasWriteEvidence && currentStatus == "fcc_written" && isConnected -> "connected"
    else -> currentStatus
}

internal class FccRuntimeTracker {
    private val mutableState = MutableStateFlow(FccRuntimeSnapshot())
    val state: StateFlow<FccRuntimeSnapshot> = mutableState.asStateFlow()

    fun serviceStartRequested() {
        mutableState.update {
            it.copy(keepaliveStatus = KeepaliveRuntimeStatus.STARTING, error = null)
        }
    }

    fun serviceRunning() {
        mutableState.update {
            it.copy(keepaliveStatus = KeepaliveRuntimeStatus.RUNNING, error = null)
        }
    }

    fun serviceStopped() {
        mutableState.update {
            it.copy(keepaliveStatus = KeepaliveRuntimeStatus.STOPPED, error = null)
        }
    }

    fun serviceFailed(message: String?) {
        mutableState.update {
            it.copy(
                keepaliveStatus = KeepaliveRuntimeStatus.FAILED,
                error = message ?: "keepalive_start_failed"
            )
        }
    }

    fun beginApply(
        origin: FccApplyOrigin,
        port: Int,
        expectedWrites: Int,
        homePointObservedAtMs: Long? = null,
        startedAtMs: Long = System.currentTimeMillis()
    ): FccApplyAttempt {
        val attempt = FccApplyAttempt(
            origin = origin,
            homePointObservedAtMs = homePointObservedAtMs,
            startedAtMs = startedAtMs,
            port = port,
            expectedWrites = expectedWrites
        )
        mutableState.update {
            it.copy(
                lastAttemptSucceeded = null,
                lastApplyAttempt = attempt,
                lastAutomaticApplyAttempt = if (origin == FccApplyOrigin.HOME_POINT_AUTO) {
                    attempt
                } else {
                    it.lastAutomaticApplyAttempt
                }
            )
        }
        return attempt
    }

    fun finishApply(
        startedAtMs: Long,
        flushedWrites: Int,
        matchingAcks: Int?,
        outcome: FccApplyOutcome,
        finishedAtMs: Long = System.currentTimeMillis()
    ) {
        mutableState.update { current ->
            val running = current.lastApplyAttempt
            if (running == null || running.startedAtMs != startedAtMs) return@update current
            val finished = running.copy(
                finishedAtMs = finishedAtMs,
                flushedWrites = flushedWrites,
                matchingAcks = matchingAcks,
                outcome = outcome
            )
            val successful = outcome == FccApplyOutcome.ALL_WRITES_FLUSHED &&
                flushedWrites == running.expectedWrites
            current.copy(
                lastSuccessfulWriteAtMs = if (successful) finishedAtMs else current.lastSuccessfulWriteAtMs,
                lastAttemptSucceeded = successful,
                lastApplyAttempt = finished,
                lastAutomaticApplyAttempt = if (running.origin == FccApplyOrigin.HOME_POINT_AUTO) {
                    finished
                } else {
                    current.lastAutomaticApplyAttempt
                }
            )
        }
    }

    /** Starts a newly detected controller session with no FCC-state assumption. */
    fun beginHardwareSession(port: Int) {
        mutableState.update {
            it.copy(
                controllerSessionEstablished = true,
                controllerPort = port,
                lastSuccessfulWriteAtMs = null,
                lastAttemptSucceeded = null,
                lastApplyAttempt = null,
                lastAutomaticApplyAttempt = null
            )
        }
    }

    /** Records a failed explicit controller probe without altering FCC history. */
    fun controllerSessionLost() {
        mutableState.update {
            it.copy(controllerSessionEstablished = false, controllerPort = null)
        }
    }

    /** Clears FCC write evidence without manufacturing a controller connection. */
    fun clearWriteEvidence() {
        mutableState.update {
            it.copy(
                lastSuccessfulWriteAtMs = null,
                lastAttemptSucceeded = null,
                lastApplyAttempt = null,
                lastAutomaticApplyAttempt = null
            )
        }
    }
}

internal object FccRuntime {
    val tracker = FccRuntimeTracker()
}
