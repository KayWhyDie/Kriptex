package com.ivor.kriptex.deliverypolicy.group.senderkey.media

import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.InMemoryGroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.InMemorySenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.InMemorySenderKeyDistributionStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.SenderKeyDistributionEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupMediaKeyDistributionEngineTest {

    private data class Stack(
        val identity: ByteArray,
        val groupStore: InMemoryGroupStore,
        val senderKeyStore: InMemorySenderKeyStore,
        val distStore: InMemorySenderKeyDistributionStore,
        val distEngine: SenderKeyDistributionEngine,
        val mediaKeyStore: InMemoryGroupMediaKeyStore,
        val pendingStore: InMemoryGroupMediaPendingStore,
        val unblock: GroupMediaUnblockCoordinator,
        val engine: GroupMediaKeyDistributionEngine,
    )

    private fun stack(identityByte: Byte): Stack {
        val id = ByteArray(32) { identityByte }
        val groupStore = InMemoryGroupStore()
        val senderKeyStore = InMemorySenderKeyStore()
        val distStore = InMemorySenderKeyDistributionStore()
        val distEngine = SenderKeyDistributionEngine(
            localIdentityPublicKey = id,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            distributionStore = distStore,
        )
        val mediaKeyStore = InMemoryGroupMediaKeyStore()
        val pendingStore = InMemoryGroupMediaPendingStore()
        val unblock = GroupMediaUnblockCoordinator()
        val engine = GroupMediaKeyDistributionEngine(
            localIdentityPublicKey = id,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            mediaKeyStore = mediaKeyStore,
            pendingStore = pendingStore,
            unblock = unblock,
        )
        return Stack(
            identity = id,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            distStore = distStore,
            distEngine = distEngine,
            mediaKeyStore = mediaKeyStore,
            pendingStore = pendingStore,
            unblock = unblock,
            engine = engine,
        )
    }

    private fun putGroupOnBoth(a: Stack, b: Stack, conversationId: String = "g1"): GroupId {
        val group = GroupDefinition(
            conversationId = conversationId,
            memberIdentityPublicKeys = listOf(a.identity, b.identity),
        )
        a.groupStore.put(group)
        b.groupStore.put(group)
        return group.groupId
    }

    private fun distributeSenderKey(from: Stack, to: Stack, groupId: GroupId, conversationIdForRecipient: String = "c1") {
        val planned = from.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { conversationIdForRecipient },
            messageIdGenerator = { "d1" },
        )
        // Single recipient (the other member).
        val msg = planned.single().message
        val res = to.distEngine.applyInboundDistribution(
            authenticatedPeerIdentityPublicKey = from.identity,
            msg = msg,
        )
        assertTrue(res.accepted)
    }

    @Test
    fun happy_path_key_applies_and_unblocks_only_after_chunks_verified() {
        val a = stack(1)
        val b = stack(2)
        val groupId = putGroupOnBoth(a, b)

        // Ensure A has a local sender key and distribute it to B.
        a.distEngine.getOrCreateLocalSenderKey(groupId)
        distributeSenderKey(from = a, to = b, groupId = groupId)

        val mediaKey = ByteArray(32) { 7 }
        val msg = a.engine.encryptOutbound(
            conversationId = "g1",
            messageId = "gm1",
            mediaId = "m1",
            mediaKey = mediaKey,
        )

        // Gate: chunks verified first, still not ready without media key.
        b.unblock.markChunksVerified("m1")
        assertFalse(b.unblock.isReady("m1"))

        val inbound = b.engine.applyInbound(authenticatedPeerIdentityPublicKey = a.identity, msg = msg)
        assertTrue(inbound is GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted)
        assertTrue((inbound as GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted).applied)
        assertTrue(b.unblock.isReady("m1"))

        val stored = b.mediaKeyStore.get("m1")
        assertNotNull(stored)
        assertArrayEquals(mediaKey, stored!!.mediaKey)
        assertEquals(groupId, stored.groupId)
        assertArrayEquals(a.identity, stored.senderIdentityPublicKey)
    }

    @Test
    fun missing_sender_key_buffers_then_sender_key_available_replays_pending_and_unblocks() {
        val a = stack(1)
        val b = stack(2)
        val groupId = putGroupOnBoth(a, b)

        // Model reorder: sender key distribution is created/sent first, but arrives late.
        val planned = a.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = { "d1" },
        )
        val skd = planned.single().message

        val mediaKey = ByteArray(32) { 9 }
        val msg = a.engine.encryptOutbound(
            conversationId = "g1",
            messageId = "gm1",
            mediaId = "m1",
            mediaKey = mediaKey,
        )

        // Chunks already verified, but no key yet.
        b.unblock.markChunksVerified("m1")

        val inbound1 = b.engine.applyInbound(authenticatedPeerIdentityPublicKey = a.identity, msg = msg)
        assertTrue(inbound1 is GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted)
        assertFalse((inbound1 as GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted).applied)
        assertTrue(inbound1.pending)
        assertFalse(b.unblock.isReady("m1"))

        // Now B receives sender key distribution and we notify the media engine.
        val res = b.distEngine.applyInboundDistribution(
            authenticatedPeerIdentityPublicKey = a.identity,
            msg = skd,
        )
        assertTrue(res.accepted)
        b.engine.onSenderKeyAvailable(groupId = groupId, senderIdentityPublicKey = a.identity)

        val stored = b.mediaKeyStore.get("m1")
        assertNotNull(stored)
        assertArrayEquals(mediaKey, stored!!.mediaKey)
        assertTrue(b.unblock.isReady("m1"))
    }

    @Test
    fun key_can_arrive_before_chunks_verified_and_readiness_waits_for_chunks() {
        val a = stack(1)
        val b = stack(2)
        val groupId = putGroupOnBoth(a, b)

        a.distEngine.getOrCreateLocalSenderKey(groupId)
        distributeSenderKey(from = a, to = b, groupId = groupId)

        val msg = a.engine.encryptOutbound(
            conversationId = "g1",
            messageId = "gm1",
            mediaId = "m1",
            mediaKey = ByteArray(32) { 4 },
        )

        val inbound = b.engine.applyInbound(authenticatedPeerIdentityPublicKey = a.identity, msg = msg)
        assertTrue(inbound is GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted)
        assertFalse(b.unblock.isReady("m1"))

        b.unblock.markChunksVerified("m1")
        assertTrue(b.unblock.isReady("m1"))
    }

    @Test
    fun replay_of_same_media_distribution_is_accepted_without_advancing_sender_key_state() {
        val a = stack(1)
        val b = stack(2)
        val groupId = putGroupOnBoth(a, b)

        a.distEngine.getOrCreateLocalSenderKey(groupId)
        distributeSenderKey(from = a, to = b, groupId = groupId)

        val mediaKey = ByteArray(32) { 5 }
        val msg = a.engine.encryptOutbound(
            conversationId = "g1",
            messageId = "gm1",
            mediaId = "m1",
            mediaKey = mediaKey,
        )

        val first = b.engine.applyInbound(authenticatedPeerIdentityPublicKey = a.identity, msg = msg)
        assertTrue(first is GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted)

        val before = b.senderKeyStore.get(groupId, a.identity)!!.nextCounter

        val second = b.engine.applyInbound(authenticatedPeerIdentityPublicKey = a.identity, msg = msg)
        assertTrue(second is GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted)
        assertFalse((second as GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted).pending)

        val after = b.senderKeyStore.get(groupId, a.identity)!!.nextCounter
        assertEquals(before, after)
    }

    @Test
    fun rotation_mismatch_rejects_old_media_distribution() {
        val a = stack(1)
        val b = stack(2)
        val groupId = putGroupOnBoth(a, b)

        a.distEngine.getOrCreateLocalSenderKey(groupId)
        distributeSenderKey(from = a, to = b, groupId = groupId)

        val oldMsg = a.engine.encryptOutbound(
            conversationId = "g1",
            messageId = "gm_old",
            mediaId = "m_old",
            mediaKey = ByteArray(32) { 1 },
        )

        // Rotate and distribute new key to B.
        a.distEngine.rotateLocalSenderKey(groupId, reason = "test")
        // Force a new distribution message id so planDistributions returns one.
        val planned2 = a.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = { "d2" },
        )
        val dist2 = planned2.single().message
        val res2 = b.distEngine.applyInboundDistribution(authenticatedPeerIdentityPublicKey = a.identity, msg = dist2)
        assertTrue(res2.accepted)

        val r = b.engine.applyInbound(authenticatedPeerIdentityPublicKey = a.identity, msg = oldMsg)
        assertTrue(r is GroupMediaKeyDistributionEngine.InboundApplyResult.Rejected)
        assertEquals("sender_key_id_mismatch", (r as GroupMediaKeyDistributionEngine.InboundApplyResult.Rejected).reason)
    }

    @Test
    fun snapshot_restore_preserves_pending_and_unblock_state() {
        val a = stack(1)
        val b = stack(2)
        val groupId = putGroupOnBoth(a, b)

        // Model reorder: sender key distribution exists but hasn't been applied yet.
        val planned = a.distEngine.planDistributions(
            groupId = groupId,
            conversationIdForRecipient = { "c1" },
            messageIdGenerator = { "d1" },
        )
        val skd = planned.single().message
        val msg = a.engine.encryptOutbound(
            conversationId = "g1",
            messageId = "gm1",
            mediaId = "m1",
            mediaKey = ByteArray(32) { 3 },
        )

        // Key missing on B -> pending; chunks already verified.
        b.unblock.markChunksVerified("m1")
        val inbound1 = b.engine.applyInbound(authenticatedPeerIdentityPublicKey = a.identity, msg = msg)
        assertTrue(inbound1 is GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted)
        assertTrue((inbound1 as GroupMediaKeyDistributionEngine.InboundApplyResult.Accepted).pending)

        val pendingSnap = b.pendingStore.snapshot(capturedAtElapsedMs = 100L)
        val keySnap = b.mediaKeyStore.snapshot(capturedAtElapsedMs = 100L)
        val unblockSnap = b.unblock.snapshot(capturedAtElapsedMs = 100L)

        // Restore into fresh stores/engine.
        val b2 = stack(2)
        putGroupOnBoth(a, b2)
        b2.pendingStore.restore(pendingSnap)
        b2.mediaKeyStore.restore(keySnap)
        b2.unblock.restore(unblockSnap)

        // Now deliver sender key and replay pending.
        val res = b2.distEngine.applyInboundDistribution(
            authenticatedPeerIdentityPublicKey = a.identity,
            msg = skd,
        )
        assertTrue(res.accepted)
        b2.engine.onSenderKeyAvailable(groupId = groupId, senderIdentityPublicKey = a.identity)

        assertNotNull(b2.mediaKeyStore.get("m1"))
        assertTrue(b2.unblock.isReady("m1"))
    }
}
