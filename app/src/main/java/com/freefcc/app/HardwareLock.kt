package com.freefcc.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicBoolean

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
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * An owner-bound, idempotent lock lease. Only the lease returned by
     * [tryBegin] can release its acquisition, so one component cannot
     * accidentally unlock another component's hardware operation.
     */
    class Lease internal constructor(private val owner: Any) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release(owner)
            }
        }
    }

    /** Claims the lock for one operation, or returns null if it is already held. */
    fun tryBegin(): Lease? {
        val owner = Any()
        if (!mutex.tryLock(owner)) return null
        _busy.value = true
        return Lease(owner)
    }

    private fun release(owner: Any) {
        _busy.value = false
        mutex.unlock(owner)
    }
}
