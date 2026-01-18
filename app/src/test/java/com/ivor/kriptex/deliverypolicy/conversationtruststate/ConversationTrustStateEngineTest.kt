package com.ivor.kriptex.deliverypolicy.conversationtruststate

import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.InMemoryGroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.InMemorySenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTrustStateEngineTest {

    @Test
    fun identity_change_detected_and_ack_clears() {
        val conv = "c1"
        val peerId = "peer"

        val trustStore = InMemoryConversationTrustStore()
        val identityStore = InMemoryIdentityKeyStore()

        val k1 = ByteArray(32) { 1 }
        val k2 = ByteArray(32) { 2 }

        identityStore.putPeerIdentityPublicKey(peerId, k1)

        val engine = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore,
            identityKeyStore = identityStore,
            peerId = peerId,
        )

        engine.verifyConversation()
        assertEquals(TrustLevel.VERIFIED, engine.snapshot().trustLevel)

        identityStore.putPeerIdentityPublicKey(peerId, k2)
        val changed = engine.snapshot()
        assertEquals(TrustLevel.CHANGED, changed.trustLevel)
        assertTrue(TrustIssue.IdentityKeyChanged in changed.issues)
        assertTrue(TrustIssue.IdentityKeyChanged in changed.unacknowledgedIssues)

        engine.acknowledgeCurrentIssues()
        val cleared = engine.snapshot()
        assertEquals(TrustLevel.VERIFIED, cleared.trustLevel)
        assertFalse(TrustIssue.IdentityKeyChanged in cleared.issues)
    }

    @Test
    fun group_membership_change_detected_and_ack_clears() {
        val conv = "g1"
        val groupId = GroupId.fromConversationId(conv)

        val local = ByteArray(32) { 7 }
        val other = ByteArray(32) { 8 }
        val third = ByteArray(32) { 9 }

        val trustStore = InMemoryConversationTrustStore()
        val groupStore = InMemoryGroupStore()
        val senderKeyStore = InMemorySenderKeyStore()

        groupStore.put(GroupDefinition(conversationId = conv, memberIdentityPublicKeys = listOf(local, other)))

        // Provide sender keys for all members to avoid MissingSenderKey.
        senderKeyStore.put(senderKeyState(groupId = groupId, sender = local, senderKeyId = 1L))
        senderKeyStore.put(senderKeyState(groupId = groupId, sender = other, senderKeyId = 2L))

        val engine = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            localIdentityPublicKey = local,
        )

        engine.verifyConversation()
        assertEquals(TrustLevel.VERIFIED, engine.snapshot().trustLevel)

        groupStore.put(GroupDefinition(conversationId = conv, memberIdentityPublicKeys = listOf(local, other, third)))
        senderKeyStore.put(senderKeyState(groupId = groupId, sender = third, senderKeyId = 3L))

        val changed = engine.snapshot()
        assertEquals(TrustLevel.CHANGED, changed.trustLevel)
        assertTrue(TrustIssue.MemberAdded in changed.issues)
        assertTrue(TrustIssue.MemberAdded in changed.unacknowledgedIssues)

        engine.acknowledgeCurrentIssues()
        val cleared = engine.snapshot()
        assertEquals(TrustLevel.VERIFIED, cleared.trustLevel)
        assertFalse(TrustIssue.MemberAdded in cleared.issues)
        assertFalse(TrustIssue.MemberRemoved in cleared.issues)
    }

    @Test
    fun restore_produces_equivalent_snapshot() {
        val conv = "g2"
        val groupId = GroupId.fromConversationId(conv)

        val local = ByteArray(32) { 7 }
        val other = ByteArray(32) { 8 }

        val trustStore1 = InMemoryConversationTrustStore()
        val identityStore1 = InMemoryIdentityKeyStore()
        val groupStore1 = InMemoryGroupStore()
        val senderKeyStore1 = InMemorySenderKeyStore()

        identityStore1.putPeerIdentityPublicKey("peer", ByteArray(32) { 1 })
        groupStore1.put(GroupDefinition(conversationId = conv, memberIdentityPublicKeys = listOf(local, other)))

        // Only other has a sender key => MissingSenderKey is present.
        senderKeyStore1.put(senderKeyState(groupId = groupId, sender = other, senderKeyId = 2L))

        val engine1 = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore1,
            identityKeyStore = identityStore1,
            peerId = "peer",
            groupStore = groupStore1,
            senderKeyStore = senderKeyStore1,
            localIdentityPublicKey = local,
        )

        engine1.verifyConversation()
        val expected = engine1.snapshot()
        assertEquals(TrustLevel.UNVERIFIED, expected.trustLevel)
        assertTrue(TrustIssue.MissingSenderKey in expected.issues)
        assertTrue(expected.unacknowledgedIssues.isEmpty())

        val trustSnap = trustStore1.snapshot(capturedAtElapsedMs = 123L)
        val identitySnap = identityStore1.snapshot()
        val groupSnap = groupStore1.snapshot()
        val senderKeySnap = senderKeyStore1.snapshot()

        val trustStore2 = InMemoryConversationTrustStore()
        val identityStore2 = InMemoryIdentityKeyStore()
        val groupStore2 = InMemoryGroupStore()
        val senderKeyStore2 = InMemorySenderKeyStore()

        trustStore2.restore(trustSnap)
        identityStore2.restore(identitySnap)
        groupStore2.restore(groupSnap)
        senderKeyStore2.restore(senderKeySnap)

        val engine2 = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore2,
            identityKeyStore = identityStore2,
            peerId = "peer",
            groupStore = groupStore2,
            senderKeyStore = senderKeyStore2,
            localIdentityPublicKey = local,
        )

        assertEquals(expected, engine2.snapshot())
    }

    @Test
    fun acknowledge_clears_warning_but_missing_sender_key_remains() {
        val conv = "g3"
        val groupId = GroupId.fromConversationId(conv)

        val local = ByteArray(32) { 7 }
        val other = ByteArray(32) { 8 }

        val trustStore = InMemoryConversationTrustStore()
        val groupStore = InMemoryGroupStore()
        val senderKeyStore = InMemorySenderKeyStore()

        groupStore.put(GroupDefinition(conversationId = conv, memberIdentityPublicKeys = listOf(local, other)))
        senderKeyStore.put(senderKeyState(groupId = groupId, sender = other, senderKeyId = 2L))

        val engine = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            localIdentityPublicKey = local,
        )

        val before = engine.snapshot()
        assertEquals(TrustLevel.UNVERIFIED, before.trustLevel)
        assertTrue(TrustIssue.MissingSenderKey in before.issues)
        assertTrue(TrustIssue.MissingSenderKey in before.unacknowledgedIssues)

        engine.acknowledgeCurrentIssues()

        val after = engine.snapshot()
        assertEquals(TrustLevel.UNVERIFIED, after.trustLevel)
        assertTrue(TrustIssue.MissingSenderKey in after.issues)
        assertFalse(TrustIssue.MissingSenderKey in after.unacknowledgedIssues)
    }

    private fun senderKeyState(groupId: GroupId, sender: ByteArray, senderKeyId: Long): SenderKeyState {
        return SenderKeyState(
            groupId = groupId,
            senderIdentityPublicKey = sender,
            senderKeyId = senderKeyId,
            chainKey = ByteArray(32) { 1 },
            nextCounter = 1L,
            skippedMessageKeys = emptyMap(),
        )
    }
}
