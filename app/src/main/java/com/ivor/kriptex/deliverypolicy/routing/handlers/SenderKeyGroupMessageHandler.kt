package com.ivor.kriptex.deliverypolicy.routing.handlers

import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage

interface SenderKeyGroupMessageHandler {

    sealed interface InboundDecision {
        data class Accepted(val userMessage: UserMessage) : InboundDecision
        data class Rejected(val reason: String) : InboundDecision
    }

    fun decryptInbound(authenticatedPeerIdentityPublicKey: ByteArray, msg: SenderKeyGroupMessage): InboundDecision
}
