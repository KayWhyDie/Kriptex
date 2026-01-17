package com.ivor.kriptex.deliverypolicy.inbound

import com.ivor.kriptex.deliverypolicy.persistence.PersistedInboundPipelineSnapshot

interface InboundMessageProcessor {
    fun onIncomingMessage(message: InboundMessage): InboundProcessingResult

    /** Returns ACKs the caller may choose to emit; does not send anything. */
    fun drainPendingAcks(): List<PendingAck>

    fun snapshot(): PersistedInboundPipelineSnapshot

    fun restore(snapshot: PersistedInboundPipelineSnapshot)

    /** For debugging/tests: ordered receive view for a conversation. */
    fun conversationReceiveView(conversationId: String): List<String>
}

data class PendingAck(
    val messageId: String,
    val conversationId: String,
    val receiveIndex: Int,
)
