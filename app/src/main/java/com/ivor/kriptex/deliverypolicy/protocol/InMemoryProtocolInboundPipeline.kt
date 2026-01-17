package com.ivor.kriptex.deliverypolicy.protocol

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.ProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.ledger.ConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessageStore
import com.ivor.kriptex.deliverypolicy.persistence.PersistedProtocolInboundPipelineSnapshot

/**
 * Full protocol-level inbound pipeline.
 *
 * Responsibilities:
 * - Decode bytes into [ProtocolMessage]
 * - Deduplicate by ProtocolMessage.messageId
 * - Assign receiveIndex per conversation
 * - Update ledger + message store (including ACK effects)
 * - Materialize ACK decisions into outbound [AckMessage] instances
 * - Snapshot/restore is safe (restore does not emit or enqueue automatically)
 */
class InMemoryProtocolInboundPipeline(
    private val decoder: ProtocolDecoder,
    private val encoder: ProtocolEncoder,
    private val messageIdGenerator: MessageIdGenerator,
    private val ledger: ConversationDeliveryLedger,
    private val messageStore: ConversationMessageStore,
    private val clock: Clock = MonotonicClock,
    private val debugTrace: ProtocolDebugTrace = NoOpProtocolDebugTrace,
) : ProtocolInboundPipeline {

    private val processed = LinkedHashSet<String>()
    private val nextReceiveIndexByConversation = LinkedHashMap<String, Int>()
    private val receivedIndexByMessageId = LinkedHashMap<String, Int>()
    private val conversationIdByMessageId = LinkedHashMap<String, String>()
    private val typeByMessageId = LinkedHashMap<String, String>()

    // Encoded outbound protocol messages pending send.
    private val pendingOutboundEncoded = ArrayList<ByteArray>()

    @Synchronized
    override fun onInboundBytes(bytes: ByteArray, receivedAtElapsedMs: Long, senderId: String): ProtocolInboundResult {
        val decoded = decoder.decode(bytes)
        debugTrace.onDecode(decoded.messageId, decoded.conversationId, decoded.type, clock.nowMs(), bytes.size)

        if (processed.contains(decoded.messageId)) {
            debugTrace.onInboundDeduplicated(decoded.messageId, decoded.conversationId, receivedAtElapsedMs)
            return ProtocolInboundResult.DuplicateIgnored(
                messageId = decoded.messageId,
                conversationId = decoded.conversationId,
                receiveIndex = receivedIndexByMessageId[decoded.messageId],
                type = decoded.type,
            )
        }

        processed.add(decoded.messageId)
        conversationIdByMessageId[decoded.messageId] = decoded.conversationId
        typeByMessageId[decoded.messageId] = decoded.type.name

        val receiveIndex = assignReceiveIndex(decoded.messageId, decoded.conversationId)
        debugTrace.onInboundAccepted(decoded.messageId, decoded.conversationId, decoded.type, receiveIndex, receivedAtElapsedMs)

        // Store the inbound protocol message as-is (encoded bytes).
        messageStore.appendInbound(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            payload = bytes,
            elapsedMs = receivedAtElapsedMs,
        )

        // Apply control behavior.
        when (decoded) {
            is UserMessage -> {
                ledger.recordReceived(decoded.messageId, decoded.conversationId)

                // Materialize ACK as a protocol message (outbound), but do not send here.
                val ack = AckMessage(
                    messageId = messageIdGenerator.nextId(),
                    conversationId = decoded.conversationId,
                    createdAtElapsedMs = clock.nowMs(),
                    ackedMessageId = decoded.messageId,
                )
                val encodedAck = encoder.encode(ack)
                debugTrace.onEncode(ack.messageId, ack.conversationId, ack.type, clock.nowMs(), encodedAck.size)
                pendingOutboundEncoded.add(encodedAck)
                debugTrace.onOutboundPending(ack.messageId, ack.conversationId, ack.type, clock.nowMs())
            }

            is SenderKeyDistributionMessage -> {
                ledger.recordReceived(decoded.messageId, decoded.conversationId)

                val ack = AckMessage(
                    messageId = messageIdGenerator.nextId(),
                    conversationId = decoded.conversationId,
                    createdAtElapsedMs = clock.nowMs(),
                    ackedMessageId = decoded.messageId,
                )
                val encodedAck = encoder.encode(ack)
                debugTrace.onEncode(ack.messageId, ack.conversationId, ack.type, clock.nowMs(), encodedAck.size)
                pendingOutboundEncoded.add(encodedAck)
                debugTrace.onOutboundPending(ack.messageId, ack.conversationId, ack.type, clock.nowMs())
            }

            is AckMessage -> {
                // ACK affects the acked message, not this messageId.
                ledger.recordAcked(decoded.ackedMessageId)
                messageStore.markAcked(decoded.ackedMessageId, elapsedMs = receivedAtElapsedMs)
            }

            is UnknownMessage -> {
                // Unknown messages are stored and deduped, but have no control effects.
            }

            else -> Unit
        }

        return ProtocolInboundResult.Accepted(
            messageId = decoded.messageId,
            conversationId = decoded.conversationId,
            receiveIndex = receiveIndex,
            type = decoded.type,
        )
    }

    @Synchronized
    override fun drainPendingOutbound(): List<ProtocolMessage> {
        val out = pendingOutboundEncoded.map { decoder.decode(it) }
        pendingOutboundEncoded.clear()
        return out
    }

    @Synchronized
    override fun snapshot(): PersistedProtocolInboundPipelineSnapshot {
        val capturedAt = clock.nowMs()
        debugTrace.onSnapshotBuilt(processed.size, pendingOutboundEncoded.size, capturedAt)
        return PersistedProtocolInboundPipelineSnapshot(
            capturedAtElapsedMs = capturedAt,
            processedMessageIds = processed.toList(),
            nextReceiveIndexByConversation = nextReceiveIndexByConversation.toMap(),
            receivedIndexByMessageId = receivedIndexByMessageId.toMap(),
            conversationIdByMessageId = conversationIdByMessageId.toMap(),
            typeByMessageId = typeByMessageId.toMap(),
            pendingOutboundEncodedMessages = pendingOutboundEncoded.toList(),
        )
    }

    @Synchronized
    override fun restore(snapshot: PersistedProtocolInboundPipelineSnapshot) {
        processed.clear()
        nextReceiveIndexByConversation.clear()
        receivedIndexByMessageId.clear()
        conversationIdByMessageId.clear()
        typeByMessageId.clear()
        pendingOutboundEncoded.clear()

        processed.addAll(snapshot.processedMessageIds)
        nextReceiveIndexByConversation.putAll(snapshot.nextReceiveIndexByConversation)
        receivedIndexByMessageId.putAll(snapshot.receivedIndexByMessageId)
        conversationIdByMessageId.putAll(snapshot.conversationIdByMessageId)
        typeByMessageId.putAll(snapshot.typeByMessageId)
        pendingOutboundEncoded.addAll(snapshot.pendingOutboundEncodedMessages)

        val now = clock.nowMs()
        debugTrace.onRestoreApplied(processed.size, pendingOutboundEncoded.size, now)
        // Restore must not auto-enqueue.
    }

    private fun assignReceiveIndex(messageId: String, conversationId: String): Int {
        val next = nextReceiveIndexByConversation.getOrDefault(conversationId, 0)
        nextReceiveIndexByConversation[conversationId] = next + 1
        receivedIndexByMessageId[messageId] = next
        return next
    }
}
