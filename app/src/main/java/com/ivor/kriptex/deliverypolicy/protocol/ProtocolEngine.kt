package com.ivor.kriptex.deliverypolicy.protocol

import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.ProtocolDebugTrace
import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult

/**
 * Protocol-level orchestration.
 *
 * Provides a single entrypoint to:
 * - accept inbound bytes
 * - drain pending outbound control messages (ACKs)
 * - enqueue them via outbox
 *
 * Important: restore is safe because this engine never auto-flushes pending outbound
 * during restore; callers must invoke [flushPendingOutbound] explicitly.
 */
class ProtocolEngine(
    private val inbound: ProtocolInboundPipeline,
    private val outbound: ProtocolOutboundSender,
    private val debugTrace: ProtocolDebugTrace = NoOpProtocolDebugTrace,
) {

    data class InboundOutcome(
        val inbound: ProtocolInboundResult,
        val enqueuedOutbound: List<Pair<ProtocolMessage, EnqueueResult>>, // may be empty
    )

    fun onInboundBytes(
        bytes: ByteArray,
        receivedAtElapsedMs: Long,
        senderId: String,
        autoEnqueuePendingOutbound: Boolean = true,
    ): InboundOutcome {
        val inboundResult = inbound.onInboundBytes(bytes, receivedAtElapsedMs, senderId)
        val enqueued = if (autoEnqueuePendingOutbound) flushPendingOutbound() else emptyList()
        return InboundOutcome(inbound = inboundResult, enqueuedOutbound = enqueued)
    }

    fun flushPendingOutbound(): List<Pair<ProtocolMessage, EnqueueResult>> {
        val pending = inbound.drainPendingOutbound()
        return outbound.enqueueAll(pending)
    }

    fun dumpDebugReport(): String = debugTrace.dumpDebugReport()
}
