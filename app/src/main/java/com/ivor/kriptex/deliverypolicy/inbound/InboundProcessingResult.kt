package com.ivor.kriptex.deliverypolicy.inbound

/** Result of processing one inbound message. */
sealed interface InboundProcessingResult {
    val messageId: String
    val conversationId: String
    val receiveIndex: Int?
    val ackDecision: AckDecision

    data class Accepted(
        override val messageId: String,
        override val conversationId: String,
        override val receiveIndex: Int,
        override val ackDecision: AckDecision,
        /** True if caller should deliver this to the app layer (non-ACK). */
        val shouldDeliver: Boolean,
    ) : InboundProcessingResult

    data class DuplicateIgnored(
        override val messageId: String,
        override val conversationId: String,
        override val receiveIndex: Int?,
        override val ackDecision: AckDecision = AckDecision.NO_ACK,
    ) : InboundProcessingResult
}
