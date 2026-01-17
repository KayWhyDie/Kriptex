package com.ivor.kriptex.deliverypolicy.routing.adapters

import com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane.SenderKeyGroupMessageEngine
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyGroupMessageHandler

class SenderKeyGroupMessageEngineAdapter(private val engine: SenderKeyGroupMessageEngine) : SenderKeyGroupMessageHandler {
    override fun decryptInbound(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyGroupMessage): SenderKeyGroupMessageHandler.InboundDecision {
        return when (val r = engine.decryptInbound(authenticatedPeerIdentityPublicKey, msg)) {
            is SenderKeyGroupMessageEngine.InboundDecision.Accepted -> SenderKeyGroupMessageHandler.InboundDecision.Accepted(r.userMessage)
            is SenderKeyGroupMessageEngine.InboundDecision.Rejected -> SenderKeyGroupMessageHandler.InboundDecision.Rejected(r.reason)
        }
    }
}
