package com.ivor.kriptex.deliverypolicy.routing

import com.ivor.kriptex.deliverypolicy.protocol.AckMessage
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.protocol.UnknownMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage

enum class ProtocolMessageKind {
    ONE_TO_ONE_USER,
    GROUP_MESSAGE,
    SENDER_KEY_DISTRIBUTION,
    ACK,
    HANDSHAKE,
    UNKNOWN,

    ;

    companion object {
        fun classify(message: ProtocolMessage): ProtocolMessageKind {
            return when (message) {
                is UserMessage -> ONE_TO_ONE_USER
                is SenderKeyGroupMessage -> GROUP_MESSAGE
                is SenderKeyDistributionMessage -> SENDER_KEY_DISTRIBUTION
                is AckMessage -> ACK
                is SessionInitMessage,
                is SessionAcceptMessage,
                -> HANDSHAKE
                is UnknownMessage -> UNKNOWN
                else -> UNKNOWN
            }
        }
    }
}
