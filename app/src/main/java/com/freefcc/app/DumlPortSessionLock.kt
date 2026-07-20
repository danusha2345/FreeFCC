package com.freefcc.app

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Serializes long-lived sessions that target the same localhost DUML port. */
internal object DumlPortSessionLock {
    private val heldByPort = ConcurrentHashMap<Int, AtomicBoolean>()

    fun tryBegin(port: Int): Lease? {
        val held = heldByPort.computeIfAbsent(port) { AtomicBoolean(false) }
        return if (held.compareAndSet(false, true)) Lease(port, held) else null
    }

    class Lease internal constructor(
        val port: Int,
        private val held: AtomicBoolean
    ) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) held.set(false)
        }
    }
}
