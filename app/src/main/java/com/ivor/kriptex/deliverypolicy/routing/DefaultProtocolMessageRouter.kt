package com.ivor.kriptex.deliverypolicy.routing

import com.ivor.kriptex.deliverypolicy.outbox.EnqueueResult
import com.ivor.kriptex.deliverypolicy.protocol.AckMessage
import com.ivor.kriptex.deliverypolicy.protocol.GroupMediaKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolEncoder
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundPipeline
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolInboundResult
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAcceptMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionInitMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage
import com.ivor.kriptex.deliverypolicy.routing.outbound.ProtocolOutboundEnqueuer
import com.ivor.kriptex.deliverypolicy.routing.handlers.GroupMediaKeyDistributionHandler
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyDistributionHandler
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyGroupMessageHandler

class DefaultProtocolMessageRouter(
    private val encoder: ProtocolEncoder,
    private val inbound: ProtocolInboundPipeline,
    private val outbound: ProtocolOutboundEnqueuer,
    private val handshakeHandler: ProtocolHandshakeHandler,
    private val senderKeyDistributionHandler: SenderKeyDistributionHandler? = null,
    private val senderKeyGroupMessageHandler: SenderKeyGroupMessageHandler? = null,
    private val groupMediaKeyDistributionHandler: GroupMediaKeyDistributionHandler? = null,
    private val debugTrace: ProtocolRoutingDebugTrace = NoOpProtocolRoutingDebugTrace,
) : ProtocolMessageRouter {

    override fun route(message: ProtocolMessage, context: RoutingContext): RoutingResult {
        val kind = ProtocolMessageKind.classify(message)
        debugTrace.onClassified(message.messageId, message.conversationId, message.type, kind)

        return when (kind) {
            ProtocolMessageKind.HANDSHAKE -> routeHandshake(message, kind, context)
            ProtocolMessageKind.ACK -> routeAck(message as AckMessage, kind, context)
            ProtocolMessageKind.ONE_TO_ONE_USER -> routeUser(message as UserMessage, kind, context)
            ProtocolMessageKind.SENDER_KEY_DISTRIBUTION -> routeDistribution(message as SenderKeyDistributionMessage, kind, context)
            ProtocolMessageKind.GROUP_MESSAGE -> routeGroup(message as SenderKeyGroupMessage, kind, context)
            ProtocolMessageKind.GROUP_MEDIA_KEY_DISTRIBUTION -> routeGroupMediaKeyDistribution(message as GroupMediaKeyDistributionMessage, kind, context)
            ProtocolMessageKind.UNKNOWN -> reject(message, kind, "unknown_message_type")
        }
    }

    private fun routeGroupMediaKeyDistribution(
        message: GroupMediaKeyDistributionMessage,
        kind: ProtocolMessageKind,
        context: RoutingContext,
    ): RoutingResult {
        if (!context.isSessionEnveloped) return reject(message, kind, "group_media_distribution_must_be_session_enveloped")
        val engine = groupMediaKeyDistributionHandler ?: return reject(message, kind, "group_media_distribution_engine_missing")
        val authenticated = context.authenticatedPeerIdentityPublicKey ?: return reject(message, kind, "missing_authenticated_peer")

        val decision = engine.applyInboundGroupMediaKeyDistribution(
            authenticatedPeerIdentityPublicKey = authenticated,
            msg = message,
        )
        if (!decision.accepted) return reject(message, kind, decision.reason ?: "group_media_distribution_rejected")

        val bytes = encoder.encode(message)
        val inboundResult = inbound.onInboundBytes(bytes, context.receivedAtElapsedMs, context.senderId)
        val pending = if (context.isRestore) emptyList() else inbound.drainPendingOutbound()

        val enqueued = enqueueOutbound(pending, context)
        debugTrace.onRouted(message.messageId, message.conversationId, kind, RoutingDecision.ACCEPTED, "group_media_distribution")
        return RoutingResult.Accepted(kind = kind, inbound = inboundResult, enqueuedOutbound = enqueued, target = "group_media_distribution")
    }

    private fun routeHandshake(message: ProtocolMessage, kind: ProtocolMessageKind, context: RoutingContext): RoutingResult {
        if (context.isSessionEnveloped) return reject(message, kind, "handshake_must_be_raw")
        if (message !is SessionInitMessage && message !is SessionAcceptMessage) return reject(message, kind, "not_a_handshake")

        val result = handshakeHandler.handleHandshake(message, context)
        return when (result) {
            is HandshakeResult.Rejected -> reject(message, kind, result.reason)
            is HandshakeResult.Accepted -> {
                val inboundResult = inbound.onInboundBytes(result.inboundBytesToStore, context.receivedAtElapsedMs, context.senderId)
                val outboundMsgs = ArrayList<ProtocolMessage>(1)
                if (!context.isRestore && result.outboundToSend != null) {
                    val enqueue = outbound.enqueue(context.peerId, result.outboundToSend)
                    if (enqueue == EnqueueResult.Enqueued) {
                        outboundMsgs.add(result.outboundToSend)
                    }
                }
                debugTrace.onRouted(message.messageId, message.conversationId, kind, RoutingDecision.ACCEPTED, "handshake")
                RoutingResult.Accepted(kind = kind, inbound = inboundResult, enqueuedOutbound = outboundMsgs, target = "handshake")
            }
        }
    }

    private fun routeAck(message: AckMessage, kind: ProtocolMessageKind, context: RoutingContext): RoutingResult {
        if (!context.isSessionEnveloped) return reject(message, kind, "ack_must_be_session_enveloped")

        // Distribution layer needs to observe ACKs for its pending distributions.
        if (senderKeyDistributionHandler != null && context.authenticatedPeerIdentityPublicKey != null) {
            senderKeyDistributionHandler.onInboundAck(
                authenticatedPeerIdentityPublicKey = context.authenticatedPeerIdentityPublicKey,
                ackedMessageId = message.ackedMessageId,
            )
        }

        val bytes = encoder.encode(message)
        val inboundResult = inbound.onInboundBytes(bytes, context.receivedAtElapsedMs, context.senderId)
        debugTrace.onRouted(message.messageId, message.conversationId, kind, RoutingDecision.ACCEPTED, "ack_handler")
        return RoutingResult.Accepted(kind = kind, inbound = inboundResult, enqueuedOutbound = emptyList(), target = "ack_handler")
    }

    private fun routeUser(message: UserMessage, kind: ProtocolMessageKind, context: RoutingContext): RoutingResult {
        if (!context.isSessionEnveloped) return reject(message, kind, "user_must_be_session_enveloped")

        val bytes = encoder.encode(message)
        val inboundResult = inbound.onInboundBytes(bytes, context.receivedAtElapsedMs, context.senderId)
        val pending = if (context.isRestore) emptyList() else inbound.drainPendingOutbound()

        val enqueued = enqueueOutbound(pending, context)
        debugTrace.onRouted(message.messageId, message.conversationId, kind, RoutingDecision.ACCEPTED, "protocol_inbound")
        return RoutingResult.Accepted(kind = kind, inbound = inboundResult, enqueuedOutbound = enqueued, target = "protocol_inbound")
    }

    private fun routeDistribution(message: SenderKeyDistributionMessage, kind: ProtocolMessageKind, context: RoutingContext): RoutingResult {
        if (!context.isSessionEnveloped) return reject(message, kind, "distribution_must_be_session_enveloped")
        val engine = senderKeyDistributionHandler ?: return reject(message, kind, "distribution_engine_missing")
        val authenticated = context.authenticatedPeerIdentityPublicKey ?: return reject(message, kind, "missing_authenticated_peer")

        val decision = engine.applyInboundDistribution(
            authenticatedPeerIdentityPublicKey = authenticated,
            msg = message,
        )
        if (!decision.accepted) return reject(message, kind, decision.reason ?: "sender_key_distribution_rejected")

        // Redact secret chain key before storage + ACK.
        val redacted = message.copy(senderChainKey = ByteArray(32))
        val bytes = encoder.encode(redacted)
        val inboundResult = inbound.onInboundBytes(bytes, context.receivedAtElapsedMs, context.senderId)
        val pending = if (context.isRestore) emptyList() else inbound.drainPendingOutbound()

        val enqueued = enqueueOutbound(pending, context)
        debugTrace.onRouted(message.messageId, message.conversationId, kind, RoutingDecision.ACCEPTED, "sender_key_distribution")
        return RoutingResult.Accepted(kind = kind, inbound = inboundResult, enqueuedOutbound = enqueued, target = "sender_key_distribution")
    }

    private fun routeGroup(message: SenderKeyGroupMessage, kind: ProtocolMessageKind, context: RoutingContext): RoutingResult {
        if (!context.isSessionEnveloped) return reject(message, kind, "group_message_must_be_session_enveloped")
        val engine = senderKeyGroupMessageHandler ?: return reject(message, kind, "group_engine_missing")
        val authenticated = context.authenticatedPeerIdentityPublicKey ?: return reject(message, kind, "missing_authenticated_peer")

        val decision = engine.decryptInbound(authenticatedPeerIdentityPublicKey = authenticated, msg = message)
        if (decision is SenderKeyGroupMessageHandler.InboundDecision.Rejected) return reject(message, kind, decision.reason)

        val user = (decision as SenderKeyGroupMessageHandler.InboundDecision.Accepted).userMessage
        val userBytes = encoder.encode(user)
        val inboundResult = inbound.onInboundBytes(userBytes, context.receivedAtElapsedMs, context.senderId)
        val pending = if (context.isRestore) emptyList() else inbound.drainPendingOutbound()

        val enqueued = enqueueOutbound(pending, context)
        debugTrace.onRouted(message.messageId, message.conversationId, kind, RoutingDecision.ACCEPTED, "group_message")
        return RoutingResult.Accepted(kind = kind, inbound = inboundResult, enqueuedOutbound = enqueued, target = "group_message")
    }

    private fun enqueueOutbound(messages: List<ProtocolMessage>, context: RoutingContext): List<ProtocolMessage> {
        if (messages.isEmpty()) return emptyList()
        val enqueued = ArrayList<ProtocolMessage>(messages.size)
        messages.forEach {
            val r = outbound.enqueue(context.peerId, it)
            if (r == EnqueueResult.Enqueued) enqueued.add(it)
        }
        return enqueued
    }

    private fun reject(message: ProtocolMessage, kind: ProtocolMessageKind, reason: String): RoutingResult.Rejected {
        debugTrace.onRejected(message.messageId, message.conversationId, kind, reason)
        debugTrace.onRouted(message.messageId, message.conversationId, kind, RoutingDecision.REJECTED, reason)
        return RoutingResult.Rejected(kind = kind, reason = reason)
    }
}
