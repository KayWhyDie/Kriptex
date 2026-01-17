package com.ivor.kriptex.deliverypolicy.passivebuffer

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.MonotonicClock
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpPassiveDeliveryBufferDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpPassiveDeliveryBufferPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.PassiveDeliveryBufferDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.PassiveDeliveryBufferPersistenceDebugTrace
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.outbox.session.DeliverySession
import com.ivor.kriptex.deliverypolicy.persistence.PersistedBufferedMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedPassiveDeliveryBufferSnapshot

class InMemoryPassiveDeliveryBuffer(
    private val clock: Clock = MonotonicClock,
    private val debugTrace: PassiveDeliveryBufferDebugTrace = NoOpPassiveDeliveryBufferDebugTrace,
    private val persistenceDebugTrace: PassiveDeliveryBufferPersistenceDebugTrace = NoOpPassiveDeliveryBufferPersistenceDebugTrace,
) : PassiveDeliveryBuffer {

    private data class Entry(
        val messageId: String,
        val payload: ByteArray,
        val enqueueElapsedMs: Long,
        val sessionId: String,
    )

    private var available: Boolean = false
    private val entries = LinkedHashMap<String, Entry>()

    override val size: Int
        get() = entries.size

    override fun markAvailable() {
        available = true
        debugTrace.onAvailableChanged(isAvailable = true, size = size, elapsedMs = clock.nowMs())
    }

    override fun markUnavailable() {
        available = false
        debugTrace.onAvailableChanged(isAvailable = false, size = size, elapsedMs = clock.nowMs())
    }

    override fun enqueue(message: OutgoingMessage, session: DeliverySession): BufferEnqueueResult {
        if (entries.containsKey(message.messageId)) {
            return BufferEnqueueResult.AlreadyBuffered
        }

        entries[message.messageId] = Entry(
            messageId = message.messageId,
            payload = message.payload,
            enqueueElapsedMs = message.enqueueElapsedMs,
            sessionId = session.sessionId,
        )

        debugTrace.onBuffered(
            messageId = message.messageId,
            sessionId = session.sessionId,
            size = size,
            elapsedMs = clock.nowMs(),
        )

        return BufferEnqueueResult.Enqueued
    }

    override fun drainReady(): List<BufferedMessage> {
        if (!available) return emptyList()
        if (entries.isEmpty()) return emptyList()

        val drained = entries.values.map {
            debugTrace.onSessionResumePlanned(it.messageId, it.sessionId, clock.nowMs())
            BufferedMessage(
                messageId = it.messageId,
                payload = it.payload,
                enqueueElapsedMs = it.enqueueElapsedMs,
                sessionId = it.sessionId,
            )
        }

        entries.clear()
        debugTrace.onDrain(count = drained.size, sizeAfter = size, elapsedMs = clock.nowMs())
        return drained
    }

    override fun snapshot(): PersistedPassiveDeliveryBufferSnapshot {
        val capturedAt = clock.nowMs()
        val messages = entries.values.map {
            PersistedBufferedMessage(
                messageId = it.messageId,
                payload = it.payload.copyOf(),
                enqueueElapsedMs = it.enqueueElapsedMs,
            )
        }
        persistenceDebugTrace.onSnapshotBuilt(messageCount = messages.size, capturedAtElapsedMs = capturedAt)
        return PersistedPassiveDeliveryBufferSnapshot(
            capturedAtElapsedMs = capturedAt,
            messages = messages,
        )
    }

    override fun restore(snapshot: PersistedPassiveDeliveryBufferSnapshot) {
        // After process death we must not assume availability.
        available = false
        entries.clear()

        // Dedupe by messageId.
        var dropped = 0
        snapshot.messages.forEach { m ->
            if (entries.containsKey(m.messageId)) {
                dropped++
                return@forEach
            }
            entries[m.messageId] = Entry(
                messageId = m.messageId,
                payload = m.payload.copyOf(),
                enqueueElapsedMs = m.enqueueElapsedMs,
                // Runtime-only session identity cannot be restored; use a synthetic id.
                sessionId = "restored:${m.messageId}",
            )
        }

        val now = clock.nowMs()
        if (dropped > 0) persistenceDebugTrace.onRestoreDeduplicated(droppedCount = dropped, capturedAtElapsedMs = now)
        persistenceDebugTrace.onRestoreApplied(messageCount = size, capturedAtElapsedMs = now)
    }
}
