package com.freefcc.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Process-wide serialization for every controller-/aircraft-facing DUML write.
 *
 * [FccViewModel] (UI-triggered operations) and [FccKeepaliveService] (background
 * re-apply loop) are separate Android components with no shared instance, so the
 * lock lives here as a singleton both can reach. [busy] mirrors who currently
 * holds it, so the UI reflects service writes too.
 */
object HardwareLock {

    private val mutex = Mutex()
    private val _busy = MutableStateFlow(false)
    private val held = java.util.concurrent.atomic.AtomicBoolean(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** Claims the lock for one operation. Returns false if another is already running. */
    fun tryBegin(): Boolean {
        if (!mutex.tryLock()) return false
        held.set(true)
        _busy.value = true
        return true
    }

    /** Releases the lock. Must run in a finally block covering every exit path.
     *  No-op (with a log) if the caller does not own the lock — prevents
     *  [IllegalMonitorStateException] from a stray end() call. */
    fun end() {
        if (!held.get()) return
        held.set(false)
        _busy.value = false
        mutex.unlock()
    }
}
