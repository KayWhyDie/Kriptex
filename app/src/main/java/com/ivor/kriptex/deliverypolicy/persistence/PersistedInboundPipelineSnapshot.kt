package com.ivor.kriptex.deliverypolicy.persistence

import com.ivor.kriptex.deliverypolicy.inbound.InboundMessage

/**
 * Persistable snapshot of inbound pipeline state.
 *
 * Pure state only:
 * - processed messageIds (dedupe)
 * - per-conversation receive ordering indices
 * - pending ACK decisions (not auto-emitted)
 */
data class PersistedInboundPipelineSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val processedMessageIds: List<String>,
    val nextReceiveIndexByConversation: Map<String, Int>,
    val receivedIndexByMessageId: Map<String, Int>,
    val conversationIdByMessageId: Map<String, String>,
    val kindByMessageId: Map<String, InboundMessage.Kind>,
    val pendingAcks: List<PersistedPendingAck>,
)

data class PersistedPendingAck(
    val messageId: String,
    val conversationId: String,
    val receiveIndex: Int,
)
