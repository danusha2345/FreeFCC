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

/** Process-local evidence. A successful write is not a physical FCC readback. */
internal data class FccRuntimeSnapshot(
    val keepaliveStatus: KeepaliveRuntimeStatus = KeepaliveRuntimeStatus.STOPPED,
    val controllerSessionEstablished: Boolean = false,
    val lastSuccessfulWriteAtMs: Long? = null,
    val lastAttemptSucceeded: Boolean? = null,
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

    fun recordWrite(success: Boolean, timestampMs: Long = System.currentTimeMillis()) {
        mutableState.update {
            it.copy(
                lastSuccessfulWriteAtMs = if (success) timestampMs else it.lastSuccessfulWriteAtMs,
                lastAttemptSucceeded = success
            )
        }
    }

    /** Starts a newly detected controller session with no FCC-state assumption. */
    fun beginHardwareSession() {
        mutableState.update {
            it.copy(
                controllerSessionEstablished = true,
                lastSuccessfulWriteAtMs = null,
                lastAttemptSucceeded = null
            )
        }
    }

    /** Records a failed explicit controller probe without altering FCC history. */
    fun controllerSessionLost() {
        mutableState.update { it.copy(controllerSessionEstablished = false) }
    }

    /** Clears FCC write evidence without manufacturing a controller connection. */
    fun clearWriteEvidence() {
        mutableState.update {
            it.copy(lastSuccessfulWriteAtMs = null, lastAttemptSucceeded = null)
        }
    }
}

internal object FccRuntime {
    val tracker = FccRuntimeTracker()
}
