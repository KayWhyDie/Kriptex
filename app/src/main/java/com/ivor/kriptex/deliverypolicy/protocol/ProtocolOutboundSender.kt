package com.ivor.kriptex.deliverypolicy.protocol

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.ProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage

/**
 * Real outbound behavior for protocol messages.
 *
 * - Encodes protocol messages into bytes
 * - Enqueues via existing outbox
 * - Does not do networking
 */
class ProtocolOutboundSender(
    private val outbox: MessageOutbox,
    private val encoder: ProtocolEncoder,
    private val clock: Clock = MonotonicClock,
    private val debugTrace: ProtocolDebugTrace = NoOpProtocolDebugTrace,
) {

    fun enqueue(message: ProtocolMessage): EnqueueResult {
        val bytes = encoder.encode(message)
        debugTrace.onEncode(message.messageId, message.conversationId, message.type, clock.nowMs(), bytes.size)

        val result = outbox.enqueue(
            OutgoingMessage(
                messageId = message.messageId,
                chatId = message.conversationId,
                payload = bytes,
                enqueueElapsedMs = clock.nowMs(),
            ),
        )

        if (result == EnqueueResult.Enqueued) {
            debugTrace.onOutboundEnqueued(message.messageId, message.conversationId, message.type, clock.nowMs())
        }

        return result
    }

    fun enqueueAll(messages: List<ProtocolMessage>): List<Pair<ProtocolMessage, EnqueueResult>> {
        return messages.map { it to enqueue(it) }
    }
}
