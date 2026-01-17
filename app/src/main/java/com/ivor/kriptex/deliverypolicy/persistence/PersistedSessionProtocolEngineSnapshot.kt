package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot for the session-bound protocol engine.
 *
 * - session store state (including replay windows)
 * - protocol inbound pipeline state (including pending outbound control messages)
 */
data class PersistedSessionProtocolEngineSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val sessionStore: PersistedSessionStoreSnapshot,
    val protocolInbound: PersistedProtocolInboundPipelineSnapshot,
)
