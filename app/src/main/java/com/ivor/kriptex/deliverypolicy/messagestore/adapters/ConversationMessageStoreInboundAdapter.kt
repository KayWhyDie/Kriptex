package com.ivor.kriptex.deliverypolicy.messagestore.adapters

import com.ivor.kriptex.deliverypolicy.inbound.InboundMessage
import com.ivor.kriptex.deliverypolicy.inbound.InboundMessageProcessor
import com.ivor.kriptex.deliverypolicy.inbound.InboundProcessingResult
import com.ivor.kriptex.deliverypolicy.inbound.PendingAck
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessageStore
import com.ivor.kriptex.deliverypolicy.persistence.PersistedInboundPipelineSnapshot

/**
 * Explicit, injectable integration:
 * - delegates to an underlying [InboundMessageProcessor]
 * - mirrors inbound accept/dup decisions into [ConversationMessageStore]
 */
class ConversationMessageStoreInboundAdapter(
    private val delegate: InboundMessageProcessor,
    private val store: ConversationMessageStore,
) : InboundMessageProcessor {

    override fun onIncomingMessage(message: InboundMessage): InboundProcessingResult {
        val result = delegate.onIncomingMessage(message)

        when (result) {
            is InboundProcessingResult.Accepted -> {
                if (message.kind == InboundMessage.Kind.DATA) {
                    store.appendInbound(
                        messageId = message.messageId,
                        conversationId = message.conversationId,
                        payload = message.payload,
                        elapsedMs = message.receivedAtElapsedMs,
                    )
                } else {
                    // ACKs target an outbound messageId.
                    store.markAcked(message.messageId, elapsedMs = message.receivedAtElapsedMs)
                }
            }

            is InboundProcessingResult.DuplicateIgnored -> {
                // No-op: store is authoritative and also de-dupes by messageId.
            }
        }

        return result
    }

    override fun drainPendingAcks(): List<PendingAck> = delegate.drainPendingAcks()

    override fun snapshot(): PersistedInboundPipelineSnapshot = delegate.snapshot()

    override fun restore(snapshot: PersistedInboundPipelineSnapshot) = delegate.restore(snapshot)

    override fun conversationReceiveView(conversationId: String): List<String> = delegate.conversationReceiveView(conversationId)
}
