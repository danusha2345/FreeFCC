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
    val lastSuccessfulWriteAtMs: Long? = null,
    val lastAttemptSucceeded: Boolean? = null,
    val error: String? = null
)

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
            it.copy(lastSuccessfulWriteAtMs = null, lastAttemptSucceeded = null)
        }
    }

    fun clearWriteEvidence() = beginHardwareSession()
}

internal object FccRuntime {
    val tracker = FccRuntimeTracker()
}
