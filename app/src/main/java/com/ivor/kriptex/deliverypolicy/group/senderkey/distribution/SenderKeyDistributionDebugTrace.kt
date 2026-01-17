package com.ivor.kriptex.deliverypolicy.group.senderkey.distribution

import com.ivor.kriptex.deliverypolicy.group.GroupId

interface SenderKeyDistributionDebugTrace {
    fun onKeyGenerated(groupId: GroupId, senderKeyId: Long, elapsedMs: Long)

    fun onKeyRotated(groupId: GroupId, oldSenderKeyId: Long, newSenderKeyId: Long, reason: String, elapsedMs: Long)

    fun onPlannedDistribution(groupId: GroupId, senderKeyId: Long, recipientIdentityPublicKey: ByteArray, messageId: String, elapsedMs: Long)

    fun onMarkedDelivered(groupId: GroupId, senderKeyId: Long, recipientIdentityPublicKey: ByteArray, elapsedMs: Long)

    fun onInboundAccepted(groupId: GroupId, senderIdentityPublicKey: ByteArray, senderKeyId: Long, elapsedMs: Long)

    fun onInboundRejected(reason: String, elapsedMs: Long)

    companion object {
        val NO_OP: SenderKeyDistributionDebugTrace = object : SenderKeyDistributionDebugTrace {
            override fun onKeyGenerated(groupId: GroupId, senderKeyId: Long, elapsedMs: Long) = Unit
            override fun onKeyRotated(groupId: GroupId, oldSenderKeyId: Long, newSenderKeyId: Long, reason: String, elapsedMs: Long) = Unit
            override fun onPlannedDistribution(groupId: GroupId, senderKeyId: Long, recipientIdentityPublicKey: ByteArray, messageId: String, elapsedMs: Long) = Unit
            override fun onMarkedDelivered(groupId: GroupId, senderKeyId: Long, recipientIdentityPublicKey: ByteArray, elapsedMs: Long) = Unit
            override fun onInboundAccepted(groupId: GroupId, senderIdentityPublicKey: ByteArray, senderKeyId: Long, elapsedMs: Long) = Unit
            override fun onInboundRejected(reason: String, elapsedMs: Long) = Unit
        }
    }
}
