package com.ivor.kriptex.deliverypolicy.routing.adapters

import com.ivor.kriptex.deliverypolicy.group.senderkey.media.GroupMediaKeyDistributionEngine
import com.ivor.kriptex.deliverypolicy.protocol.GroupMediaKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.routing.handlers.GroupMediaKeyDistributionHandler

class GroupMediaKeyDistributionEngineAdapter(
    private val engine: GroupMediaKeyDistributionEngine,
) : GroupMediaKeyDistributionHandler {

    override fun applyInboundGroupMediaKeyDistribution(
        authenticatedPeerIdentityPublicKey: ByteArray,
        msg: GroupMediaKeyDistributionMessage,
    ): GroupMediaKeyDistributionHandler.InboundApplyResult {
        return when (val r = engine.applyInbound(authenticatedPeerIdentityPublicKey, msg)) {
            is GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted -> {
                GroupMediaKeyDistributionHandler.InboundApplyResult(accepted = true)
            }

            is GroupMediaKeyDistributionEngine.InboundApplyResult.Rejected -> {
                GroupMediaKeyDistributionHandler.InboundApplyResult(accepted = false, reason = r.reason)
            }
        }
    }
}
