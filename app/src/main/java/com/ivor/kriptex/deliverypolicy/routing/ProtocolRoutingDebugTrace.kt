package com.ivor.kriptex.deliverypolicy.routing

import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage

interface ProtocolRoutingDebugTrace {
    fun onClassified(messageId: String, conversationId: String, type: ProtocolMessage.Type, kind: ProtocolMessageKind)

    fun onRouted(messageId: String, conversationId: String, kind: ProtocolMessageKind, decision: RoutingDecision, target: String)

    fun onRejected(messageId: String, conversationId: String, kind: ProtocolMessageKind, reason: String)
}

enum class RoutingDecision {
    ACCEPTED,
    REJECTED,
}

object NoOpProtocolRoutingDebugTrace : ProtocolRoutingDebugTrace {
    override fun onClassified(messageId: String, conversationId: String, type: ProtocolMessage.Type, kind: ProtocolMessageKind) = Unit
    override fun onRouted(messageId: String, conversationId: String, kind: ProtocolMessageKind, decision: RoutingDecision, target: String) = Unit
    override fun onRejected(messageId: String, conversationId: String, kind: ProtocolMessageKind, reason: String) = Unit
}
