package com.ivor.kriptex.deliverypolicy.routing.adapters

import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.SenderKeyDistributionEngine
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyDistributionHandler

class SenderKeyDistributionEngineAdapter(private val engine: SenderKeyDistributionEngine) : SenderKeyDistributionHandler {
    override fun applyInboundDistribution(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyDistributionMessage): SenderKeyDistributionHandler.InboundApplyResult {
        val r = engine.applyInboundDistribution(authenticatedPeerIdentityPublicKey, msg)
        return SenderKeyDistributionHandler.InboundApplyResult(accepted = r.accepted, reason = r.reason)
    }

    override fun onInboundAck(authenticatedPeerIdentityPublicKey: ByteArray, ackedMessageId: String) {
        engine.onInboundAck(authenticatedPeerIdentityPublicKey, ackedMessageId)
    }
}
