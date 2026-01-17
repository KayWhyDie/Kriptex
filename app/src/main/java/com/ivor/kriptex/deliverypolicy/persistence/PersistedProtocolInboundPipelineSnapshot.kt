package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot for the protocol inbound pipeline.
 *
 * Stores protocol messages as encoded bytes (no special casing ACKs).
 */
data class PersistedProtocolInboundPipelineSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val processedMessageIds: List<String>,
    val nextReceiveIndexByConversation: Map<String, Int>,
    val receivedIndexByMessageId: Map<String, Int>,
    val conversationIdByMessageId: Map<String, String>,
    val typeByMessageId: Map<String, String>,
    val pendingOutboundEncodedMessages: List<ByteArray>,
)
