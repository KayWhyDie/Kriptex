package com.ivor.kriptex.deliverypolicy.active

import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession

/**
 * Tracks open delivery sessions so higher layers can make deterministic decisions
 * (e.g., downgrade handling) without knowing about transport.
 */
interface OpenSessionRegistry {
    val size: Int

    fun register(session: DeliverySession)

    fun unregister(sessionId: String)

    fun snapshot(): List<DeliverySession>

    /** Completes all currently open sessions as deferred. */
    fun completeAllDeferred(reason: String)
}

object NoOpOpenSessionRegistry : OpenSessionRegistry {
    override val size: Int = 0
    override fun register(session: DeliverySession) = Unit
    override fun unregister(sessionId: String) = Unit
    override fun snapshot(): List<DeliverySession> = emptyList()
    override fun completeAllDeferred(reason: String) = Unit
}

class InMemoryOpenSessionRegistry : OpenSessionRegistry {
    private val sessions = LinkedHashMap<String, DeliverySession>()

    override val size: Int
        @Synchronized get() = sessions.size

    @Synchronized
    override fun register(session: DeliverySession) {
        sessions[session.sessionId] = session
    }

    @Synchronized
    override fun unregister(sessionId: String) {
        sessions.remove(sessionId)
    }

    @Synchronized
    override fun snapshot(): List<DeliverySession> = sessions.values.toList()

    override fun completeAllDeferred(reason: String) {
        // Snapshot first so completion can mutate the registry safely.
        val open = snapshot()
        open.forEach { it.completeDeferred(reason) }
    }
}
