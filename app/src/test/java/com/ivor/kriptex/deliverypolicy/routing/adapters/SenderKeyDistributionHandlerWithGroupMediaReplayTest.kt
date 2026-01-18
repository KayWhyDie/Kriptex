package com.ivor.kriptex.deliverypolicy.routing.adapters

import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.routing.handlers.SenderKeyDistributionHandler
import org.junit.Assert.assertEquals
import org.junit.Test

class SenderKeyDistributionHandlerWithGroupMediaReplayTest {

    private class Fake(val accept: Boolean) : SenderKeyDistributionHandler {
        var applied = 0
        override fun applyInboundDistribution(
            authenticatedPeerIdentityPublicKey: ByteArray,
            msg: SenderKeyDistributionMessage,
        ): SenderKeyDistributionHandler.InboundApplyResult {
            applied++
            return SenderKeyDistributionHandler.InboundApplyResult(accepted = accept, reason = if (accept) null else "no")
        }

        override fun onInboundAck(authenticatedPeerIdentityPublicKey: ByteArray, ackedMessageId: String) = Unit
    }

    @Test
    fun accepted_distribution_triggers_callback() {
        val delegate = Fake(accept = true)

        var called = 0
        var gid: GroupId? = null

        val decorated = SenderKeyDistributionHandlerWithGroupMediaReplay(delegate) { groupId, _ ->
            called++
            gid = groupId
        }

        val msg = SenderKeyDistributionMessage(
            messageId = "d1",
            conversationId = "c1",
            createdAtElapsedMs = 1L,
            groupId = ByteArray(32) { 7 },
            senderIdentityPublicKey = ByteArray(32) { 1 },
            senderKeyId = 1L,
            senderChainKey = ByteArray(32) { 2 },
        )

        decorated.applyInboundDistribution(ByteArray(32) { 1 }, msg)

        assertEquals(1, delegate.applied)
        assertEquals(1, called)
        assertEquals(GroupId(msg.groupId), gid)
    }

    @Test
    fun rejected_distribution_does_not_trigger_callback() {
        val delegate = Fake(accept = false)

        var called = 0
        val decorated = SenderKeyDistributionHandlerWithGroupMediaReplay(delegate) { _, _ -> called++ }

        val msg = SenderKeyDistributionMessage(
            messageId = "d1",
            conversationId = "c1",
            createdAtElapsedMs = 1L,
            groupId = ByteArray(32) { 7 },
            senderIdentityPublicKey = ByteArray(32) { 1 },
            senderKeyId = 1L,
            senderChainKey = ByteArray(32) { 2 },
        )

        decorated.applyInboundDistribution(ByteArray(32) { 1 }, msg)

        assertEquals(1, delegate.applied)
        assertEquals(0, called)
    }
}
