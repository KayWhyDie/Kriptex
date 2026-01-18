package com.ivor.kriptex.deliverypolicy.conversationfacade

import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.connection.DefaultConnectionStateProvider
import com.ivor.kriptex.deliverypolicy.conversationattention.AppLifecycleState
import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionCoordinator
import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionState
import com.ivor.kriptex.deliverypolicy.conversationattention.NotificationSink
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationStateAggregator
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationStateInvalidationSources
import com.ivor.kriptex.deliverypolicy.conversationstate.ObservableConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.conversationstate.ObservableConversationMessageStore
import com.ivor.kriptex.deliverypolicy.conversationtruststate.ConversationTrustStateEngine
import com.ivor.kriptex.deliverypolicy.conversationtruststate.ConversationTrustInvalidationSources
import com.ivor.kriptex.deliverypolicy.conversationtruststate.InMemoryConversationTrustStore
import com.ivor.kriptex.deliverypolicy.conversationtruststate.InMemoryIdentityKeyStore
import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.messagestore.InMemoryConversationMessageStore
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.selects.select
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class TestClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
}

private class RecordingNotificationSink : NotificationSink {
    override fun showNotification(conversationId: String, snapshot: com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot) = Unit
    override fun cancelNotification(conversationId: String) = Unit
}

class ConversationFacadeTest {

    @Test
    fun view_updates_when_any_component_changes_and_is_deduped_for_noops() = runBlocking {
        val conv = "c1"
        val peerId = "peer"

        val store = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val manual = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)
        val stateAgg = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store,
            ledger = ledger,
            connectionStateProvider = connection,
            peerId = peerId,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store.invalidations,
                ledger = ledger.invalidations,
                manual = manual,
            ),
        )

        val trustStore = InMemoryConversationTrustStore()
        val identityStore = InMemoryIdentityKeyStore()
        identityStore.putPeerIdentityPublicKey(peerId, ByteArray(32) { 1 })

        val trustInvalidations = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 8)

        val trustEngine = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore,
            identityKeyStore = identityStore,
            peerId = peerId,
            invalidations = ConversationTrustInvalidationSources(identityKeyStore = trustInvalidations),
        )
        trustEngine.verifyConversation()

        val coordinator = ConversationAttentionCoordinator(notificationSink = RecordingNotificationSink())
        coordinator.onAppLifecycle(AppLifecycleState.FOREGROUND)
        coordinator.onVisibleConversationChanged(null)

        // Wire snapshots into coordinator (facade itself stays read-only).
        val wireStarted = CompletableDeferred<Unit>()
        val wireJob = launch {
            wireStarted.complete(Unit)
            stateAgg.observe().collect { coordinator.onSnapshot(it) }
        }
        withTimeout(5_000) { wireStarted.await() }

        val facade = ConversationFacade(
            conversationId = conv,
            state = stateAgg,
            trust = trustEngine,
            attention = coordinator,
        )

        // Sanity: snapshot() must be fast + side-effect-free.
        val snapCheck = async { facade.snapshot() }
        withTimeout(5_000) { snapCheck.await() }

        val collected = ArrayList<ConversationView>()
        val out = Channel<ConversationView>(capacity = Channel.UNLIMITED)
        val firstSeen = CompletableDeferred<Unit>()
        val collectionFailure = CompletableDeferred<Throwable?>()
        val collectJob = launch {
            try {
                facade.observe().collect { v ->
                    if (!firstSeen.isCompleted) firstSeen.complete(Unit)
                    collected.add(v)
                    out.trySend(v)
                }
            } catch (t: Throwable) {
                if (!collectionFailure.isCompleted) collectionFailure.complete(t)
                throw t
            }
        }
        collectJob.invokeOnCompletion { cause ->
            if (!collectionFailure.isCompleted) collectionFailure.complete(cause)
        }

        suspend fun awaitView(timeoutMs: Long, predicate: (ConversationView) -> Boolean): ConversationView {
            return withTimeout(timeoutMs) {
                while (true) {
                    val v = out.receive()
                    if (predicate(v)) return@withTimeout v
                }
                @Suppress("UNREACHABLE_CODE")
                error("unreachable")
            }
        }

        // 1) Baseline view.
        try {
            withTimeout(5_000) {
                select<Unit> {
                    firstSeen.onAwait { Unit }
                    collectionFailure.onAwait { cause ->
                        throw AssertionError("facade.observe() collector failed before baseline", cause)
                    }
                }
            }
        } catch (t: Throwable) {
            throw AssertionError("facade.observe() did not emit baseline", t)
        }
        val baseline = collected.first()
        assertEquals(conv, baseline.conversationId)
        assertEquals(0, baseline.unreadCount)

        // 2) State change: advance activity.
        store.appendOutbound("m1", conv, payload = byteArrayOf(1), elapsedMs = 10L)

        val afterState = awaitView(timeoutMs = 5_000) { it.lastActivityTimestamp >= 10L }
        assertEquals(ConversationAttentionState.FOREGROUND_BACKGROUND, afterState.attention)

        // 3) Trust change: identity key changes.
        identityStore.putPeerIdentityPublicKey(peerId, ByteArray(32) { 2 })
        trustInvalidations.tryEmit(Unit)

        val afterTrust = awaitView(timeoutMs = 5_000) { it.trust.issues.isNotEmpty() }
        assertTrue(afterTrust.trust.issues.isNotEmpty())

        // No-op invalidation should not emit (stateAgg is distinctUntilChanged).
        // Drain any in-flight updates first.
        while (out.tryReceive().isSuccess) {
            // keep draining
        }

        manual.tryEmit(Unit)

        val unexpected = withTimeoutOrNull(500) { out.receive() }
        assertEquals(null, unexpected)

        wireJob.cancel()
        collectJob.cancel()
    }

    @Test
    fun restore_produces_equivalent_view() = runBlocking {
        val conv = "c2"
        val peerId = "peer"

        val store1 = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger1 = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())

        val clock = TestClock(now = 0L)
        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        store1.appendOutbound("m1", conv, payload = byteArrayOf(1), elapsedMs = 10L)
        ledger1.recordEnqueued("m1", conv)

        val agg1 = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store1,
            ledger = ledger1,
            connectionStateProvider = connection,
            peerId = peerId,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store1.invalidations,
                ledger = ledger1.invalidations,
            ),
        )

        val trustStore1 = InMemoryConversationTrustStore()
        val identityStore1 = InMemoryIdentityKeyStore()
        identityStore1.putPeerIdentityPublicKey(peerId, ByteArray(32) { 1 })

        val trust1 = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore1,
            identityKeyStore = identityStore1,
            peerId = peerId,
        )
        trust1.verifyConversation()

        val coordinator1 = ConversationAttentionCoordinator(notificationSink = RecordingNotificationSink())
        coordinator1.onAppLifecycle(AppLifecycleState.FOREGROUND)
        coordinator1.onVisibleConversationChanged(null)

        val wire1 = launch { agg1.observe().collect { coordinator1.onSnapshot(it) } }

        val facade1 = ConversationFacade(
            conversationId = conv,
            state = agg1,
            trust = trust1,
            attention = coordinator1,
        )

        val v1 = ArrayList<ConversationView>()
        val j1 = launch { facade1.observe().take(1).toList(v1) }
        withTimeout(2_000) { j1.join() }
        val expected = v1.single()

        val storeSnap = store1.snapshot()
        val ledgerSnap = ledger1.snapshot()
        val trustSnap = trustStore1.snapshot(capturedAtElapsedMs = 123L)
        val identitySnap = identityStore1.snapshot()

        val store2 = ObservableConversationMessageStore(InMemoryConversationMessageStore())
        val ledger2 = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger())
        store2.restore(storeSnap)
        ledger2.restore(ledgerSnap)

        val agg2 = ConversationStateAggregator(
            conversationId = conv,
            messageStore = store2,
            ledger = ledger2,
            connectionStateProvider = connection,
            peerId = peerId,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store2.invalidations,
                ledger = ledger2.invalidations,
            ),
        )

        val trustStore2 = InMemoryConversationTrustStore()
        val identityStore2 = InMemoryIdentityKeyStore()
        trustStore2.restore(trustSnap)
        identityStore2.restore(identitySnap)

        val trust2 = ConversationTrustStateEngine(
            conversationId = conv,
            trustStore = trustStore2,
            identityKeyStore = identityStore2,
            peerId = peerId,
        )

        val coordinator2 = ConversationAttentionCoordinator(notificationSink = RecordingNotificationSink())
        coordinator2.onAppLifecycle(AppLifecycleState.FOREGROUND)
        coordinator2.onVisibleConversationChanged(null)

        val wire2 = launch { agg2.observe().collect { coordinator2.onSnapshot(it) } }

        val facade2 = ConversationFacade(
            conversationId = conv,
            state = agg2,
            trust = trust2,
            attention = coordinator2,
        )

        val v2 = ArrayList<ConversationView>()
        val j2 = launch { facade2.observe().take(1).toList(v2) }
        withTimeout(2_000) { j2.join() }

        assertEquals(expected, v2.single())

        wire1.cancel()
        wire2.cancel()
    }
}
