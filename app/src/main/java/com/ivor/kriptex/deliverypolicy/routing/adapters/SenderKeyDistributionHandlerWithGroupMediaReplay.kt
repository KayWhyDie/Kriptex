package com.ivor.kriptex.deliverypolicy.routing.adapters

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyDistributionHandler

/**
 * Decorator that triggers a callback when a sender key distribution is accepted.
 *
 * Intended use: replay buffered [com.ivor.kriptex.deliverypolicy.protocol.GroupMediaKeyDistributionMessage]
 * items that arrived before the corresponding sender key distribution.
 */
class SenderKeyDistributionHandlerWithGroupMediaReplay(
    private val delegate: SenderKeyDistributionHandler,
    private val onSenderKeyAvailable: (groupId: GroupId, senderIdentityPublicKey: ByteArray) -> Unit,
) : SenderKeyDistributionHandler {

    override fun applyInboundDistribution(
        authenticatedPeerIdentityPublicKey: ByteArray,
        msg: SenderKeyDistributionMessage,
    ): SenderKeyDistributionHandler.InboundApplyResult {
        val r = delegate.applyInboundDistribution(authenticatedPeerIdentityPublicKey, msg)
        if (r.accepted) {
            onSenderKeyAvailable(GroupId(msg.groupId), authenticatedPeerIdentityPublicKey)
        }
        return r
    }

    override fun onInboundAck(authenticatedPeerIdentityPublicKey: ByteArray, ackedMessageId: String) {
        delegate.onInboundAck(authenticatedPeerIdentityPublicKey, ackedMessageId)
    }
}
