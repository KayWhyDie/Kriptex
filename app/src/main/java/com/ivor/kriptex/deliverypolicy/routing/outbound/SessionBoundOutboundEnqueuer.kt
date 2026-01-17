package com.ivor.kriptex.deliverypolicy.routing.outbound

import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.session.SessionBoundProtocolOutbound

class SessionBoundOutboundEnqueuer(private val outbound: SessionBoundProtocolOutbound) : ProtocolOutboundEnqueuer {
    override fun enqueue(peerId: String, message: ProtocolMessage): EnqueueResult = outbound.enqueue(peerId, message)
}
