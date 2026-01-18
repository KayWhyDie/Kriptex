package com.ivor.kriptex.deliverypolicy.routing.handlers

import com.ivor.kriptex.deliverypolicy.protocol.GroupMediaKeyDistributionMessage

interface GroupMediaKeyDistributionHandler {

    data class InboundApplyResult(
        val accepted: Boolean,
        val reason: String? = null,
    )

    fun applyInboundGroupMediaKeyDistribution(
        authenticatedPeerIdentityPublicKey: ByteArray,
        msg: GroupMediaKeyDistributionMessage,
    ): InboundApplyResult
}
