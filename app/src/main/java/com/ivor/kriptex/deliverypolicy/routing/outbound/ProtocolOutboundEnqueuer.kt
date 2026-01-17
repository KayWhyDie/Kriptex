package com.ivor.kriptex.deliverypolicy.routing.outbound

import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage

interface ProtocolOutboundEnqueuer {
    fun enqueue(peerId: String, message: ProtocolMessage): EnqueueResult
}
