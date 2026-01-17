package com.ivor.kriptex.deliverypolicy.conversationstate

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.connection.DefaultConnectionStateProvider
import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.InMemoryGroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.InMemorySenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyState
import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.messagestore.InMemoryConversationMessageStore
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSessionState
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSessionStoreSnapshot
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import com.ivor.kriptex.deliverypolicy.session.SessionRole
import com.ivor.kriptex.deliverypolicy.session.SessionStatus
import com.ivor.kriptex.deliverypolicy.session.x3dh.InMemoryX3dhPreKeyStore
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhCrypto
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhSignedPreKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

private class TestClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

private class RecordingConversationStateDebugTrace : ConversationStateDebugTrace {
    data class Entry(
        val conversationId: String,
        val health: ConversationHealth,
        val healthReason: String?,
        val encryptionStatus: ConversationEncryptionStatus,
        val encryptionReason: String?,
        val pending: Int,
        val lastActivity: Long,
    )

    val entries = ArrayList<Entry>()

    override fun onDerived(
        conversationId: String,
        conversationType: ConversationType,
        health: ConversationHealth,
        healthReason: String?,
        encryptionStatus: ConversationEncryptionStatus,
        encryptionReason: String?,
        pendingMessageCount: Int,
        lastActivityTimestamp: Long,
    ) {
        entries.add(
            Entry(
                conversationId = conversationId,
                health = health,
                healthReason = healthReason,
                encryptionStatus = encryptionStatus,
                encryptionReason = encryptionReason,
                pending = pendingMessageCount,
                lastActivity = lastActivityTimestamp,
            )
        )
    }
}

class ConversationStateAggregatorTest {

    private fun buildLegacyEstablishedSessionSnapshot(peerId: String, conversationId: String): PersistedSessionStoreSnapshot {
        val localId = ByteArray(32) { 1 }
        val peerIdKey = ByteArray(32) { 2 }

        val session = PersistedSessionState(
            peerId = peerId,
            conversationId = conversationId,
            sessionId = "s1",
            role = SessionRole.INITIATOR,
            status = SessionStatus.ESTABLISHED,
            aeadEnabled = false,
            aeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM,
            localIdentityPublicKey = localId,
            peerIdentityPublicKey = peerIdKey,
            initiatorNonce = byteArrayOf(7, 7),
            responderNonce = byteArrayOf(8, 8),
            sharedKey = ByteArray(32) { 9 },
            nextOutboundSeq = 1L,
            replayHighestSeqSeen = 0L,
            replaySeenBitmask = 0L,
            inboundMessageIdsSeen = emptyList(),
        )

        return PersistedSessionStoreSnapshot(
            capturedAtElapsedMs = 0L,
            sessions = listOf(session),
        )
    }

    private fun newSessionStore(): InMemorySessionStore {
        val id = X3dhCrypto.generateIdentityKeyPair()
        val spk = X3dhCrypto.generateX25519KeyPair()
        val spkId = 1
        val spkSig = X3dhCrypto.signSignedPreKey(
            identitySeed = id.seed,
            signedPreKeyId = spkId,
            signedPreKeyPublic = spk.publicKey,
        )
        val signed = X3dhSignedPreKey(
            preKeyId = spkId,
            privateKey = spk.privateKey,
            publicKey = spk.publicKey,
            signature = spkSig,
            createdAtElapsedMs = 0L,
        )
        val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = emptyList())
        return InMemorySessionStore(
            x3dhIdentitySeedEd = id.seed,
            x3dhIdentityPublicKeyEd = id.publicKey,
            x3dhPreKeyStore = preKeyStore,
        )
    }

    @Test
    fun send_then_ack_updates_pending_and_health_and_emits() = runBlocking {
        val conv = "c1"
        val peerId = "peer"

        val store = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val sessionStore = newSessionStore()
        sessionStore.restore(buildLegacyEstablishedSessionSnapshot(peerId = peerId, conversationId = conv))

        val trace = RecordingConversationStateDebugTrace()
        val manual = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val agg = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store,
            ledger = ledger,
            connectionStateProvider = connection,
            peerId = peerId,
            sessionStore = sessionStore,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store.invalidations,
                ledger = ledger.invalidations,
                manual = manual,
            ),
            debugTrace = trace,
        )

        val snapshots = ArrayList<ConversationSnapshot>()
        val started = CompletableDeferred<Unit>()
        val job = launch {
            agg.observe()
                .onEach {
                    snapshots.add(it)
                    if (!started.isCompleted) started.complete(Unit)
                }
                .take(2)
                .collect()
        }

        // Ensure collector is active and first emission captured.
        withTimeout(2_000) { started.await() }

        // Send.
        store.appendOutbound("m1", conv, payload = byteArrayOf(1), elapsedMs = 100L)
        ledger.recordEnqueued("m1", conv)
        ledger.recordSent("m1")
        store.markSent("m1", elapsedMs = 101L)

        manual.tryEmit(Unit)

        withTimeout(2_000) { job.join() }

        assertEquals(2, snapshots.size)
        assertEquals(0, snapshots[0].pendingMessageCount)
        assertEquals(ConversationHealth.ACTIVE, snapshots[0].health)
        assertEquals(ConversationEncryptionStatus.OK, snapshots[0].encryptionStatus)

        assertEquals(1, snapshots[1].pendingMessageCount)
        assertEquals(ConversationHealth.DEGRADED, snapshots[1].health)

        // Now ack and verify snapshot evolution without depending on flow scheduling.
        ledger.recordAcked("m1")
        store.markAcked("m1", elapsedMs = 120L)
        val afterAck = agg.snapshot()
        assertEquals(0, afterAck.pendingMessageCount)
        assertEquals(ConversationHealth.ACTIVE, afterAck.health)
        assertEquals(120L, afterAck.lastActivityTimestamp)

        // Diagnostics: ensure we explain degradation.
        val degraded = trace.entries.firstOrNull { it.health == ConversationHealth.DEGRADED }
        assertNotNull(degraded)
        assertEquals("pending_delivery_backlog", degraded!!.healthReason)
    }

    @Test
    fun offline_then_online_updates_health_and_emits() = runBlocking {
        val conv = "c1"

        val store = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportPeerOffline()

        val agg = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store,
            ledger = ledger,
            connectionStateProvider = connection,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store.invalidations,
                ledger = ledger.invalidations,
            ),
        )

        val snapshots = ArrayList<ConversationSnapshot>()
        val job = launch {
            agg.observe().take(2).toList(snapshots)
        }

        yield()
        connection.reportDirectContactConfirmed()

        withTimeout(2_000) { job.join() }

        assertEquals(2, snapshots.size)
        assertEquals(ConversationHealth.OFFLINE, snapshots[0].health)
        assertEquals(ConversationHealth.ACTIVE, snapshots[1].health)
    }

    @Test
    fun group_missing_sender_key_blocks_encryption() = runBlocking {
        val conv = "g1"
        val local = ByteArray(32) { 7 }
        val other = ByteArray(32) { 8 }

        val store = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val groupStore = ObservableGroupStore(InMemoryGroupStore())
        val senderKeyStore = ObservableSenderKeyStore(InMemorySenderKeyStore())

        // Membership order matters for the local-missing test.
        groupStore.put(GroupDefinition(conversationId = conv, memberIdentityPublicKeys = listOf(local, other)))

        // Only other has a sender key.
        senderKeyStore.put(
            SenderKeyState(
                groupId = com.ivor.kriptex.deliverypolicy.group.GroupId.fromConversationId(conv),
                senderIdentityPublicKey = other,
                senderKeyId = 1L,
                chainKey = ByteArray(32) { 1 },
                nextCounter = 1L,
            )
        )

        val trace = RecordingConversationStateDebugTrace()
        val agg = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store,
            ledger = ledger,
            connectionStateProvider = connection,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            localIdentityPublicKey = local,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store.invalidations,
                ledger = ledger.invalidations,
                groupStore = groupStore.invalidations,
                senderKeyStore = senderKeyStore.invalidations,
            ),
            debugTrace = trace,
        )

        val snap = agg.snapshot()
        assertEquals(ConversationType.GROUP, snap.conversationType)
        assertEquals(ConversationEncryptionStatus.MISSING_KEYS, snap.encryptionStatus)

        val last = trace.entries.last()
        assertEquals(ConversationEncryptionStatus.MISSING_KEYS, last.encryptionStatus)
        assertEquals("missing_local_sender_key", last.encryptionReason)
    }

    @Test
    fun restore_produces_same_snapshot_as_fresh_replay() {
        val conv = "c1"
        val peerId = "peer"

        val store1 = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger1 = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val sessionStore1 = newSessionStore()
        sessionStore1.restore(buildLegacyEstablishedSessionSnapshot(peerId = peerId, conversationId = conv))

        store1.appendOutbound("m1", conv, payload = byteArrayOf(1), elapsedMs = 10L)
        ledger1.recordEnqueued("m1", conv)
        ledger1.recordSent("m1")
        store1.markSent("m1", elapsedMs = 11L)

        val agg1 = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store1,
            ledger = ledger1,
            connectionStateProvider = connection,
            peerId = peerId,
            sessionStore = sessionStore1,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store1.invalidations,
                ledger = ledger1.invalidations,
            ),
        )

        val expected = agg1.snapshot()

        // Snapshot + restore into new stores.
        val storeSnap = store1.snapshot()
        val ledgerSnap = ledger1.snapshot()
        val sessionSnap = sessionStore1.snapshot()

        val store2 = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger2 = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())
        val sessionStore2 = newSessionStore()

        store2.restore(storeSnap)
        ledger2.restore(ledgerSnap)
        sessionStore2.restore(sessionSnap)

        val agg2 = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store2,
            ledger = ledger2,
            connectionStateProvider = connection,
            peerId = peerId,
            sessionStore = sessionStore2,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store2.invalidations,
                ledger = ledger2.invalidations,
            ),
        )

        val restored = agg2.snapshot()
        assertEquals(expected, restored)
    }
}
