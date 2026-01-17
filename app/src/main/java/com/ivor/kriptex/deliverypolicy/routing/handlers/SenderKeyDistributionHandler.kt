package com.ivor.kriptex.deliverypolicy.routing.handlers

import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage

interface SenderKeyDistributionHandler {

    data class InboundApplyResult(
        val accepted: Boolean,
        val reason: String? = null,
    )

    fun applyInboundDistribution(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyDistributionMessage): InboundApplyResult

    fun onInboundAck(authenticatedPeerIdentityPublicKey: ByteArray, ackedMessageId: String)
}
