package com.ivor.kriptex.deliverypolicy.inbound

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.InboundPipelineDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpInboundPipelineDebugTrace
import com.ivor.kriptex.deliverypolicy.ledger.ConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.persistence.PersistedInboundPipelineSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedPendingAck

/**
 * Full inbound pipeline (transport-agnostic, crypto-agnostic).
 *
 * Guarantees:
 * - Deduplicates by messageId
 * - Assigns receiveIndex per conversation
 * - Updates ledger (ACK -> recordAcked, DATA -> recordReceived)
 * - Produces an AckDecision but never sends an ACK
 * - Snapshot/restore is safe: restore never re-delivers or auto-emits ACKs
 */
class InMemoryInboundMessageProcessor(
    private val ledger: ConversationDeliveryLedger,
    private val clock: Clock = MonotonicClock,
    private val debugTrace: InboundPipelineDebugTrace = NoOpInboundPipelineDebugTrace,
) : InboundMessageProcessor {

    private val processed = LinkedHashSet<String>()

    private val nextReceiveIndexByConversation = LinkedHashMap<String, Int>()

    private val receivedIndexByMessageId = LinkedHashMap<String, Int>()
    private val conversationIdByMessageId = LinkedHashMap<String, String>()
    private val kindByMessageId = LinkedHashMap<String, InboundMessage.Kind>()

    private val pendingAcks = LinkedHashMap<String, PendingAck>()

    @Synchronized
    override fun onIncomingMessage(message: InboundMessage): InboundProcessingResult {
        if (processed.contains(message.messageId)) {
            debugTrace.onDeduplicated(message.messageId, message.conversationId, message.receivedAtElapsedMs)
            return InboundProcessingResult.DuplicateIgnored(
                messageId = message.messageId,
                conversationId = message.conversationId,
                receiveIndex = receivedIndexByMessageId[message.messageId],
                ackDecision = AckDecision.NO_ACK,
            )
        }

        processed.add(message.messageId)
        conversationIdByMessageId[message.messageId] = message.conversationId
        kindByMessageId[message.messageId] = message.kind

        debugTrace.onAccepted(message.messageId, message.conversationId, message.kind, message.receivedAtElapsedMs)

        val receiveIndex = assignReceiveIndex(message.messageId, message.conversationId)

        // Ledger integration.
        when (message.kind) {
            InboundMessage.Kind.ACK -> {
                ledger.recordAcked(message.messageId)
                debugTrace.onLedgerUpdated(message.messageId, update = "ACKED", elapsedMs = clock.nowMs())
            }

            InboundMessage.Kind.DATA -> {
                ledger.recordReceived(message.messageId, message.conversationId)
                debugTrace.onLedgerUpdated(message.messageId, update = "RECEIVED", elapsedMs = clock.nowMs())
            }
        }

        val decision = decideAck(message)
        debugTrace.onAckDecision(message.messageId, decision, clock.nowMs())

        if (decision == AckDecision.SEND_ACK) {
            pendingAcks.putIfAbsent(
                message.messageId,
                PendingAck(messageId = message.messageId, conversationId = message.conversationId, receiveIndex = receiveIndex),
            )
        }

        return InboundProcessingResult.Accepted(
            messageId = message.messageId,
            conversationId = message.conversationId,
            receiveIndex = receiveIndex,
            ackDecision = decision,
            shouldDeliver = message.kind == InboundMessage.Kind.DATA,
        )
    }

    @Synchronized
    override fun drainPendingAcks(): List<PendingAck> {
        val acks = pendingAcks.values.toList()
        pendingAcks.clear()
        return acks
    }

    @Synchronized
    override fun snapshot(): PersistedInboundPipelineSnapshot {
        val capturedAt = clock.nowMs()
        debugTrace.onSnapshotBuilt(
            processedCount = processed.size,
            conversationCount = nextReceiveIndexByConversation.size,
            pendingAckCount = pendingAcks.size,
            elapsedMs = capturedAt,
        )
        return PersistedInboundPipelineSnapshot(
            capturedAtElapsedMs = capturedAt,
            processedMessageIds = processed.toList(),
            nextReceiveIndexByConversation = nextReceiveIndexByConversation.toMap(),
            receivedIndexByMessageId = receivedIndexByMessageId.toMap(),
            conversationIdByMessageId = conversationIdByMessageId.toMap(),
            kindByMessageId = kindByMessageId.toMap(),
            pendingAcks = pendingAcks.values.map {
                PersistedPendingAck(it.messageId, it.conversationId, it.receiveIndex)
            },
        )
    }

    @Synchronized
    override fun restore(snapshot: PersistedInboundPipelineSnapshot) {
        processed.clear()
        nextReceiveIndexByConversation.clear()
        receivedIndexByMessageId.clear()
        conversationIdByMessageId.clear()
        kindByMessageId.clear()
        pendingAcks.clear()

        processed.addAll(snapshot.processedMessageIds)
        nextReceiveIndexByConversation.putAll(snapshot.nextReceiveIndexByConversation)
        receivedIndexByMessageId.putAll(snapshot.receivedIndexByMessageId)
        conversationIdByMessageId.putAll(snapshot.conversationIdByMessageId)
        kindByMessageId.putAll(snapshot.kindByMessageId)

        snapshot.pendingAcks.forEach {
            pendingAcks[it.messageId] = PendingAck(it.messageId, it.conversationId, it.receiveIndex)
        }

        val now = clock.nowMs()
        debugTrace.onRestoreApplied(
            processedCount = processed.size,
            conversationCount = nextReceiveIndexByConversation.size,
            pendingAckCount = pendingAcks.size,
            elapsedMs = now,
        )
        // Restore never replays deliveries or emits ACKs.
    }

    @Synchronized
    override fun conversationReceiveView(conversationId: String): List<String> {
        val pairs = conversationIdByMessageId.entries
            .filter { it.value == conversationId }
            .mapNotNull { (messageId, _) ->
                val idx = receivedIndexByMessageId[messageId] ?: return@mapNotNull null
                messageId to idx
            }
            .sortedBy { it.second }
        return pairs.map { it.first }
    }

    private fun assignReceiveIndex(messageId: String, conversationId: String): Int {
        val next = nextReceiveIndexByConversation.getOrDefault(conversationId, 0)
        nextReceiveIndexByConversation[conversationId] = next + 1
        receivedIndexByMessageId[messageId] = next
        debugTrace.onReceiveIndexAssigned(messageId, conversationId, next, clock.nowMs())
        return next
    }

    private fun decideAck(message: InboundMessage): AckDecision {
        return when (message.kind) {
            InboundMessage.Kind.ACK -> AckDecision.NO_ACK
            InboundMessage.Kind.DATA -> AckDecision.SEND_ACK
        }
    }
}
