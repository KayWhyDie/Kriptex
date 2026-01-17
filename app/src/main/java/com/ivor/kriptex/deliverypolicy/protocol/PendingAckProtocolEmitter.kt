package com.ivor.kriptex.deliverypolicy.protocol

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.inbound.InboundMessageProcessor

/**
 * Bridges existing inbound pipeline ACK decisions into first-class protocol ACK messages.
 *
 * This keeps backward compatibility while allowing ACK control messages to flow
 * through the same outbox + delivery pipeline as user messages.
 */
class PendingAckProtocolEmitter(
    private val inbound: InboundMessageProcessor,
    private val messageIdGenerator: MessageIdGenerator,
    private val clock: Clock = MonotonicClock,
) {

    fun drainAckMessages(): List<AckMessage> {
        val pending = inbound.drainPendingAcks()
        val now = clock.nowMs()
        return pending.map {
            AckMessage(
                messageId = messageIdGenerator.nextId(),
                conversationId = it.conversationId,
                createdAtElapsedMs = now,
                ackedMessageId = it.messageId,
            )
        }
    }
}
