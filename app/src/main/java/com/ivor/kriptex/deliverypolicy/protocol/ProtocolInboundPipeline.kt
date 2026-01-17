package com.ivor.kriptex.deliverypolicy.protocol

import com.ivor.kriptex.deliverypolicy.persistence.PersistedProtocolInboundPipelineSnapshot

sealed interface ProtocolInboundResult {
    data class Accepted(
        val messageId: String,
        val conversationId: String,
        val receiveIndex: Int,
        val type: ProtocolMessage.Type,
    ) : ProtocolInboundResult

    data class DuplicateIgnored(
        val messageId: String,
        val conversationId: String,
        val receiveIndex: Int?,
        val type: ProtocolMessage.Type?,
    ) : ProtocolInboundResult
}

interface ProtocolInboundPipeline {

    fun onInboundBytes(bytes: ByteArray, receivedAtElapsedMs: Long, senderId: String): ProtocolInboundResult

    /**
     * Drains outbound protocol messages that should be sent as a result of inbound processing.
     *
     * This is where ACK decisions materialize into real [AckMessage] instances.
     */
    fun drainPendingOutbound(): List<ProtocolMessage>

    fun snapshot(): PersistedProtocolInboundPipelineSnapshot

    fun restore(snapshot: PersistedProtocolInboundPipelineSnapshot)
}
