package com.ivor.kriptex.deliverypolicy.adversarial

import com.ivor.kriptex.deliverypolicy.ActiveDelivery
import com.ivor.kriptex.deliverypolicy.Clock
import com.ivor.kriptex.deliverypolicy.DeliveryMode
import com.ivor.kriptex.deliverypolicy.DeliveryStrategy
import com.ivor.kriptex.deliverypolicy.PassiveDelivery
import com.ivor.kriptex.deliverypolicy.connection.DefaultConnectionStateProvider
import com.ivor.kriptex.deliverypolicy.conversationattention.AppLifecycleState
import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionCoordinator
import com.ivor.kriptex.deliverypolicy.conversationattention.NotificationSink
import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationFacade
import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationFacadeDebugTrace
import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationView
import com.ivor.kriptex.deliverypolicy.conversationinvariants.ConversationInvariantValidator
import com.ivor.kriptex.deliverypolicy.conversationinvariants.ConversationInvariantViolation
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
import com.ivor.kriptex.deliverypolicy.conversationtruststate.PersistedIdentityKeyStoreSnapshot
import com.ivor.kriptex.deliverypolicy.decision.DeliveryDecisionEngine
import com.ivor.kriptex.deliverypolicy.diagnostics.DefaultMessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.MessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.diagnostics.NoOpMessageOutboxDebugTrace
import com.ivor.kriptex.deliverypolicy.group.GroupDefinition
import com.ivor.kriptex.deliverypolicy.group.GroupId
import com.ivor.kriptex.deliverypolicy.group.InMemoryGroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.InMemorySenderKeyStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.InMemorySenderKeyDistributionStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.distribution.SenderKeyDistributionEngine
import com.ivor.kriptex.deliverypolicy.ledger.InMemoryConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.messagestore.InMemoryConversationMessageStore
import com.ivor.kriptex.deliverypolicy.messagestore.adapters.ConversationMessageStoreOutboxAdapter
import com.ivor.kriptex.deliverypolicy.outbox.DefaultMessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryAttemptResult
import com.ivor.kriptex.deliverypolicy.outbox.DeliveryStrategySender
import com.ivor.kriptex.deliverypolicy.outbox.MessageOutbox
import com.ivor.kriptex.deliverypolicy.outbox.OutgoingMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationDeliveryLedgerSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessageStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedGroupStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedMessageOutboxSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyDistributionSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSenderKeyStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedSessionProtocolEngineSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationTrustSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedLedgerState
import com.ivor.kriptex.deliverypolicy.protocol.AckMessage
import com.ivor.kriptex.deliverypolicy.protocol.BinaryProtocolCodec
import com.ivor.kriptex.deliverypolicy.protocol.InMemoryProtocolInboundPipeline
import com.ivor.kriptex.deliverypolicy.protocol.IncrementingMessageIdGenerator
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SessionAeadAlgorithm
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage
import com.ivor.kriptex.deliverypolicy.routing.DefaultProtocolMessageRouter
import com.ivor.kriptex.deliverypolicy.routing.adapters.SenderKeyDistributionEngineAdapter
import com.ivor.kriptex.deliverypolicy.routing.adapters.SenderKeyGroupMessageEngineAdapter
import com.ivor.kriptex.deliverypolicy.routing.outbound.SessionBoundOutboundEnqueuer
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import com.ivor.kriptex.deliverypolicy.session.IncrementingSessionIdGenerator
import com.ivor.kriptex.deliverypolicy.session.SessionAeadSupport
import com.ivor.kriptex.deliverypolicy.session.SessionAwareProtocolEngine
import com.ivor.kriptex.deliverypolicy.session.SessionBoundProtocolOutbound
import com.ivor.kriptex.deliverypolicy.session.ratchet.RatchetState
import com.ivor.kriptex.deliverypolicy.session.routing.SessionHandshakeHandler
import com.ivor.kriptex.deliverypolicy.session.x3dh.InMemoryX3dhPreKeyStore
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhCrypto
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhOneTimePreKey
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhPreKeyBundle
import com.ivor.kriptex.deliverypolicy.session.x3dh.X3dhSignedPreKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.fail
import java.util.concurrent.atomic.AtomicInteger

private class TestClock(var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now
    fun tick(deltaMs: Long = 1L) {
        now += deltaMs
    }
}

private object AesOnlySupport : SessionAeadSupport {
    override fun preferred(): SessionAeadAlgorithm = SessionAeadAlgorithm.AES_256_GCM
    override fun supports(algorithm: SessionAeadAlgorithm): Boolean = algorithm == SessionAeadAlgorithm.AES_256_GCM
}

private class NoOpNotificationSink : NotificationSink {
    override fun showNotification(conversationId: String, snapshot: com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot) = Unit
    override fun cancelNotification(conversationId: String) = Unit
}

private class MutableDecisionEngine(initial: DeliveryStrategy) : DeliveryDecisionEngine {
    private val listeners = LinkedHashSet<(DeliveryStrategy) -> Unit>()
    private val _flow = MutableStateFlow(initial)
    var onStrategyEmitted: ((DeliveryStrategy) -> Unit)? = null

    override val strategy: DeliveryStrategy
        get() = _flow.value

    override val strategyFlow: StateFlow<DeliveryStrategy>
        get() = _flow.asStateFlow()

    override fun addListener(listener: (DeliveryStrategy) -> Unit): () -> Unit {
        listeners.add(listener)
        listener(strategy)
        return { listeners.remove(listener) }
    }

    override fun close() {
        listeners.clear()
    }

    fun emit(newStrategy: DeliveryStrategy) {
        if (newStrategy == strategy) return
        _flow.value = newStrategy
        listeners.toList().forEach { it(newStrategy) }
        onStrategyEmitted?.invoke(newStrategy)
    }
}

private data class InFlightPacket(
    val id: String,
    val from: ConversationScenario.Actor,
    val to: ConversationScenario.Actor,
    val bytes: ByteArray,
    val messageId: String?,
    val conversationId: String?,
    val kind: PacketKind,
    val ackedMessageId: String?,
    val source: PacketSource,
    /** Snapshot of all active cut ids at send time (supports nested cuts). */
    val cutIdsAtSend: List<String>,
)

private enum class PacketSource {
    SYSTEM,
    INJECTED,
}

private enum class PacketKind {
    USER,
    ACK,
    OTHER,
}

private class DeterministicNetwork {
    private val nextId = AtomicInteger(1)
    private val packets = ArrayList<InFlightPacket>()

    private data class NetworkCut(val id: String, var active: Boolean)

    private val cutStack = ArrayDeque<String>()
    private val cutsById = LinkedHashMap<String, NetworkCut>()
    private val cutEvents = ArrayList<String>(32)
    private var cutEventSeq = 0

    private fun record(event: String) {
        cutEventSeq++
        cutEvents.add("#" + cutEventSeq + ":" + event)
    }

    fun startCut(cutId: String) {
        val existing = cutsById[cutId]
        require(existing == null || !existing.active) { "cut_already_active($cutId)" }
        cutsById[cutId] = NetworkCut(id = cutId, active = true)
        cutStack.addLast(cutId)
        record("CUT_START($cutId)")
    }

    fun endCut(cutId: String) {
        require(cutStack.isNotEmpty()) { "no_active_cut" }
        val top = cutStack.removeLast()
        require(top == cutId) { "cut_end_not_top_of_stack(expected=$top actual=$cutId)" }
        val c = cutsById[cutId] ?: error("missing_cut($cutId)")
        c.active = false
        record("CUT_END($cutId)")

        // Adversarial release: packets that become newly deliverable now get moved to the front
        // in reverse order (deterministic, worst-case).
        val newlyUnblocked = ArrayList<InFlightPacket>()
        val it = packets.iterator()
        while (it.hasNext()) {
            val pkt = it.next()
            if (!pkt.cutIdsAtSend.contains(cutId)) continue
            if (!isDeliverable(pkt)) continue
            it.remove()
            newlyUnblocked.add(pkt)
        }
        if (newlyUnblocked.isNotEmpty()) {
            newlyUnblocked.reverse()
            packets.addAll(0, newlyUnblocked)
            record("CUT_RELEASE($cutId,count=${newlyUnblocked.size})")
        }
    }

    fun reorderAcrossCuts(windowSize: Int) {
        if (windowSize <= 1) return
        if (packets.size < windowSize) return
        val head = packets.subList(0, windowSize)
        head.reverse()
        record("REORDER_ACROSS_CUTS($windowSize)")
    }

    private fun isDeliverable(pkt: InFlightPacket): Boolean {
        // Deliverable only when all cuts that were active at send time are inactive.
        for (id in pkt.cutIdsAtSend) {
            val c = cutsById[id]
            if (c != null && c.active) return false
        }
        return true
    }

    fun inFlightCount(): Int = packets.size

    fun hasActiveCuts(): Boolean = cutStack.isNotEmpty()

    fun activeCutStack(): List<String> = cutStack.toList()

    fun endTopCut(): String {
        require(cutStack.isNotEmpty()) { "no_active_cut" }
        val id = cutStack.last()
        endCut(id)
        return id
    }

    fun popFirstDeliverable(): InFlightPacket? {
        val idx = packets.indexOfFirst { isDeliverable(it) }
        if (idx < 0) return null
        return packets.removeAt(idx)
    }

    fun hasDeliverableOfKind(kind: PacketKind): Boolean {
        return packets.any { isDeliverable(it) && it.kind == kind }
    }

    fun enqueue(
        from: ConversationScenario.Actor,
        to: ConversationScenario.Actor,
        bytes: ByteArray,
        messageId: String?,
        conversationId: String?,
        kind: PacketKind,
        ackedMessageId: String? = null,
        source: PacketSource = PacketSource.SYSTEM,
    ): ConversationScenario.PacketHandle {
        val id = "p" + nextId.getAndIncrement()
        val cutIds = cutStack.toList()
        packets.add(
            InFlightPacket(
                id = id,
                from = from,
                to = to,
                bytes = bytes,
                messageId = messageId,
                conversationId = conversationId,
                kind = kind,
                ackedMessageId = ackedMessageId,
                source = source,
                cutIdsAtSend = cutIds,
            ),
        )
        return ConversationScenario.PacketHandle(id = id, from = from, to = to, messageId = messageId)
    }

    fun removeByMessageId(messageId: String): InFlightPacket? {
        val idx = packets.indexOfFirst { it.messageId == messageId }
        if (idx < 0) return null
        val pkt = packets[idx]
        if (!isDeliverable(pkt)) return null
        return packets.removeAt(idx)
    }

    fun find(handle: ConversationScenario.PacketHandle): InFlightPacket? {
        val byId = packets.firstOrNull { it.id == handle.id }
        if (byId != null) return byId

        val mid = handle.messageId
        if (mid != null) {
            return packets.firstOrNull { it.messageId == mid && it.from == handle.from && it.to == handle.to }
        }
        return null
    }

    fun remove(handle: ConversationScenario.PacketHandle): InFlightPacket? {
        val byId = packets.indexOfFirst { it.id == handle.id }
        if (byId >= 0) {
            val pkt = packets[byId]
            if (!isDeliverable(pkt)) return null
            return packets.removeAt(byId)
        }

        val mid = handle.messageId
        if (mid != null) {
            val byMsg = packets.indexOfFirst { it.messageId == mid && it.from == handle.from && it.to == handle.to }
            if (byMsg >= 0) {
                val pkt = packets[byMsg]
                if (!isDeliverable(pkt)) return null
                return packets.removeAt(byMsg)
            }
        }
        return null
    }

    fun duplicate(handle: ConversationScenario.PacketHandle, times: Int) {
        val pkt = find(handle) ?: return
        repeat(times - 1) {
            packets.add(pkt.copy(id = pkt.id + ":dup" + (it + 1)))
        }
    }

    fun snapshotPacketIds(): List<String> = packets.map { it.id }

    fun dumpCutsAndQueues(): String {
        val active = cutStack.toList()
        val allCuts = cutsById.values.joinToString(",") { it.id + "=" + (if (it.active) "active" else "ended") }
        val ready = packets.filter { isDeliverable(it) }.map { it.id }
        val blockedByCut = LinkedHashMap<String, MutableList<String>>()
        for (pkt in packets) {
            if (isDeliverable(pkt)) continue
            val blocking = pkt.cutIdsAtSend.firstOrNull { cutsById[it]?.active == true } ?: "unknown"
            blockedByCut.getOrPut(blocking) { ArrayList() }.add(pkt.id)
        }

        return buildString {
            appendLine("CUTS activeStack=$active all={$allCuts}")
            appendLine("CUT_EVENTS")
            cutEvents.forEach { appendLine("  $it") }
            appendLine("QUEUES")
            appendLine("  ready=$ready")
            blockedByCut.forEach { (k, v) -> appendLine("  blocked[$k]=$v") }
        }
    }

    fun reorderFirstWindow(size: Int) {
        if (size <= 1) return
        if (packets.size < size) return
        val head = packets.subList(0, size)
        head.reverse()
    }
}

private class RecordingStrategySender(
    private val who: ConversationScenario.Actor,
    private val network: DeterministicNetwork,
    private val codec: BinaryProtocolCodec,
    private val clock: TestClock,
    private val ledger: ObservableConversationDeliveryLedger,
) : DeliveryStrategySender {

    override fun attemptSend(strategy: DeliveryStrategy, message: OutgoingMessage): DeliveryAttemptResult {
        val decoded = decodeMessage(message.payload)
        val isTrackable = when (decoded) {
            is UserMessage -> true
            is SenderKeyDistributionMessage -> true
            is com.ivor.kriptex.deliverypolicy.protocol.SenderKeyGroupMessage -> true
            else -> false
        }

        if (isTrackable) {
            ledger.recordEnqueued(message.messageId, message.chatId)
        }

        return when (strategy.mode) {
            DeliveryMode.ACTIVE -> {
                if (isTrackable) ledger.recordSent(message.messageId)
                val kind = when (decoded) {
                    is UserMessage -> PacketKind.USER
                    is AckMessage -> PacketKind.ACK
                    else -> PacketKind.OTHER
                }
                val acked = (decoded as? AckMessage)?.ackedMessageId
                network.enqueue(
                    from = who,
                    to = if (who == ConversationScenario.Actor.A) ConversationScenario.Actor.B else ConversationScenario.Actor.A,
                    bytes = message.payload,
                    messageId = message.messageId,
                    conversationId = message.chatId,
                    kind = kind,
                    ackedMessageId = acked,
                )
                DeliveryAttemptResult.Accepted
            }

            DeliveryMode.PASSIVE -> DeliveryAttemptResult.Deferred("passive")
        }
    }

    private fun decodeMessage(payload: ByteArray): ProtocolMessage? {
        // Attempt to decode as session envelope v1 (plaintext inner) or raw protocol.
        return try {
            val engine = com.ivor.kriptex.deliverypolicy.session.SessionEnvelopeCodec()
            if (engine.looksLikeEnvelope(payload)) {
                val env = engine.decode(payload)
                if (env.messageId != null) return null // encrypted envelope; not decodable here
                codec.decode(env.inner)
            } else {
                codec.decode(payload)
            }
        } catch (_: Exception) {
            null
        }
    }
}

private data class NodeSnapshots(
    val messageStore: PersistedConversationMessageStoreSnapshot?,
    val ledger: PersistedConversationDeliveryLedgerSnapshot?,
    val outbox: PersistedMessageOutboxSnapshot?,
    val sessionEngine: PersistedSessionProtocolEngineSnapshot?,
    val groupStore: PersistedGroupStoreSnapshot?,
    val senderKeyStore: PersistedSenderKeyStoreSnapshot?,
    val senderKeyDistributionStore: PersistedSenderKeyDistributionSnapshot?,
    val trustStore: PersistedConversationTrustSnapshot?,
    val identityKeyStore: PersistedIdentityKeyStoreSnapshot?,
)

private data class NodeRuntime(
    val actor: ConversationScenario.Actor,
    val peerId: String,
    val conversationId: String,
    val clock: TestClock,
    val codec: BinaryProtocolCodec,
    val identityPublicKey: ByteArray,
    val peerBundle: X3dhPreKeyBundle,
    val decision: MutableDecisionEngine,
    val store: ObservableConversationMessageStore,
    val ledger: ObservableConversationDeliveryLedger,
    val groupStore: ObservableGroupStore,
    val senderKeyStore: ObservableSenderKeyStore,
    val senderKeyDistributionStore: InMemorySenderKeyDistributionStore,
    val senderKeyDistributionEngine: SenderKeyDistributionEngine,
    val senderKeyGroupEngine: com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane.SenderKeyGroupMessageEngine,
    val trustStore: InMemoryConversationTrustStore,
    val identityKeyStore: InMemoryIdentityKeyStore,
    val sessionStore: InMemorySessionStore,
    val engine: SessionAwareProtocolEngine,
    val outbox: MessageOutbox,
    val outboxDebugTrace: MessageOutboxDebugTrace,
    val attention: ConversationAttentionCoordinator,
    val notificationSink: RecordingNotificationSink,
    val connection: DefaultConnectionStateProvider,
    val stateAgg: ConversationStateAggregator,
    val trustEngine: ConversationTrustStateEngine,
    val facade: ConversationFacade,
    val sessionCryptoDebugTrace: RecordingSessionCryptoDebugTrace,
    val viewChannel: Channel<ConversationView>,
    val wireJob: Job,
    val viewJob: Job,
) {
    fun snapshotTargets(targets: Set<ConversationScenario.RestoreTarget>): NodeSnapshots {
        return NodeSnapshots(
            messageStore = if (targets.contains(ConversationScenario.RestoreTarget.MESSAGE_STORE)) store.snapshot() else null,
            ledger = if (targets.contains(ConversationScenario.RestoreTarget.LEDGER)) ledger.snapshot() else null,
            outbox = if (targets.contains(ConversationScenario.RestoreTarget.OUTBOX)) outbox.snapshot() else null,
            sessionEngine = if (targets.contains(ConversationScenario.RestoreTarget.SESSION_PROTOCOL_ENGINE)) engine.snapshot() else null,
            groupStore = if (targets.contains(ConversationScenario.RestoreTarget.GROUP_STORE)) groupStore.snapshot() else null,
            senderKeyStore = if (targets.contains(ConversationScenario.RestoreTarget.SENDER_KEY_STORE)) senderKeyStore.snapshot() else null,
            senderKeyDistributionStore = if (targets.contains(ConversationScenario.RestoreTarget.SENDER_KEY_DISTRIBUTION_STORE)) senderKeyDistributionStore.snapshot() else null,
            trustStore = if (targets.contains(ConversationScenario.RestoreTarget.TRUST_STORE)) trustStore.snapshot(capturedAtElapsedMs = clock.nowMs()) else null,
            identityKeyStore = if (targets.contains(ConversationScenario.RestoreTarget.IDENTITY_KEY_STORE)) identityKeyStore.snapshot() else null,
        )
    }
}

/**
 * Deterministic, controlled-chaos scenario runner.
 *
 * - Uses real outbox/protocol/session/store/ledger/trust/attention engines.
 * - Owns a deterministic in-flight packet queue (no sleeps, no fuzzing).
 * - Validates invariants after every step.
 */
class ConversationScenarioRunner(
    private val validator: ConversationInvariantValidator = ConversationInvariantValidator(),
    private val enableDiagnostics: Boolean = false,
    private val maxDiagnosticLines: Int = 2_000,
    private val safetyContract: (
        stepIndex: Int,
        stepLabel: String,
        actor: ConversationScenario.Actor,
        previousView: ConversationView,
        nextView: ConversationView,
        nextStore: PersistedConversationMessageStoreSnapshot,
        nextLedger: PersistedConversationDeliveryLedgerSnapshot,
        sessionDecryptSucceededMessageIds: Set<String>,
        restore: ConversationSafetyContract.RestoreContext,
    ) -> List<ConversationSafetyContract.ViolationEvent> = ConversationSafetyContract::checkAfterStep,
) {

    fun runScenario(scenario: ConversationScenario) = runBlocking {
        val network = DeterministicNetwork()
        val diagnostics = if (enableDiagnostics) StringBuilder(8_192) else null
        val facadeTraces = if (enableDiagnostics) StringBuilder(8_192) else null
        var diagnosticLines = 0

        val conversationId = scenario.steps
            .asSequence()
            .mapNotNull {
                when (it) {
                    is ConversationScenario.SendOutbound -> it.conversationId
                    is ConversationScenario.InjectAck -> it.conversationId
                    else -> null
                }
            }
            .firstOrNull() ?: "c_adv"

        val a = newNode(ConversationScenario.Actor.A, peerId = "B", conversationId = conversationId, network = network, facadeTrace = facadeTraces)
        val b = newNode(ConversationScenario.Actor.B, peerId = "A", conversationId = conversationId, network = network, facadeTrace = facadeTraces)

        val usesSenderKey = scenario.steps.any { s ->
            (s as? ConversationScenario.SendOutbound)?.protocol is ConversationScenario.OutboundProtocol.SenderKeyDistributionPlanned ||
                (s as? ConversationScenario.SendOutbound)?.protocol is ConversationScenario.OutboundProtocol.SenderKeyDistribution ||
                (s as? ConversationScenario.SendOutbound)?.protocol is ConversationScenario.OutboundProtocol.SenderKeyGroupMessage
        }

        if (usesSenderKey) {
            val members = listOf(a.identityPublicKey, b.identityPublicKey)
            val groupId = GroupId.fromConversationId(conversationId)
            val def = GroupDefinition(conversationId = conversationId, groupId = groupId, memberIdentityPublicKeys = members)
            a.groupStore.put(def)
            b.groupStore.put(def)

            // Make group encryption derivation stable: local sender key exists for both.
            a.senderKeyDistributionEngine.getOrCreateLocalSenderKey(groupId)
            b.senderKeyDistributionEngine.getOrCreateLocalSenderKey(groupId)
        }

        // Establish sessions upfront; scenarios focus on adversarial ordering/restore.
        establishSession(a, b, network)

        // If this is a group scenario, tests can put groups into both stores via helper.

        var runtimeA = a
        var runtimeB = b

        val lastViews = hashMapOf(
            ConversationScenario.Actor.A to awaitBaseline(runtimeA),
            ConversationScenario.Actor.B to awaitBaseline(runtimeB),
        )

        lastViews.values.forEach { assertNoViolations(validator.validate(it)) }

        fun ledgerOrdinal(s: PersistedLedgerState): Int = when (s) {
            PersistedLedgerState.QUEUED -> 0
            PersistedLedgerState.SENT -> 1
            PersistedLedgerState.RECEIVED -> 2
            PersistedLedgerState.ACKED -> 3
            PersistedLedgerState.FAILED_TERMINAL -> 4
        }

        val enableResourceAndLivenessValidation = scenario.enableResourceAndLivenessValidation

        data class StepEffects(
            val replayDeliveredToA: Boolean = false,
            val replayDeliveredToB: Boolean = false,
            val newDeliveredToA: Int = 0,
            val newDeliveredToB: Int = 0,
            val sentOutboundFromA: Int = 0,
            val sentOutboundFromB: Int = 0,
            val isRestore: Boolean = false,
        )

        data class ResourceSample(
            val uniqueMessageIds: Int,
            val outboxPending: Int,
            val storeTimelineCount: Int,
            val ledgerEntries: Int,
            val inboundProcessedIds: Int,
            val inboundBufferedIds: Int,
            val inboundIndexMapsTotal: Int,
            val pendingOutboundControlMessages: Int,
            val sessionSkippedKeys: Int,
            val sessionInboundMessageIdsSeen: Int,
            val sessions: Int,
        )

        fun captureResources(rt: NodeRuntime): ResourceSample {
            val outbox = rt.outbox.snapshot()
            val store = rt.store.snapshot()
            val ledger = rt.ledger.snapshot()
            val engine = rt.engine.snapshot()

            val inbound = engine.protocolInbound
            val sessions = engine.sessionStore.sessions
            val skipped = sessions.sumOf { it.ratchet?.skippedKeys?.size ?: 0 }
            val inboundSeen = sessions.sumOf { it.inboundMessageIdsSeen.size }

            val storeTl = store.conversations[rt.conversationId]?.orderedMessageIds ?: emptyList()
            val storeTimelineCount = storeTl.size

            val inboundIndexMapsTotal =
                inbound.nextReceiveIndexByConversation.size +
                    inbound.receivedIndexByMessageId.size +
                    inbound.conversationIdByMessageId.size +
                    inbound.receivedIndexByMessageId.size +
                    inbound.typeByMessageId.size

            val unique = LinkedHashSet<String>(256)
            ledger.entries.forEach { unique.add(it.messageId) }
            storeTl.forEach { unique.add(it) }
            inbound.processedMessageIds.forEach { unique.add(it) }
            inbound.receivedIndexByMessageId.keys.forEach { unique.add(it) }
            sessions.forEach { s -> s.inboundMessageIdsSeen.forEach { unique.add(it) } }

            return ResourceSample(
                uniqueMessageIds = unique.size,
                outboxPending = outbox.messages.size,
                storeTimelineCount = storeTimelineCount,
                ledgerEntries = ledger.entries.size,
                inboundProcessedIds = inbound.processedMessageIds.size,
                inboundBufferedIds = inbound.receivedIndexByMessageId.size,
                inboundIndexMapsTotal = inboundIndexMapsTotal,
                pendingOutboundControlMessages = inbound.pendingOutboundEncodedMessages.size,
                sessionSkippedKeys = skipped,
                sessionInboundMessageIdsSeen = inboundSeen,
                sessions = sessions.size,
            )
        }

        val deliveredMessageIds = hashMapOf(
            ConversationScenario.Actor.A to mutableSetOf<String>(),
            ConversationScenario.Actor.B to mutableSetOf<String>(),
        )

        // Test-only liveness step config, keyed by fully-qualified stepLabel.
        val livenessConfigByStepLabel = HashMap<String, Int>()

        // Diagnostics: per-step resource and strategy traces.
        val resourceTrace = if (enableDiagnostics || enableResourceAndLivenessValidation) StringBuilder(8_192) else null
        val strategyTrace = if (enableDiagnostics || enableResourceAndLivenessValidation) StringBuilder(8_192) else null
        val lifecycleByActor = hashMapOf(
            ConversationScenario.Actor.A to LinkedHashMap<String, MutableList<String>>(),
            ConversationScenario.Actor.B to LinkedHashMap<String, MutableList<String>>(),
        )

        var currentStepForTraces: String = "(init)"

        // Ensure notification sink events are tagged with the current step label.
        runtimeA.notificationSink.stepLabelProvider = { currentStepForTraces }
        runtimeB.notificationSink.stepLabelProvider = { currentStepForTraces }

        // New invariants state.
        var prevStoreA = runtimeA.store.snapshot()
        var prevStoreB = runtimeB.store.snapshot()
        var prevLedgerA = runtimeA.ledger.snapshot()
        var prevLedgerB = runtimeB.ledger.snapshot()

        var skipStoreInvariantA = false
        var skipStoreInvariantB = false
        var skipLedgerInvariantA = false
        var skipLedgerInvariantB = false

        // Protocol-level ACK ordering (new invariant): keep track of which USER messageIds have been
        // actually delivered to each actor.
        val receivedUserMessageIds = hashMapOf(
            ConversationScenario.Actor.A to mutableSetOf<String>(),
            ConversationScenario.Actor.B to mutableSetOf<String>(),
        )

        val lastStateByActor = hashMapOf(
            ConversationScenario.Actor.A to HashMap<String, PersistedLedgerState>(),
            ConversationScenario.Actor.B to HashMap<String, PersistedLedgerState>(),
        )
        fun seedLastStates(actor: ConversationScenario.Actor, snap: PersistedConversationDeliveryLedgerSnapshot) {
            val m = lastStateByActor.getValue(actor)
            for (e in snap.entries) m[e.messageId] = e.state
        }
        seedLastStates(ConversationScenario.Actor.A, prevLedgerA)
        seedLastStates(ConversationScenario.Actor.B, prevLedgerB)

        // Initialize strategy tracing (test-only).
        val lastStrategyTransitions = hashMapOf(
            ConversationScenario.Actor.A to ArrayDeque<String>(16),
            ConversationScenario.Actor.B to ArrayDeque<String>(16),
        )

        val lastConnectionTransitions = hashMapOf(
            ConversationScenario.Actor.A to ArrayDeque<String>(16),
            ConversationScenario.Actor.B to ArrayDeque<String>(16),
        )

        fun pushLast(q: ArrayDeque<String>, s: String, cap: Int = 16) {
            if (q.size >= cap) q.removeFirst()
            q.addLast(s)
        }

        fun hashMessageId(messageId: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val dig = md.digest(messageId.toByteArray(Charsets.UTF_8))
            return dig.take(6).joinToString("") { b -> "%02x".format(b) }
        }

        // Safety Contract transient violation tracking.
        val safetyTracker = SafetyContractViolationTracker()
        val completedStepLabels = ArrayList<String>(scenario.steps.size)
        var earliestSafetyEvidence: String? = null

        fun summarizeStore(snapshot: PersistedConversationMessageStoreSnapshot, related: Set<String>): String {
            val messageCount = snapshot.messages.size
            val conversationCount = snapshot.conversations.size
            val timelineCount = snapshot.conversations.values.sumOf { it.orderedMessageIds.size }
            val relatedLines = related.mapNotNull { id ->
                val m = snapshot.messages[id] ?: return@mapNotNull null
                "  id=${hashMessageId(id)} dir=${m.direction} state=${m.state} sendIndex=${m.sendIndex} recvIndex=${m.receiveIndex}"
            }
            return buildString {
                append("store: conversations=$conversationCount messages=$messageCount timelineIds=$timelineCount")
                if (relatedLines.isNotEmpty()) {
                    append("\nstore.related:\n")
                    relatedLines.sorted().forEach { appendLine(it) }
                }
            }
        }

        fun summarizeLedger(snapshot: PersistedConversationDeliveryLedgerSnapshot, related: Set<String>): String {
            val entryCount = snapshot.entries.size
            val acked = snapshot.entries.count { it.state == PersistedLedgerState.ACKED }
            val received = snapshot.entries.count { it.state == PersistedLedgerState.RECEIVED }
            val sent = snapshot.entries.count { it.state == PersistedLedgerState.SENT }
            val queued = snapshot.entries.count { it.state == PersistedLedgerState.QUEUED }
            val failed = snapshot.entries.count { it.state == PersistedLedgerState.FAILED_TERMINAL }
            val relatedLines = related.mapNotNull { id ->
                val e = snapshot.entries.firstOrNull { it.messageId == id } ?: return@mapNotNull null
                "  id=${hashMessageId(id)} idx=${e.index} state=${e.state}"
            }
            return buildString {
                append("ledger: entries=$entryCount acked=$acked received=$received sent=$sent queued=$queued failed=$failed")
                if (relatedLines.isNotEmpty()) {
                    append("\nledger.related:\n")
                    relatedLines.sorted().forEach { appendLine(it) }
                }
            }
        }

        runtimeA.decision.onStrategyEmitted = { s ->
            strategyTrace?.appendLine("STEP $currentStepForTraces STRATEGY A ${s.mode}")
            pushLast(lastStrategyTransitions.getValue(ConversationScenario.Actor.A), "STEP $currentStepForTraces ${s.mode}")
        }
        runtimeB.decision.onStrategyEmitted = { s ->
            strategyTrace?.appendLine("STEP $currentStepForTraces STRATEGY B ${s.mode}")
            pushLast(lastStrategyTransitions.getValue(ConversationScenario.Actor.B), "STEP $currentStepForTraces ${s.mode}")
        }

        // Initialize connection tracing (test-only).
        runtimeA.connection.addListener { st ->
            pushLast(lastConnectionTransitions.getValue(ConversationScenario.Actor.A), "STEP $currentStepForTraces $st")
        }
        runtimeB.connection.addListener { st ->
            pushLast(lastConnectionTransitions.getValue(ConversationScenario.Actor.B), "STEP $currentStepForTraces $st")
        }

        var lastStepEffects: StepEffects = StepEffects()
        var lastRestoreTargets: Set<ConversationScenario.RestoreTarget> = emptySet()
        var prevResA: ResourceSample = captureResources(runtimeA)
        var prevResB: ResourceSample = captureResources(runtimeB)

        fun assertStorePrefixConsistent(prev: PersistedConversationMessageStoreSnapshot, next: PersistedConversationMessageStoreSnapshot) {
            for ((cid, prevTl) in prev.conversations) {
                val nextTl = next.conversations[cid] ?: continue
                val a = prevTl.orderedMessageIds
                val b = nextTl.orderedMessageIds
                if (a.size > b.size) {
                    throw AssertionError("store_timeline_shrunk(conversationId=$cid prev=${a.size} next=${b.size})")
                }
                for (i in a.indices) {
                    if (a[i] != b[i]) {
                        throw AssertionError("store_timeline_not_prefix(conversationId=$cid idx=$i prevId=${a[i]} nextId=${b[i]})")
                    }
                }
                if (b.size != b.distinct().size) {
                    throw AssertionError("store_timeline_has_duplicates(conversationId=$cid size=${b.size})")
                }
            }
        }

        fun assertLedgerMonotonic(actor: ConversationScenario.Actor, next: PersistedConversationDeliveryLedgerSnapshot) {
            // Indices must be unique and sorted (monotonic timeline).
            val indices = next.entries.map { it.index }
            if (indices.size != indices.distinct().size) {
                throw AssertionError("ledger_indices_not_unique(actor=$actor)")
            }
            if (indices != indices.sorted()) {
                throw AssertionError("ledger_indices_not_monotonic(actor=$actor)")
            }

            // Per-message state must not regress.
            val lastMap = lastStateByActor.getValue(actor)
            for (e in next.entries) {
                val prev = lastMap[e.messageId]
                if (prev != null && ledgerOrdinal(e.state) < ledgerOrdinal(prev)) {
                    throw AssertionError("ledger_state_regressed(actor=$actor messageId=${e.messageId} prev=$prev next=${e.state})")
                }
                lastMap[e.messageId] = e.state
            }
        }

        fun summarizeView(v: ConversationView): String {
            return "attn=${v.attention} unread=${v.unreadCount} last=${v.lastActivityTimestamp} health=${v.snapshot.health} enc=${v.snapshot.encryptionStatus} pending=${v.snapshot.pendingMessageCount}"
        }

        suspend fun validateAfterStep(stepLabel: String, stepIndex: Int) {
            currentStepForTraces = stepLabel

            // If this is a liveness assertion step, perform a deterministic fairness drain first.
            // This mutates state and will emit additional view updates, which we then validate normally.
            livenessConfigByStepLabel[stepLabel]?.let { maxDrainMultiplier ->
                if (enableResourceAndLivenessValidation) {
                    // Force fairness for delivery strategy.
                    runtimeA.decision.emit(ActiveDelivery)
                    runtimeB.decision.emit(ActiveDelivery)

                    // Fairness: every cut eventually ends.
                    while (network.hasActiveCuts()) {
                        network.endTopCut()
                    }

                    val maxDrainSteps = (network.inFlightCount() * maxDrainMultiplier).coerceAtLeast(16)
                    var drainSteps = 0
                    var consecutiveAckWhileUserDeliverable = 0
                    var consecutiveUserWhileAckDeliverable = 0
                    val maxSkew = 8 * maxOf(1, maxDrainMultiplier)

                    while (true) {
                        if (drainSteps > maxDrainSteps) {
                            throw AssertionError("liveness_fairness_drain_exceeded_budget(steps=$drainSteps max=$maxDrainSteps inFlight=${network.inFlightCount()})")
                        }
                        val deliverableUserExists = network.hasDeliverableOfKind(PacketKind.USER)
                        val deliverableAckExists = network.hasDeliverableOfKind(PacketKind.ACK)
                        val pkt = network.popFirstDeliverable() ?: break
                        drainSteps++

                        if (deliverableUserExists && pkt.kind == PacketKind.ACK) {
                            consecutiveAckWhileUserDeliverable++
                        } else {
                            consecutiveAckWhileUserDeliverable = 0
                        }
                        if (deliverableAckExists && pkt.kind == PacketKind.USER) {
                            consecutiveUserWhileAckDeliverable++
                        } else {
                            consecutiveUserWhileAckDeliverable = 0
                        }
                        if (consecutiveAckWhileUserDeliverable > maxSkew) {
                            throw AssertionError("starvation_ack_starves_user(maxSkew=$maxSkew)")
                        }
                        if (consecutiveUserWhileAckDeliverable > maxSkew) {
                            throw AssertionError("starvation_user_starves_ack(maxSkew=$maxSkew)")
                        }

                        // New invariant: no message is ACKed before it is RECEIVED (SYSTEM ACKs only).
                        if (pkt.kind == PacketKind.ACK && pkt.source == PacketSource.SYSTEM) {
                            val acked = pkt.ackedMessageId
                            if (acked != null && !receivedUserMessageIds.getValue(pkt.from).contains(acked)) {
                                throw AssertionError("ack_delivered_before_user_received(ackFrom=${pkt.from} ackedMessageId=$acked)")
                            }
                        }

                        val receiver = if (pkt.to == ConversationScenario.Actor.A) runtimeA else runtimeB
                        val sender = if (pkt.from == ConversationScenario.Actor.A) runtimeA else runtimeB

                        receiver.clock.tick(1)
                        receiver.engine.onInboundBytes(pkt.bytes, receivedAtElapsedMs = receiver.clock.nowMs(), peerId = receiver.peerId)

                        pkt.messageId?.let { mid ->
                            deliveredMessageIds.getValue(pkt.to).add(mid)
                        }
                        if (pkt.kind == PacketKind.USER && pkt.messageId != null) {
                            receivedUserMessageIds.getValue(pkt.to).add(pkt.messageId)
                        }
                        pkt.messageId?.let { sender.outbox.notifyDelivered(it) }
                    }

                    // Liveness: after fairness drain, no message should remain non-terminal.
                    fun isTerminal(s: PersistedLedgerState): Boolean {
                        return s == PersistedLedgerState.ACKED || s == PersistedLedgerState.FAILED_TERMINAL || s == PersistedLedgerState.RECEIVED
                    }

                    val la = runtimeA.ledger.snapshot()
                    val lb = runtimeB.ledger.snapshot()
                    val nonTerminalA = la.entries.filter { !isTerminal(it.state) }
                    val nonTerminalB = lb.entries.filter { !isTerminal(it.state) }
                    if (nonTerminalA.isNotEmpty() || nonTerminalB.isNotEmpty()) {
                        throw AssertionError(
                            "liveness_non_terminal_messages_remain(A=${nonTerminalA.size} B=${nonTerminalB.size})",
                        )
                    }

                    val oa = runtimeA.outbox.snapshot().messages
                    val ob = runtimeB.outbox.snapshot().messages
                    if (oa.isNotEmpty() || ob.isNotEmpty()) {
                        throw AssertionError("liveness_outbox_not_empty_after_fairness(A=${oa.size} B=${ob.size})")
                    }
                }
            }

            val prevA = lastViews.getValue(ConversationScenario.Actor.A)
            val prevB = lastViews.getValue(ConversationScenario.Actor.B)

            fun restoreEquivalent(expected: ConversationView, actual: ConversationView): Boolean {
                return expected.conversationId == actual.conversationId &&
                    expected.snapshot == actual.snapshot &&
                    expected.attention == actual.attention &&
                    expected.unreadCount == actual.unreadCount &&
                    expected.lastActivityTimestamp == actual.lastActivityTimestamp &&
                    expected.trust.explicitlyVerified == actual.trust.explicitlyVerified
            }

            // Collect until quiescent. Important nuance: combined flows can emit a stale recombination
            // after emitting a newer one. We want to validate a stable, invariant-safe post-step view,
            // not the last transient emission.
            val updatesA = mutableListOf<ConversationView>()
            val updatesB = mutableListOf<ConversationView>()
            for (i in 0 until 40) {
                yield()
                val drainedA = drainAll(runtimeA.viewChannel)
                val drainedB = drainAll(runtimeB.viewChannel)
                if (drainedA.isEmpty() && drainedB.isEmpty()) break
                updatesA += drainedA
                updatesB += drainedB
            }

            fun pickStable(prev: ConversationView, updates: List<ConversationView>): ConversationView {
                if (updates.isEmpty()) return prev
                var best: ConversationView? = null
                for (u in updates) {
                    if (validator.validate(u).isNotEmpty()) continue
                    if (validator.validateTransition(prev, u).isNotEmpty()) continue
                    best = u
                }
                // If nothing was transition-safe, fall back to last emission to surface the failure.
                return best ?: updates.last()
            }

            val expectedA = expectedEquivalenceAfterStep[stepLabel + ":A"]
            val expectedB = expectedEquivalenceAfterStep[stepLabel + ":B"]

            val latestA = when {
                expectedA == null -> pickStable(prevA, updatesA)
                updatesA.isEmpty() -> prevA
                updatesA.any { it == expectedA } -> expectedA
                else -> pickStable(prevA, updatesA)
            }

            val latestB = when {
                expectedB == null -> pickStable(prevB, updatesB)
                updatesB.isEmpty() -> prevB
                updatesB.any { it == expectedB } -> expectedB
                else -> pickStable(prevB, updatesB)
            }

            diagnostics?.let { sb ->
                if (diagnosticLines < maxDiagnosticLines) {
                    sb.appendLine("STEP $stepLabel")
                    sb.appendLine("  inFlight=${network.snapshotPacketIds()}")
                    sb.appendLine("  A: ${summarizeView(latestA)}")
                    sb.appendLine("  B: ${summarizeView(latestB)}")
                    diagnosticLines += 4
                }
            }

            try {
                assertNoViolations(validator.validate(latestA))
                assertNoViolations(validator.validateTransition(prevA, latestA))
                assertNoViolations(validator.validate(latestB))
                assertNoViolations(validator.validateTransition(prevB, latestB))

                // Cut-aware invariants over persisted state.
                val nextStoreA = runtimeA.store.snapshot()
                val nextStoreB = runtimeB.store.snapshot()
                val nextLedgerA = runtimeA.ledger.snapshot()
                val nextLedgerB = runtimeB.ledger.snapshot()

                // Global Safety Contract (test-only): cross-layer assertions.
                // IMPORTANT: do not fail-fast; record violations and continue to surface secondary issues.
                fun runSafetyContract(actor: ConversationScenario.Actor) {
                    val (prevView, nextView, nextStore, nextLedger, rt) = when (actor) {
                        ConversationScenario.Actor.A -> listOf(prevA, latestA, nextStoreA, nextLedgerA, runtimeA)
                        ConversationScenario.Actor.B -> listOf(prevB, latestB, nextStoreB, nextLedgerB, runtimeB)
                    }.let {
                        @Suppress("UNCHECKED_CAST")
                        Quintuple(
                            it[0] as ConversationView,
                            it[1] as ConversationView,
                            it[2] as PersistedConversationMessageStoreSnapshot,
                            it[3] as PersistedConversationDeliveryLedgerSnapshot,
                            it[4] as NodeRuntime,
                        )
                    }

                    val restoreCtx = ConversationSafetyContract.RestoreContext(
                        isRestoreStep = lastStepEffects.isRestore,
                        restoredTargets = if (lastStepEffects.isRestore) lastRestoreTargets else emptySet(),
                    )

                    val events = safetyContract(
                        stepIndex,
                        stepLabel,
                        actor,
                        prevView,
                        nextView,
                        nextStore,
                        nextLedger,
                        rt.sessionCryptoDebugTrace.decryptedMessageIdsSnapshot(),
                        restoreCtx,
                    )

                    if (events.isEmpty()) return

                    safetyTracker.record(actor, events)

                    if (earliestSafetyEvidence == null) {
                        val related = events.flatMap { it.relatedMessageIds }.toSet()
                        val last5Before = completedStepLabels.takeLast(5)
                        val lastStrat = lastStrategyTransitions.getValue(actor).toList().takeLast(5)
                        val lastConn = lastConnectionTransitions.getValue(actor).toList().takeLast(5)

                        earliestSafetyEvidence = buildString {
                            appendLine("EARLIEST SAFETY VIOLATION")
                            appendLine("actor=$actor stepIndex=$stepIndex stepLabel=$stepLabel")
                            last5Before.forEach { appendLine("priorStep=$it") }
                            events.forEach { e ->
                                append("- contract=${e.contractName} severity=${e.severity} details=${e.details}")
                                if (e.relatedMessageIds.isNotEmpty()) {
                                    append(" messageIds=")
                                    append(e.relatedMessageIds.joinToString(prefix = "[", postfix = "]") { hashMessageId(it) })
                                }
                                append("\n")
                            }
                            if (lastStrat.isNotEmpty()) appendLine("lastStrategyTransitions=${lastStrat.joinToString(separator = " | ")}")
                            if (lastConn.isNotEmpty()) appendLine("lastConnectionTransitions=${lastConn.joinToString(separator = " | ")}")
                            appendLine(summarizeStore(nextStore, related))
                            appendLine(summarizeLedger(nextLedger, related))
                        }
                    }
                }

                runSafetyContract(ConversationScenario.Actor.A)
                runSafetyContract(ConversationScenario.Actor.B)

                // Record lifecycle transitions (for failure diagnostics) before any mutation of baselines.
                fun recordLifecycle(actor: ConversationScenario.Actor, snap: PersistedConversationDeliveryLedgerSnapshot) {
                    val entries = lifecycleByActor.getValue(actor)
                    val lastMap = lastStateByActor.getValue(actor)
                    for (e in snap.entries) {
                        val prev = lastMap[e.messageId]
                        if (prev == null || prev != e.state) {
                            entries.getOrPut(e.messageId) { ArrayList() }.add("step=$stepLabel state=${e.state}")
                        }
                    }
                }
                recordLifecycle(ConversationScenario.Actor.A, nextLedgerA)
                recordLifecycle(ConversationScenario.Actor.B, nextLedgerB)

                // Resource bounds (hard invariants): O(n) in number of unique message ids.
                if (enableResourceAndLivenessValidation) {
                    val ra = captureResources(runtimeA)
                    val rb = captureResources(runtimeB)

                    fun assertLinearBound(who: String, name: String, value: Int, n: Int, k: Int = 40, c: Int = 500) {
                        if (value <= k * n + c) return
                        throw AssertionError("resource_bound_exceeded($who $name=$value n=$n k=$k c=$c)")
                    }

                    fun assertRatchetBound(who: String, skipped: Int, sessions: Int) {
                        val cap = RatchetState.MAX_SKIPPED_KEYS * maxOf(1, sessions)
                        if (skipped <= cap) return
                        throw AssertionError("resource_skipped_ratchet_keys_exceeded($who skipped=$skipped cap=$cap sessions=$sessions)")
                    }

                    val na = maxOf(1, ra.uniqueMessageIds)
                    val nb = maxOf(1, rb.uniqueMessageIds)

                    assertLinearBound("A", "outboxPending", ra.outboxPending, na)
                    assertLinearBound("A", "storeTimeline", ra.storeTimelineCount, na)
                    assertLinearBound("A", "ledgerEntries", ra.ledgerEntries, na)
                    assertLinearBound("A", "inboundProcessed", ra.inboundProcessedIds, na)
                    assertLinearBound("A", "inboundBuffered", ra.inboundBufferedIds, na)
                    assertLinearBound("A", "inboundIndexMaps", ra.inboundIndexMapsTotal, na)
                    assertLinearBound("A", "pendingOutboundControl", ra.pendingOutboundControlMessages, na)
                    assertLinearBound("A", "sessionInboundIdsSeen", ra.sessionInboundMessageIdsSeen, na)
                    assertRatchetBound("A", ra.sessionSkippedKeys, ra.sessions)

                    assertLinearBound("B", "outboxPending", rb.outboxPending, nb)
                    assertLinearBound("B", "storeTimeline", rb.storeTimelineCount, nb)
                    assertLinearBound("B", "ledgerEntries", rb.ledgerEntries, nb)
                    assertLinearBound("B", "inboundProcessed", rb.inboundProcessedIds, nb)
                    assertLinearBound("B", "inboundBuffered", rb.inboundBufferedIds, nb)
                    assertLinearBound("B", "inboundIndexMaps", rb.inboundIndexMapsTotal, nb)
                    assertLinearBound("B", "pendingOutboundControl", rb.pendingOutboundControlMessages, nb)
                    assertLinearBound("B", "sessionInboundIdsSeen", rb.sessionInboundMessageIdsSeen, nb)
                    assertRatchetBound("B", rb.sessionSkippedKeys, rb.sessions)

                    resourceTrace?.let { sb ->
                        sb.appendLine(
                            "STEP $stepLabel RES A(n=${ra.uniqueMessageIds} outbox=${ra.outboxPending} store=${ra.storeTimelineCount} ledger=${ra.ledgerEntries} skipped=${ra.sessionSkippedKeys} inboundBuf=${ra.inboundBufferedIds}) " +
                                "B(n=${rb.uniqueMessageIds} outbox=${rb.outboxPending} store=${rb.storeTimelineCount} ledger=${rb.ledgerEntries} skipped=${rb.sessionSkippedKeys} inboundBuf=${rb.inboundBufferedIds})",
                        )
                    }

                    // No-growth-on-replay / no-growth-on-non-introducing steps.
                    // Intuition: duplicates/replays/reorders must not create new persisted state.
                    val isLivenessStep = livenessConfigByStepLabel.containsKey(stepLabel)
                    if (!lastStepEffects.isRestore && !isLivenessStep) {
                        fun assertNoIncrease(who: String, name: String, prev: Int, next: Int) {
                            if (next <= prev) return
                            throw AssertionError("resource_grew_on_nonintroducing_step($who $name prev=$prev next=$next)")
                        }

                        // If we did not introduce any new message for an actor, its store timeline must not grow.
                        // (Store can grow on outbound send as well as inbound delivery.)
                        if (lastStepEffects.newDeliveredToA == 0 && lastStepEffects.sentOutboundFromA == 0) {
                            assertNoIncrease("A", "storeTimeline", prevResA.storeTimelineCount, ra.storeTimelineCount)
                        }
                        if (lastStepEffects.newDeliveredToB == 0 && lastStepEffects.sentOutboundFromB == 0) {
                            assertNoIncrease("B", "storeTimeline", prevResB.storeTimelineCount, rb.storeTimelineCount)
                        }

                        // On replay deliveries, buffered/protocol/session state must not grow.
                        if (lastStepEffects.replayDeliveredToA) {
                            assertNoIncrease("A", "ledgerEntries", prevResA.ledgerEntries, ra.ledgerEntries)
                            assertNoIncrease("A", "inboundProcessedIds", prevResA.inboundProcessedIds, ra.inboundProcessedIds)
                            assertNoIncrease("A", "inboundBufferedIds", prevResA.inboundBufferedIds, ra.inboundBufferedIds)
                            assertNoIncrease("A", "sessionSkippedKeys", prevResA.sessionSkippedKeys, ra.sessionSkippedKeys)
                            assertNoIncrease("A", "sessionInboundIdsSeen", prevResA.sessionInboundMessageIdsSeen, ra.sessionInboundMessageIdsSeen)
                        }
                        if (lastStepEffects.replayDeliveredToB) {
                            assertNoIncrease("B", "ledgerEntries", prevResB.ledgerEntries, rb.ledgerEntries)
                            assertNoIncrease("B", "inboundProcessedIds", prevResB.inboundProcessedIds, rb.inboundProcessedIds)
                            assertNoIncrease("B", "inboundBufferedIds", prevResB.inboundBufferedIds, rb.inboundBufferedIds)
                            assertNoIncrease("B", "sessionSkippedKeys", prevResB.sessionSkippedKeys, rb.sessionSkippedKeys)
                            assertNoIncrease("B", "sessionInboundIdsSeen", prevResB.sessionInboundMessageIdsSeen, rb.sessionInboundMessageIdsSeen)
                        }
                    }
                }

                if (!skipStoreInvariantA) assertStorePrefixConsistent(prevStoreA, nextStoreA)
                if (!skipStoreInvariantB) assertStorePrefixConsistent(prevStoreB, nextStoreB)

                if (!skipLedgerInvariantA) assertLedgerMonotonic(ConversationScenario.Actor.A, nextLedgerA)
                if (!skipLedgerInvariantB) assertLedgerMonotonic(ConversationScenario.Actor.B, nextLedgerB)

                // After a restore of these subsystems, reset baselines so subsequent checks compare against
                // the restored snapshot instead of flagging legitimate rollback.
                prevStoreA = nextStoreA
                prevStoreB = nextStoreB
                prevLedgerA = nextLedgerA
                prevLedgerB = nextLedgerB

                skipStoreInvariantA = false
                skipStoreInvariantB = false
                skipLedgerInvariantA = false
                skipLedgerInvariantB = false

                // Optional restore equivalence oracle.
                expectedEquivalenceAfterStep.remove(stepLabel + ":A")?.let { expected ->
                    if (updatesA.isNotEmpty() && !restoreEquivalent(expected, latestA)) {
                        throw AssertionError(
                            "restore_equivalence_failed(A): expectedCore=${summarizeView(expected)} actualCore=${summarizeView(latestA)} " +
                                "trustExpected=${expected.trust} trustActual=${latestA.trust}",
                        )
                    }
                }
                expectedEquivalenceAfterStep.remove(stepLabel + ":B")?.let { expected ->
                    if (updatesB.isNotEmpty() && !restoreEquivalent(expected, latestB)) {
                        throw AssertionError(
                            "restore_equivalence_failed(B): expectedCore=${summarizeView(expected)} actualCore=${summarizeView(latestB)} " +
                                "trustExpected=${expected.trust} trustActual=${latestB.trust}",
                        )
                    }
                }
            } catch (e: AssertionError) {
                val trace = diagnostics?.toString()
                val cuts = network.dumpCutsAndQueues()
                val facade = facadeTraces?.toString()
                val resources = resourceTrace?.toString()
                val strategies = strategyTrace?.toString()
                val outboxA = runtimeA.outboxDebugTrace.dumpDebugReport()
                val outboxB = runtimeB.outboxDebugTrace.dumpDebugReport()
                val cryptoA = runtimeA.sessionCryptoDebugTrace.dumpDebugReport()
                val cryptoB = runtimeB.sessionCryptoDebugTrace.dumpDebugReport()
                val lifecycle = buildString {
                    fun dump(actor: ConversationScenario.Actor) {
                        appendLine("ACTOR $actor")
                        val m = lifecycleByActor.getValue(actor)
                        if (m.isEmpty()) {
                            appendLine("  (no lifecycle entries)")
                            return
                        }
                        m.forEach { (messageId, events) ->
                            appendLine("  $messageId")
                            events.forEach { ev -> appendLine("    $ev") }
                        }
                    }
                    dump(ConversationScenario.Actor.A)
                    dump(ConversationScenario.Actor.B)
                }
                val msg = buildString {
                    append("Step '")
                    append(stepLabel)
                    append("' violated invariants: ")
                    append(e.message)
                    if (!trace.isNullOrBlank()) {
                        append("\n\nDIAGNOSTICS TRACE\n")
                        append(trace)
                    }
                    if (cuts.isNotBlank()) {
                        append("\n\nCUT DIAGNOSTICS\n")
                        append(cuts)
                    }
                    if (!facade.isNullOrBlank()) {
                        append("\n\nFACADE DEBUG TRACE\n")
                        append(facade)
                    }
                    if (!strategies.isNullOrBlank()) {
                        append("\n\nSTRATEGY TRACE\n")
                        append(strategies)
                    }
                    if (!resources.isNullOrBlank()) {
                        append("\n\nRESOURCE TRACE\n")
                        append(resources)
                    }
                    if (lifecycle.isNotBlank()) {
                        append("\n\nMESSAGE LIFECYCLE\n")
                        append(lifecycle)
                    }
                    append("\n\nOUTBOX DEBUG TRACE (A)\n")
                    append(outboxA)
                    append("\n\nOUTBOX DEBUG TRACE (B)\n")
                    append(outboxB)
                    append("\n\nSESSION CRYPTO TRACE (A)\n")
                    append(cryptoA)
                    append("\n\nSESSION CRYPTO TRACE (B)\n")
                    append(cryptoB)
                }
                throw AssertionError(msg)
            }

            lastViews[ConversationScenario.Actor.A] = latestA
            lastViews[ConversationScenario.Actor.B] = latestB
        }

        scenario.steps.forEachIndexed { idx, step ->
            val stepLabel = "${scenario.name}#${idx + 1}:${step.label}"
            currentStepForTraces = stepLabel
            var stepEffects = StepEffects()
            lastRestoreTargets = emptySet()
            try {
                when (step) {
                    is ConversationScenario.SendOutbound -> {
                        val rt = if (step.from == ConversationScenario.Actor.A) runtimeA else runtimeB
                        rt.clock.tick(1)
                        sendOutbound(rt, step)
                        stepEffects = when (step.from) {
                            ConversationScenario.Actor.A -> stepEffects.copy(sentOutboundFromA = stepEffects.sentOutboundFromA + 1)
                            ConversationScenario.Actor.B -> stepEffects.copy(sentOutboundFromB = stepEffects.sentOutboundFromB + 1)
                        }
                    }

                    is ConversationScenario.ReceiveInbound -> {
                        val pkt = network.remove(step.packet) ?: return@forEachIndexed

                        // Track replay vs new delivery (for resource invariants).
                        pkt.messageId?.let { mid ->
                            val deliveredTo = pkt.to
                            val seen = deliveredMessageIds.getValue(deliveredTo)
                            val isReplay = seen.contains(mid)
                            stepEffects = when {
                                isReplay && deliveredTo == ConversationScenario.Actor.A -> stepEffects.copy(replayDeliveredToA = true)
                                isReplay && deliveredTo == ConversationScenario.Actor.B -> stepEffects.copy(replayDeliveredToB = true)
                                (!isReplay) && deliveredTo == ConversationScenario.Actor.A -> stepEffects.copy(newDeliveredToA = stepEffects.newDeliveredToA + 1)
                                (!isReplay) && deliveredTo == ConversationScenario.Actor.B -> stepEffects.copy(newDeliveredToB = stepEffects.newDeliveredToB + 1)
                                else -> stepEffects
                            }
                        }

                        // New invariant: no message is ACKed before it is RECEIVED.
                        // When delivering an ACK from X, ensure X has already received the acked USER message.
                        if (pkt.kind == PacketKind.ACK && pkt.source == PacketSource.SYSTEM) {
                            val acked = pkt.ackedMessageId
                            if (acked != null && !receivedUserMessageIds.getValue(pkt.from).contains(acked)) {
                                throw AssertionError("ack_delivered_before_user_received(ackFrom=${pkt.from} ackedMessageId=$acked)")
                            }
                        }

                        val receiver = if (pkt.to == ConversationScenario.Actor.A) runtimeA else runtimeB
                        val sender = if (pkt.from == ConversationScenario.Actor.A) runtimeA else runtimeB

                        receiver.clock.tick(1)
                        receiver.engine.onInboundBytes(pkt.bytes, receivedAtElapsedMs = receiver.clock.nowMs(), peerId = receiver.peerId)

                        if (pkt.kind == PacketKind.USER && pkt.messageId != null) {
                            receivedUserMessageIds.getValue(pkt.to).add(pkt.messageId)
                        }

                        pkt.messageId?.let { deliveredMessageIds.getValue(pkt.to).add(it) }

                        // Transport delivered => clear sender in-flight item.
                        pkt.messageId?.let { sender.outbox.notifyDelivered(it) }
                    }

                    is ConversationScenario.InjectAck -> {
                        val to = if (step.to == ConversationScenario.Actor.A) runtimeA else runtimeB
                        val bytes = to.engine.wrapForSession(
                            peerId = to.peerId,
                            message = AckMessage(
                                messageId = step.ackMessageId,
                                conversationId = step.conversationId,
                                createdAtElapsedMs = to.clock.nowMs(),
                                ackedMessageId = step.ackedMessageId,
                            ),
                        )
                        network.enqueue(
                            from = step.from,
                            to = step.to,
                            bytes = bytes,
                            messageId = step.ackMessageId,
                            conversationId = step.conversationId,
                            kind = PacketKind.ACK,
                            ackedMessageId = step.ackedMessageId,
                            source = PacketSource.INJECTED,
                        )
                    }

                    is ConversationScenario.Drop -> {
                        network.remove(step.packet)
                    }

                    is ConversationScenario.Duplicate -> {
                        network.duplicate(step.packet, step.times)
                    }

                    is ConversationScenario.Reorder -> {
                        // Deterministic reorder: execute nested steps in reverse.
                        step.steps.asReversed().forEach { nested ->
                            // Run nested steps inline (no additional validation between nested steps; outer step is one unit).
                            when (nested) {
                                is ConversationScenario.ReceiveInbound -> {
                                    val pkt = network.remove(nested.packet) ?: return@forEach

                                    pkt.messageId?.let { mid ->
                                        val deliveredTo = pkt.to
                                        val seen = deliveredMessageIds.getValue(deliveredTo)
                                        val isReplay = seen.contains(mid)
                                        stepEffects = when {
                                            isReplay && deliveredTo == ConversationScenario.Actor.A -> stepEffects.copy(replayDeliveredToA = true)
                                            isReplay && deliveredTo == ConversationScenario.Actor.B -> stepEffects.copy(replayDeliveredToB = true)
                                            (!isReplay) && deliveredTo == ConversationScenario.Actor.A -> stepEffects.copy(newDeliveredToA = stepEffects.newDeliveredToA + 1)
                                            (!isReplay) && deliveredTo == ConversationScenario.Actor.B -> stepEffects.copy(newDeliveredToB = stepEffects.newDeliveredToB + 1)
                                            else -> stepEffects
                                        }
                                    }

                                    val receiver = if (pkt.to == ConversationScenario.Actor.A) runtimeA else runtimeB
                                    val sender = if (pkt.from == ConversationScenario.Actor.A) runtimeA else runtimeB
                                    receiver.clock.tick(1)
                                    receiver.engine.onInboundBytes(pkt.bytes, receivedAtElapsedMs = receiver.clock.nowMs(), peerId = receiver.peerId)
                                    pkt.messageId?.let { deliveredMessageIds.getValue(pkt.to).add(it) }
                                    pkt.messageId?.let { sender.outbox.notifyDelivered(it) }
                                }

                                is ConversationScenario.Drop -> network.remove(nested.packet)
                                is ConversationScenario.Duplicate -> network.duplicate(nested.packet, nested.times)
                                else -> error("reorder_block_step_not_supported: ${nested::class.simpleName}")
                            }
                        }
                    }

                    is ConversationScenario.ReorderWindow -> {
                        network.reorderFirstWindow(step.size)
                    }

                    is ConversationScenario.CutStart -> {
                        network.startCut(step.cutId)
                    }

                    is ConversationScenario.CutEnd -> {
                        network.endCut(step.cutId)
                    }

                    is ConversationScenario.ReorderAcrossCuts -> {
                        network.reorderAcrossCuts(step.windowSize)
                    }

                    is ConversationScenario.SnapshotAndRestore -> {
                        stepEffects = stepEffects.copy(isRestore = true)
                        val targets = step.targets
                        lastRestoreTargets = targets
                        val beforeA = if (step.actors.contains(ConversationScenario.Actor.A)) lastViews[ConversationScenario.Actor.A] else null
                        val beforeB = if (step.actors.contains(ConversationScenario.Actor.B)) lastViews[ConversationScenario.Actor.B] else null
                        if (step.actors.contains(ConversationScenario.Actor.A)) {
                            val snaps = runtimeA.snapshotTargets(targets)
                            runtimeA = restoreNode(runtimeA, snaps, targets)
                        }
                        if (step.actors.contains(ConversationScenario.Actor.B)) {
                            val snaps = runtimeB.snapshotTargets(targets)
                            runtimeB = restoreNode(runtimeB, snaps, targets)
                        }

                        if (step.assertEquivalent) {
                            // Validate equivalence after the subsequent quiescence drain in validateAfterStep.
                            // We record the pre-restore views and compare after validation runs.
                            // (Comparison happens in validateAfterStep by looking at the most recent drained views.)
                            // Stash into step label map via closure vars.
                            //
                            // NOTE: We intentionally only compare the actors included in this restore.
                            expectedEquivalenceAfterStep[stepLabelKey(scenario.name, idx + 1, step.label, "A")] = beforeA
                            expectedEquivalenceAfterStep[stepLabelKey(scenario.name, idx + 1, step.label, "B")] = beforeB
                        }

                        // Snapshot/restore intentionally rolls back persisted subsystems; skip monotonic/prefix
                        // checks for those restored targets in the next validateAfterStep.
                        if (step.actors.contains(ConversationScenario.Actor.A)) {
                            if (targets.contains(ConversationScenario.RestoreTarget.MESSAGE_STORE)) skipStoreInvariantA = true
                            if (targets.contains(ConversationScenario.RestoreTarget.LEDGER)) skipLedgerInvariantA = true
                        }
                        if (step.actors.contains(ConversationScenario.Actor.B)) {
                            if (targets.contains(ConversationScenario.RestoreTarget.MESSAGE_STORE)) skipStoreInvariantB = true
                            if (targets.contains(ConversationScenario.RestoreTarget.LEDGER)) skipLedgerInvariantB = true
                        }
                    }

                    is ConversationScenario.SetVisibility -> {
                        val rt = if (step.actor == ConversationScenario.Actor.A) runtimeA else runtimeB
                        rt.attention.onVisibleConversationChanged(
                            when (step.visibility) {
                                ConversationScenario.Visibility.VISIBLE -> rt.conversationId
                                ConversationScenario.Visibility.HIDDEN -> null
                            },
                        )
                    }

                    is ConversationScenario.SetDeliveryMode -> {
                        val rt = if (step.actor == ConversationScenario.Actor.A) runtimeA else runtimeB
                        rt.decision.emit(
                            when (step.mode) {
                                DeliveryMode.ACTIVE -> ActiveDelivery
                                DeliveryMode.PASSIVE -> PassiveDelivery(PassiveDelivery.QueueReason.USER_PREFERENCE)
                            },
                        )
                    }

                    is ConversationScenario.AssertLivenessUnderFairness -> {
                        // Marker step; validateAfterStep performs the drain + liveness assertions.
                        livenessConfigByStepLabel[stepLabel] = step.maxDrainMultiplier
                    }
                }
            } catch (t: Throwable) {
                throw RuntimeException("Scenario '${scenario.name}' failed at step ${idx + 1}/${scenario.steps.size} (${step.label}): ${t.message}", t)
            }

            lastStepEffects = stepEffects
            validateAfterStep(stepLabel = stepLabel, stepIndex = idx + 1)

            completedStepLabels += stepLabel

            if (enableResourceAndLivenessValidation) {
                prevResA = captureResources(runtimeA)
                prevResB = captureResources(runtimeB)
            }
        }

        val safetySummary = safetyTracker.buildSummary(finalStepIndex = scenario.steps.size)
        val pendingSafetyFailure: AssertionError? = if (safetySummary.totalViolations == 0) {
            null
        } else {
            val cuts = network.dumpCutsAndQueues()
            val trace = diagnostics?.toString()
            val facade = facadeTraces?.toString()
            val resources = resourceTrace?.toString()
            val strategies = strategyTrace?.toString()
            val outboxA = runtimeA.outboxDebugTrace.dumpDebugReport()
            val outboxB = runtimeB.outboxDebugTrace.dumpDebugReport()
            val cryptoA = runtimeA.sessionCryptoDebugTrace.dumpDebugReport()
            val cryptoB = runtimeB.sessionCryptoDebugTrace.dumpDebugReport()

            AssertionError(
                buildString {
                    appendLine("safety_contract_run_failed(scenario=${scenario.name} totalViolations=${safetySummary.totalViolations})")
                    safetySummary.earliest?.let { e ->
                        appendLine("earliestViolation(actor=${e.actor} contract=${e.contractName} stepIndex=${e.stepIndex})")
                    }
                    appendLine("healed_vs_persistent:")
                    safetySummary.byActor.forEach { a ->
                        if (a.totalViolations == 0) return@forEach
                        appendLine("  actor=${a.actor} total=${a.totalViolations}")
                        a.contracts.forEach { c ->
                            appendLine("    contract=${c.contractName} firstStep=${c.firstViolationStep} count=${c.violationCount} healed=${c.healed}")
                        }
                    }

                    if (!earliestSafetyEvidence.isNullOrBlank()) {
                        appendLine()
                        append(earliestSafetyEvidence)
                    }

                    if (!trace.isNullOrBlank()) {
                        append("\n\nDIAGNOSTICS TRACE\n")
                        append(trace)
                    }
                    if (cuts.isNotBlank()) {
                        append("\n\nCUT DIAGNOSTICS\n")
                        append(cuts)
                    }
                    if (!facade.isNullOrBlank()) {
                        append("\n\nFACADE DEBUG TRACE\n")
                        append(facade)
                    }
                    if (!strategies.isNullOrBlank()) {
                        append("\n\nSTRATEGY TRACE\n")
                        append(strategies)
                    }
                    if (!resources.isNullOrBlank()) {
                        append("\n\nRESOURCE TRACE\n")
                        append(resources)
                    }
                    append("\n\nOUTBOX DEBUG TRACE (A)\n")
                    append(outboxA)
                    append("\n\nOUTBOX DEBUG TRACE (B)\n")
                    append(outboxB)
                    append("\n\nSESSION CRYPTO TRACE (A)\n")
                    append(cryptoA)
                    append("\n\nSESSION CRYPTO TRACE (B)\n")
                    append(cryptoB)
                },
            )
        }

        runtimeA.wireJob.cancel(); runtimeA.viewJob.cancel(); runtimeA.viewChannel.close()
        runtimeB.wireJob.cancel(); runtimeB.viewJob.cancel(); runtimeB.viewChannel.close()
        runtimeA.outbox.close(); runtimeB.outbox.close()
        runtimeA.outboxDebugTrace.close(); runtimeB.outboxDebugTrace.close()
        runtimeA.sessionCryptoDebugTrace.close(); runtimeB.sessionCryptoDebugTrace.close()
        runtimeA.decision.close(); runtimeB.decision.close()

        if (pendingSafetyFailure != null) throw pendingSafetyFailure
    }

    private data class Quintuple<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private suspend fun awaitBaseline(rt: NodeRuntime): ConversationView {
        return withTimeout(2_000) { rt.viewChannel.receive() }
    }

    private fun drainAll(ch: Channel<ConversationView>): List<ConversationView> {
        val out = ArrayList<ConversationView>(8)
        while (true) {
            val r = ch.tryReceive().getOrNull() ?: break
            out += r
        }
        return out
    }

    private fun stepLabelKey(scenarioName: String, idx1: Int, label: String, actor: String): String = "$scenarioName#$idx1:$label:$actor"

    // Stores expected pre-restore views for opt-in restore-equivalence assertions.
    private val expectedEquivalenceAfterStep = HashMap<String, ConversationView?>()

    private fun assertNoViolations(violations: List<ConversationInvariantViolation>) {
        if (violations.isEmpty()) return
        fail(
            buildString {
                appendLine("Expected zero invariant violations, but got ${violations.size}:")
                violations.forEach { v ->
                    appendLine("- ${v.severity} ${v.id}: ${v.message} details=${v.details}")
                }
            },
        )
    }

    private fun sendOutbound(rt: NodeRuntime, step: ConversationScenario.SendOutbound) {
        val msg: ProtocolMessage = when (val p = step.protocol) {
            is ConversationScenario.OutboundProtocol.User -> UserMessage(
                messageId = p.messageId,
                conversationId = step.conversationId,
                createdAtElapsedMs = p.createdAtElapsedMs,
                payload = p.payload,
            )

            is ConversationScenario.OutboundProtocol.SenderKeyDistributionPlanned -> {
                val groupId = GroupId.fromConversationId(step.conversationId)
                val planned = rt.senderKeyDistributionEngine.planDistributions(
                    groupId = groupId,
                    conversationIdForRecipient = { step.conversationId },
                    messageIdGenerator = { p.messageId },
                )

                val msg = planned.firstOrNull()?.message
                    ?: throw IllegalStateException("no_sender_key_distribution_planned")

                // Ensure our createdAt matches the scenario step (planDistributions uses current clock).
                msg.copy(createdAtElapsedMs = p.createdAtElapsedMs)
            }

            is ConversationScenario.OutboundProtocol.SenderKeyDistribution -> SenderKeyDistributionMessage(
                messageId = p.messageId,
                conversationId = step.conversationId,
                createdAtElapsedMs = p.createdAtElapsedMs,
                groupId = p.groupIdBytes,
                senderIdentityPublicKey = rt.identityPublicKey.copyOf(),
                senderKeyId = p.senderKeyId,
                senderChainKey = p.senderChainKey,
            )

            is ConversationScenario.OutboundProtocol.SenderKeyGroupMessage -> {
                // Ensure sender has a local sender key for this group.
                rt.senderKeyDistributionEngine.getOrCreateLocalSenderKey(GroupId.fromConversationId(step.conversationId))
                rt.senderKeyGroupEngine.encryptOutbound(
                    conversationId = step.conversationId,
                    messageId = p.messageId,
                    createdAtElapsedMs = p.createdAtElapsedMs,
                    plaintextPayload = p.ciphertextPayload,
                )
            }

            is ConversationScenario.OutboundProtocol.SessionInit -> {
                throw IllegalStateException("session_init_not_supported_in_scenarios")
            }
        }

        rt.engine.send(peerId = step.peerId, message = msg)
    }

    private fun establishSession(a: NodeRuntime, b: NodeRuntime, network: DeterministicNetwork) {
        val init = a.engine.startSession(
            peerId = a.peerId,
            conversationId = a.conversationId,
            initiatorNonce = byteArrayOf(7, 7),
            peerBundle = b.peerBundle,
        )
        a.engine.send(peerId = a.peerId, message = init)

        val initPkt = network.removeByMessageId(init.messageId) ?: throw IllegalStateException("missing_session_init_packet")
        b.engine.onInboundBytes(initPkt.bytes, receivedAtElapsedMs = b.clock.nowMs(), peerId = b.peerId)
        a.outbox.notifyDelivered(init.messageId)

        // The handshake handler enqueued a SessionAccept outbound from B.
        // It uses init.messageId + ":accept".
        val acceptId = init.messageId + ":accept"
        val acceptPkt = network.removeByMessageId(acceptId) ?: throw IllegalStateException("missing_session_accept_packet")

        a.engine.onInboundBytes(acceptPkt.bytes, receivedAtElapsedMs = a.clock.nowMs(), peerId = a.peerId)
        b.outbox.notifyDelivered(acceptId)
    }

    private fun newNode(
        actor: ConversationScenario.Actor,
        peerId: String,
        conversationId: String,
        network: DeterministicNetwork,
        facadeTrace: StringBuilder?,
    ): NodeRuntime {
        val codec = BinaryProtocolCodec()
        val clock = TestClock(now = 0L)

        val id = X3dhCrypto.generateIdentityKeyPair()
        val spk = X3dhCrypto.generateX25519KeyPair()
        val spkId = 1
        val spkSig = X3dhCrypto.signSignedPreKey(identitySeed = id.seed, signedPreKeyId = spkId, signedPreKeyPublic = spk.publicKey)
        val signed = X3dhSignedPreKey(preKeyId = spkId, privateKey = spk.privateKey, publicKey = spk.publicKey, signature = spkSig, createdAtElapsedMs = 0L)
        val opk = X3dhCrypto.generateX25519KeyPair()
        val oneTime = X3dhOneTimePreKey(preKeyId = 1, privateKey = opk.privateKey, publicKey = opk.publicKey, createdAtElapsedMs = 0L)
        val preKeyStore = InMemoryX3dhPreKeyStore(signedPreKey = signed, oneTimePreKeys = listOf(oneTime))
        val bundle = preKeyStore.buildBundle(identityPublicKey = id.publicKey)

        val sessions = InMemorySessionStore(
            x3dhIdentitySeedEd = id.seed,
            x3dhIdentityPublicKeyEd = id.publicKey,
            x3dhPreKeyStore = preKeyStore,
        )

        val store = ObservableConversationMessageStore(InMemoryConversationMessageStore(clock = clock))
        val ledger = ObservableConversationDeliveryLedger(InMemoryConversationDeliveryLedger(clock = clock))

        val groupStore = ObservableGroupStore(InMemoryGroupStore())
        val senderKeyStore = ObservableSenderKeyStore(InMemorySenderKeyStore())
        val senderKeyDistStore = InMemorySenderKeyDistributionStore()

        val distEngine = SenderKeyDistributionEngine(
            localIdentityPublicKey = id.publicKey,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            distributionStore = senderKeyDistStore,
            clock = clock,
        )

        val groupEngine = com.ivor.kriptex.deliverypolicy.group.senderkey.dataplane.SenderKeyGroupMessageEngine(
            localIdentityPublicKey = id.publicKey,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
        )

        val inbound = InMemoryProtocolInboundPipeline(
            decoder = codec,
            encoder = codec,
            messageIdGenerator = IncrementingMessageIdGenerator(prefix = "ack", start = 1),
            ledger = ledger,
            messageStore = store,
            clock = clock,
        )

        val decision = MutableDecisionEngine(ActiveDelivery)

        val sender = RecordingStrategySender(
            who = actor,
            network = network,
            codec = codec,
            clock = clock,
            ledger = ledger,
        )

        val outboxDebug = if (facadeTrace != null) DefaultMessageOutboxDebugTrace(maxEntries = 2_000) else NoOpMessageOutboxDebugTrace

        val outboxCore = DefaultMessageOutbox(
            decisionEngine = decision,
            sender = sender,
            clock = clock,
            debugTrace = outboxDebug,
        )

        val outbox = ConversationMessageStoreOutboxAdapter(outboxCore, store)

        val outbound = SessionBoundProtocolOutbound(
            outbox = outbox,
            encoder = codec,
            sessionStore = sessions,
            clock = clock,
        )

        val sessionCryptoDebug = RecordingSessionCryptoDebugTrace(maxEntries = 500)

        val handshakeHandler = SessionHandshakeHandler(
            sessionStore = sessions,
            encoder = codec,
            localIdentityPublicKey = id.publicKey,
            responderNonceGenerator = { byteArrayOf(8, 8) },
            aeadSupport = AesOnlySupport,
            clock = clock,
        )

        val router = DefaultProtocolMessageRouter(
            encoder = codec,
            inbound = inbound,
            outbound = SessionBoundOutboundEnqueuer(outbound),
            handshakeHandler = handshakeHandler,
            senderKeyDistributionHandler = SenderKeyDistributionEngineAdapter(distEngine),
            senderKeyGroupMessageHandler = SenderKeyGroupMessageEngineAdapter(groupEngine),
        )

        val engine = SessionAwareProtocolEngine(
            inbound = inbound,
            outbound = outbound,
            sessionStore = sessions,
            protocolDecoder = codec,
            protocolEncoder = codec,
            router = router,
            sessionIdGenerator = IncrementingSessionIdGenerator(prefix = actor.name.lowercase(), start = 1),
            localIdentityPublicKey = id.publicKey,
            responderNonceGenerator = { byteArrayOf(8, 8) },
            aeadSupport = AesOnlySupport,
            clock = clock,
            cryptoDebug = sessionCryptoDebug,
        )

        val connection = DefaultConnectionStateProvider(clock = clock, debounceMs = 0L)
        connection.setLocalOnline(true)
        connection.reportDirectContactConfirmed()

        val stateAgg = ConversationStateAggregator(
            conversationId = conversationId,
            messageStore = store,
            ledger = ledger,
            connectionStateProvider = connection,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            localIdentityPublicKey = id.publicKey,
            peerId = peerId,
            sessionStore = sessions,
            invalidations = ConversationStateInvalidationSources(
                messageStore = store.invalidations,
                ledger = ledger.invalidations,
                groupStore = groupStore.invalidations,
                senderKeyStore = senderKeyStore.invalidations,
            ),
        )

        val trustStore = InMemoryConversationTrustStore()
        val identityKeyStore = InMemoryIdentityKeyStore()
        identityKeyStore.putPeerIdentityPublicKey(peerId, ByteArray(32) { 1 })

        val trustEngine = ConversationTrustStateEngine(
            conversationId = conversationId,
            trustStore = trustStore,
            identityKeyStore = identityKeyStore,
            sessionStore = sessions,
            peerId = peerId,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            senderKeyDistributionStore = senderKeyDistStore,
            localIdentityPublicKey = id.publicKey,
            invalidations = ConversationTrustInvalidationSources(
                groupStore = groupStore.invalidations,
                senderKeyStore = senderKeyStore.invalidations,
            ),
        )
        trustEngine.verifyConversation()

        val notificationSink = RecordingNotificationSink(who = actor)
        val attention = ConversationAttentionCoordinator(notificationSink = notificationSink)
        attention.onAppLifecycle(AppLifecycleState.FOREGROUND)
        attention.onVisibleConversationChanged(null)

        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val wireJob = scope.launch {
            stateAgg.observe().collect { attention.onSnapshot(it) }
        }

        val debugTrace = facadeTrace?.let { sb ->
            object : ConversationFacadeDebugTrace {
                override fun onViewUpdated(conversationId: String, reason: String, changedComponents: Set<String>) {
                    sb.appendLine("${actor.name}: viewUpdated(conversationId=$conversationId reason=$reason changed=$changedComponents)")
                }
            }
        }

        val facade = ConversationFacade(
            conversationId = conversationId,
            state = stateAgg,
            trust = trustEngine,
            attention = attention,
            debugTrace = debugTrace ?: com.ivor.kriptex.deliverypolicy.conversationfacade.NoOpConversationFacadeDebugTrace,
        )

        val viewChannel = Channel<ConversationView>(capacity = Channel.UNLIMITED)
        val viewJob = scope.launch {
            facade.observe().collect { viewChannel.trySend(it) }
        }

        return NodeRuntime(
            actor = actor,
            peerId = peerId,
            conversationId = conversationId,
            clock = clock,
            codec = codec,
            identityPublicKey = id.publicKey,
            peerBundle = bundle,
            decision = decision,
            store = store,
            ledger = ledger,
            groupStore = groupStore,
            senderKeyStore = senderKeyStore,
            senderKeyDistributionStore = senderKeyDistStore,
            senderKeyDistributionEngine = distEngine,
            senderKeyGroupEngine = groupEngine,
            trustStore = trustStore,
            identityKeyStore = identityKeyStore,
            sessionStore = sessions,
            engine = engine,
            outbox = outbox,
            outboxDebugTrace = outboxDebug,
            attention = attention,
            notificationSink = notificationSink,
            connection = connection,
            stateAgg = stateAgg,
            trustEngine = trustEngine,
            facade = facade,
            sessionCryptoDebugTrace = sessionCryptoDebug,
            viewChannel = viewChannel,
            wireJob = wireJob,
            viewJob = viewJob,
        )
    }

    private fun restoreNode(
        old: NodeRuntime,
        snaps: NodeSnapshots,
        targets: Set<ConversationScenario.RestoreTarget>,
    ): NodeRuntime {
        // IMPORTANT: Restore is modeled as in-place snapshot/restore of selected subsystems.
        // Recreating the whole node would incorrectly reset runtime-only components (attention/unread)
        // and create transitions production does not expose.
        @Suppress("UNUSED_VARIABLE")
        val _keep = targets

        snaps.messageStore?.let { old.store.restore(it) }
        snaps.ledger?.let { old.ledger.restore(it) }
        snaps.outbox?.let { old.outbox.restore(it) }
        snaps.sessionEngine?.let { old.engine.restore(it) }
        snaps.groupStore?.let { old.groupStore.restore(it) }
        snaps.senderKeyStore?.let { old.senderKeyStore.restore(it) }
        snaps.senderKeyDistributionStore?.let { old.senderKeyDistributionStore.restore(it) }
        snaps.trustStore?.let { old.trustStore.restore(it) }
        snaps.identityKeyStore?.let { old.identityKeyStore.restore(it) }

        return old
    }
}
