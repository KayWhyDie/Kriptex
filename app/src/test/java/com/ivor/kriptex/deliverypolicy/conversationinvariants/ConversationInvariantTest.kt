package com.ivor.kriptex.deliverypolicy.conversationinvariants

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.connection.DefaultConnectionStateProvider
import com.ivor.kriptex.deliverypolicy.conversationattention.AppLifecycleState
import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionCoordinator
import com.ivor.kriptex.deliverypolicy.conversationattention.NotificationSink
import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationFacade
import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationView
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationEncryptionStatus
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationStateAggregator
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationStateInvalidationSources
import com.ivor.kriptex.deliverypolicy.conversationstate.ObservableConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.conversationstate.ObservableConversationMessageStore
import com.ivor.kriptex.deliverypolicy.conversationstate.ObservableGroupStore
import com.ivor.kriptex.deliverypolicy.conversationstate.ObservableSenderKeyStore
import com.ivor.kriptex.deliverypolicy.conversationtruststate.ConversationTrustInvalidationSources
import com.ivor.kriptex.deliverypolicy.conversationtruststate.ConversationTrustStateEngine
import com.ivor.kriptex.deliverypolicy.conversationtruststate.InMemoryConversationTrustStore
import com.ivor.kriptex.deliverypolicy.conversationtruststate.InMemoryIdentityKeyStore
import com.ivor.kriptex.deliverypolicy.conversationtruststate.TrustIssue
import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

private class TestClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

private class NoOpNotificationSink : NotificationSink {
    override fun showNotification(conversationId: String, snapshot: com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot) = Unit
    override fun cancelNotification(conversationId: String) = Unit
}

class ConversationInvariantTest {

    private val validator = ConversationInvariantValidator()

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

    private fun establishedSessionSnapshot(peerId: String, conversationId: String): PersistedSessionStoreSnapshot {
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

    private suspend fun Channel<ConversationView>.awaitView(timeoutMs: Long, predicate: (ConversationView) -> Boolean): ConversationView {
        return withTimeout(timeoutMs) {
            while (true) {
                val v = receive()
                if (predicate(v)) return@withTimeout v
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }
    }

    private fun assertNoViolations(violations: List<ConversationInvariantViolation>) {
        if (violations.isEmpty()) return
        fail(
            buildString {
                appendLine("Expected zero invariant violations, but got ${violations.size}:")
                violations.forEach { v ->
                    appendLine("- ${v.severity} ${v.id}: ${v.message} details=${v.details}")
                }
            }
        )
    }

    @Test
    fun one_to_one_happy_path_has_no_invariant_violations_and_restore_equivalence() = runBlocking {
        val conv = "c_inv_1"
        val peerId = "peer"

        val store1 = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger1 = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val sessionStore1 = newSessionStore()
        sessionStore1.restore(establishedSessionSnapshot(peerId = peerId, conversationId = conv))

        val stateAgg1 = ConversationStateAggregator(
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

        val trustStore1 = InMemoryConversationTrustStore()
        val identityStore1 = InMemoryIdentityKeyStore()
        identityStore1.putPeerIdentityPublicKey(peerId, ByteArray(32) { 1 })

        val trustEngine1 = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore1,
            identityKeyStore = identityStore1,
            peerId = peerId,
        )
        trustEngine1.verifyConversation()

        val coordinator1 = ConversationAttentionCoordinator(notificationSink = NoOpNotificationSink())
        coordinator1.onAppLifecycle(AppLifecycleState.FOREGROUND)
        coordinator1.onVisibleConversationChanged(null)

        val wireStarted = CompletableDeferred<Unit>()
        val wireJob = launch {
            wireStarted.complete(Unit)
            stateAgg1.observe().collect { coordinator1.onSnapshot(it) }
        }
        withTimeout(2_000) { wireStarted.await() }

        val facade1 = ConversationFacade(
            conversationId = conv,
            state = stateAgg1,
            trust = trustEngine1,
            attention = coordinator1,
        )

        val out = Channel<ConversationView>(capacity = Channel.UNLIMITED)
        val collectJob = launch { facade1.observe().collect { out.trySend(it) } }

        val baseline = out.awaitView(2_000) { true }
        assertNoViolations(validator.validate(baseline))

        // Send + mark sent; this advances activity and increases pending.
        store1.appendOutbound("m1", conv, payload = byteArrayOf(1), elapsedMs = 100L)
        ledger1.recordEnqueued("m1", conv)
        ledger1.recordSent("m1")
        store1.markSent("m1", elapsedMs = 101L)

        val afterSend = out.awaitView(2_000) { it.lastActivityTimestamp >= 101L }
        assertNoViolations(validator.validateTransition(baseline, afterSend))

        // Become visible -> unread resets to 0.
        coordinator1.onVisibleConversationChanged(conv)
        val afterVisible = out.awaitView(2_000) { it.attention.name == "VISIBLE" }
        assertNoViolations(validator.validateTransition(afterSend, afterVisible))

        // Ack -> pending clears and activity advances.
        ledger1.recordAcked("m1")
        store1.markAcked("m1", elapsedMs = 120L)

        val afterAck = out.awaitView(2_000) { it.lastActivityTimestamp >= 120L && it.snapshot.pendingMessageCount == 0 }
        assertNoViolations(validator.validateTransition(afterVisible, afterAck))

        // Restore equivalence for snapshot()
        val expected = facade1.snapshot(attentionState = afterAck.attention, unreadCount = afterAck.unreadCount)
        assertNoViolations(validator.validate(expected))

        val storeSnap = store1.snapshot()
        val ledgerSnap = ledger1.snapshot()
        val trustSnap = trustStore1.snapshot(capturedAtElapsedMs = 123L)
        val identitySnap = identityStore1.snapshot()
        val sessionSnap = sessionStore1.snapshot()

        val store2 = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger2 = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())
        val trustStore2 = InMemoryConversationTrustStore()
        val identityStore2 = InMemoryIdentityKeyStore()
        val sessionStore2 = newSessionStore()

        store2.restore(storeSnap)
        ledger2.restore(ledgerSnap)
        trustStore2.restore(trustSnap)
        identityStore2.restore(identitySnap)
        sessionStore2.restore(sessionSnap)

        val stateAgg2 = ConversationStateAggregator(
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

        val trustEngine2 = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore2,
            identityKeyStore = identityStore2,
            peerId = peerId,
        )

        val coordinator2 = ConversationAttentionCoordinator(notificationSink = NoOpNotificationSink())
        coordinator2.onAppLifecycle(AppLifecycleState.FOREGROUND)
        coordinator2.onVisibleConversationChanged(conv)

        val wireJob2 = launch { stateAgg2.observe().collect { coordinator2.onSnapshot(it) } }

        val facade2 = ConversationFacade(
            conversationId = conv,
            state = stateAgg2,
            trust = trustEngine2,
            attention = coordinator2,
        )

        val restored = facade2.snapshot(attentionState = afterAck.attention, unreadCount = afterAck.unreadCount)
        assertEquals(expected, restored)
        assertNoViolations(validator.validate(restored))

        wireJob.cancel()
        collectJob.cancel()
        wireJob2.cancel()
    }

    @Test
    fun group_missing_sender_key_then_unblocked_has_no_invariant_violations() = runBlocking {
        val conv = "g_inv_1"
        val groupId = GroupId.fromConversationId(conv)

        val local = ByteArray(32) { 7 }
        val other = ByteArray(32) { 8 }

        val store = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())
        val groupStore = ObservableGroupStore(InMemoryGroupStore())
        val senderKeyStore = ObservableSenderKeyStore(InMemorySenderKeyStore())

        val manual = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)

        groupStore.put(GroupDefinition(conversationId = conv, memberIdentityPublicKeys = listOf(local, other)))
        // Only other has a sender key; local missing.
        senderKeyStore.put(senderKeyState(groupId = groupId, sender = other, senderKeyId = 2L))

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val stateAgg = ConversationStateAggregator(
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
                manual = manual,
            ),
        )

        val trustStore = InMemoryConversationTrustStore()
        val trustEngine = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            localIdentityPublicKey = local,
            invalidations = ConversationTrustInvalidationSources(
                groupStore = groupStore.invalidations,
                senderKeyStore = senderKeyStore.invalidations,
                manual = manual,
            ),
        )

        val coordinator = ConversationAttentionCoordinator(notificationSink = NoOpNotificationSink())
        coordinator.onAppLifecycle(AppLifecycleState.FOREGROUND)
        coordinator.onVisibleConversationChanged(null)

        val wireJob = launch { stateAgg.observe().collect { coordinator.onSnapshot(it) } }

        val facade = ConversationFacade(
            conversationId = conv,
            state = stateAgg,
            trust = trustEngine,
            attention = coordinator,
        )

        val baseline = facade.snapshot()
        assertNoViolations(validator.validate(baseline))

        // Provide missing local sender key => encryption should become OK and trust issue should clear.
        senderKeyStore.put(senderKeyState(groupId = groupId, sender = local, senderKeyId = 1L))
        manual.emit(Unit)

        // Sanity: the underlying derived snapshots must reflect the update.
        val stateAfter = stateAgg.snapshot()
        val trustAfter = trustEngine.snapshot()
        assertEquals(ConversationEncryptionStatus.OK, stateAfter.encryptionStatus)
        if (TrustIssue.MissingSenderKey in trustAfter.issues) {
            fail("Expected MissingSenderKey to clear after sender key insertion")
        }

        val updated = facade.snapshot()
        assertNoViolations(validator.validateTransition(baseline, updated))

        wireJob.cancel()
    }

    @Test
    fun ack_storm_does_not_produce_invariant_violations() = runBlocking {
        val conv = "c_inv_ack"
        val peerId = "peer"

        val store = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val sessionStore = newSessionStore()
        sessionStore.restore(establishedSessionSnapshot(peerId = peerId, conversationId = conv))

        val stateAgg = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store,
            ledger = ledger,
            connectionStateProvider = connection,
            peerId = peerId,
            sessionStore = sessionStore,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store.invalidations,
                ledger = ledger.invalidations,
            ),
        )

        val trustStore = InMemoryConversationTrustStore()
        val identityStore = InMemoryIdentityKeyStore()
        identityStore.putPeerIdentityPublicKey(peerId, ByteArray(32) { 1 })
        val trustEngine = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore,
            identityKeyStore = identityStore,
            peerId = peerId,
        )

        val coordinator = ConversationAttentionCoordinator(notificationSink = NoOpNotificationSink())
        coordinator.onAppLifecycle(AppLifecycleState.FOREGROUND)
        coordinator.onVisibleConversationChanged(null)

        val wireJob = launch { stateAgg.observe().collect { coordinator.onSnapshot(it) } }

        val facade = ConversationFacade(
            conversationId = conv,
            state = stateAgg,
            trust = trustEngine,
            attention = coordinator,
        )

        val out = Channel<ConversationView>(capacity = Channel.UNLIMITED)
        val collectJob = launch { facade.observe().collect { out.trySend(it) } }

        val baseline = out.awaitView(2_000) { true }
        assertNoViolations(validator.validate(baseline))

        // Simulate out-of-order / repeated ACKs (including unknown message ids).
        ledger.recordAcked("unknown")
        ledger.recordAcked("unknown")

        store.appendOutbound("m1", conv, payload = byteArrayOf(1), elapsedMs = 10L)
        ledger.recordEnqueued("m1", conv)
        ledger.recordSent("m1")
        store.markSent("m1", elapsedMs = 11L)

        ledger.recordAcked("m1")
        ledger.recordAcked("m1")
        store.markAcked("m1", elapsedMs = 12L)

        val final = out.awaitView(2_000) { it.lastActivityTimestamp >= 12L }
        assertNoViolations(validator.validateSequence(listOf(baseline, final)))

        // Ensure there are no late violations in-flight.
        val unexpected = withTimeoutOrNull(250) { out.receive() }
        if (unexpected != null) {
            assertNoViolations(validator.validateTransition(final, unexpected))
        }

        wireJob.cancel()
        collectJob.cancel()
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
