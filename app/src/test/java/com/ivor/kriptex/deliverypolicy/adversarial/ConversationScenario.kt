package com.ivor.kriptex.deliverypolicy.adversarial

import com.ivor.kriptex.deliverypolicy.DeliveryMode

/**
 * Deterministic, test-only adversarial scenario DSL.
 */
class ConversationScenario internal constructor(
    val name: String,
    internal val steps: List<Step>,
    /**
     * If true, the runner enforces additional test-only resource/liveness validation.
     * Defaults to false to avoid changing existing scenario behavior.
     */
    val enableResourceAndLivenessValidation: Boolean = false,
) {

    sealed interface Step {
        val label: String
    }

    /** Marks the start of a network cut. Packets sent while any cut is active are tagged with active cut ids. */
    data class CutStart(
        override val label: String,
        val cutId: String,
    ) : Step

    /** Marks the end of a network cut. Ending a cut adversarially reintroduces newly-unblocked packets. */
    data class CutEnd(
        override val label: String,
        val cutId: String,
    ) : Step

    /** Deterministically reorders the first [windowSize] in-flight packets, including those spanning cuts. */
    data class ReorderAcrossCuts(
        override val label: String,
        val windowSize: Int,
    ) : Step

    data class PacketHandle(
        val id: String,
        val from: Actor,
        val to: Actor,
        val messageId: String?,
    )

    enum class Actor { A, B }

    enum class Visibility { VISIBLE, HIDDEN }

    enum class RestoreTarget {
        MESSAGE_STORE,
        LEDGER,
        OUTBOX,
        SESSION_PROTOCOL_ENGINE,
        GROUP_STORE,
        SENDER_KEY_STORE,
        SENDER_KEY_DISTRIBUTION_STORE,
        TRUST_STORE,
        IDENTITY_KEY_STORE,
    }

    data class SendOutbound(
        override val label: String,
        val from: Actor,
        val conversationId: String,
        val peerId: String,
        val protocol: OutboundProtocol,
    ) : Step

    data class ReceiveInbound(
        override val label: String,
        val packet: PacketHandle,
    ) : Step

    data class InjectAck(
        override val label: String,
        val from: Actor,
        val to: Actor,
        val conversationId: String,
        val ackMessageId: String,
        val ackedMessageId: String,
    ) : Step

    data class Drop(
        override val label: String,
        val packet: PacketHandle,
    ) : Step

    data class Duplicate(
        override val label: String,
        val packet: PacketHandle,
        val times: Int,
    ) : Step

    data class Reorder(
        override val label: String,
        val steps: List<Step>,
    ) : Step

    data class SnapshotAndRestore(
        override val label: String,
        val actors: Set<Actor>,
        val targets: Set<RestoreTarget>,
        /** If true, assert restore-equivalence after the runner reaches quiescence (core view fields). */
        val assertEquivalent: Boolean = false,
    ) : Step

    /** Deterministically reorders the next delivery window by reversing the first [size] in-flight packets. */
    data class ReorderWindow(
        override val label: String,
        val size: Int,
    ) : Step

    /**
     * Test-only liveness assertion under fairness.
     *
     * FAIRNESS rule:
     * - every active cut eventually ends
     * - every queued packet becomes eligible for delivery
     *
     * Runner semantics:
     * - forces delivery strategies to ACTIVE
     * - deterministically ends all remaining cuts (LIFO)
     * - drains the network until quiescent (bounded)
     * - then asserts terminal progress for all messages
     */
    data class AssertLivenessUnderFairness(
        override val label: String,
        val maxDrainMultiplier: Int = 8,
    ) : Step

    data class SetVisibility(
        override val label: String,
        val actor: Actor,
        val visibility: Visibility,
    ) : Step

    data class SetDeliveryMode(
        override val label: String,
        val actor: Actor,
        val mode: DeliveryMode,
    ) : Step

    sealed interface OutboundProtocol {
        data class User(
            val messageId: String,
            val createdAtElapsedMs: Long,
            val payload: ByteArray,
        ) : OutboundProtocol

        /**
         * Test-friendly distribution message: runner fills fields from real SenderKeyDistributionEngine state.
         *
         * - groupId is derived from conversationId
         * - recipient identity is the peer actor's identity key
         */
        data class SenderKeyDistributionPlanned(
            val messageId: String,
            val createdAtElapsedMs: Long,
        ) : OutboundProtocol

        data class SenderKeyDistribution(
            val messageId: String,
            val createdAtElapsedMs: Long,
            val groupIdBytes: ByteArray,
            val senderKeyId: Long,
            val senderChainKey: ByteArray,
        ) : OutboundProtocol

        data class SenderKeyGroupMessage(
            val messageId: String,
            val createdAtElapsedMs: Long,
            val ciphertextPayload: ByteArray,
        ) : OutboundProtocol

        data class SessionInit(
            val createdAtElapsedMs: Long,
            val initiatorNonce: ByteArray,
        ) : OutboundProtocol
    }
}

fun conversationScenario(name: String, block: ConversationScenarioBuilder.() -> Unit): ConversationScenario {
    val b = ConversationScenarioBuilder(name)
    b.block()
    return b.build()
}

class ConversationScenarioBuilder internal constructor(
    private val name: String,
) {
    private val steps = ArrayList<ConversationScenario.Step>()

    fun sendOutbound(
        label: String = "sendOutbound",
        from: ConversationScenario.Actor,
        conversationId: String,
        peerId: String,
        protocol: ConversationScenario.OutboundProtocol,
    ) {
        steps.add(
            ConversationScenario.SendOutbound(
                label = label,
                from = from,
                conversationId = conversationId,
                peerId = peerId,
                protocol = protocol,
            ),
        )
    }

    fun receiveInbound(label: String = "receiveInbound", packet: ConversationScenario.PacketHandle) {
        steps.add(ConversationScenario.ReceiveInbound(label = label, packet = packet))
    }

    fun ack(
        label: String = "ack",
        from: ConversationScenario.Actor,
        to: ConversationScenario.Actor,
        conversationId: String,
        ackMessageId: String,
        ackedMessageId: String,
    ) {
        steps.add(
            ConversationScenario.InjectAck(
                label = label,
                from = from,
                to = to,
                conversationId = conversationId,
                ackMessageId = ackMessageId,
                ackedMessageId = ackedMessageId,
            ),
        )
    }

    fun drop(label: String = "drop", packet: ConversationScenario.PacketHandle) {
        steps.add(ConversationScenario.Drop(label = label, packet = packet))
    }

    fun duplicate(label: String = "duplicate", packet: ConversationScenario.PacketHandle, times: Int) {
        require(times >= 2) { "times_must_be_>=2" }
        steps.add(ConversationScenario.Duplicate(label = label, packet = packet, times = times))
    }

    fun reorder(label: String = "reorder", block: ConversationScenarioBuilder.() -> Unit) {
        val nested = ConversationScenarioBuilder(name = name)
        nested.block()
        steps.add(ConversationScenario.Reorder(label = label, steps = nested.steps.toList()))
    }

    fun snapshotAndRestore(
        label: String = "snapshotAndRestore",
        actors: Set<ConversationScenario.Actor> = setOf(ConversationScenario.Actor.A, ConversationScenario.Actor.B),
        targets: Set<ConversationScenario.RestoreTarget>,
        assertEquivalent: Boolean = false,
    ) {
        steps.add(
            ConversationScenario.SnapshotAndRestore(
                label = label,
                actors = actors,
                targets = targets,
                assertEquivalent = assertEquivalent,
            ),
        )
    }

    fun reorderWindow(label: String = "reorderWindow", size: Int) {
        require(size >= 2) { "reorder_window_size_must_be_>=2" }
        steps.add(ConversationScenario.ReorderWindow(label = label, size = size))
    }

    fun cutStart(label: String = "cutStart", cutId: String) {
        steps.add(ConversationScenario.CutStart(label = label, cutId = cutId))
    }

    fun cutEnd(label: String = "cutEnd", cutId: String) {
        steps.add(ConversationScenario.CutEnd(label = label, cutId = cutId))
    }

    fun reorderAcrossCuts(label: String = "reorderAcrossCuts", windowSize: Int) {
        require(windowSize >= 2) { "reorder_across_cuts_window_must_be_>=2" }
        steps.add(ConversationScenario.ReorderAcrossCuts(label = label, windowSize = windowSize))
    }

    fun assertLivenessUnderFairness(label: String = "assertLivenessUnderFairness", maxDrainMultiplier: Int = 8) {
        require(maxDrainMultiplier >= 1) { "maxDrainMultiplier_must_be_>=1" }
        steps.add(ConversationScenario.AssertLivenessUnderFairness(label = label, maxDrainMultiplier = maxDrainMultiplier))
    }

    fun setVisibility(
        label: String = "setVisibility",
        actor: ConversationScenario.Actor,
        visibility: ConversationScenario.Visibility,
    ) {
        steps.add(ConversationScenario.SetVisibility(label = label, actor = actor, visibility = visibility))
    }

    fun setDeliveryMode(label: String = "setDeliveryMode", actor: ConversationScenario.Actor, mode: DeliveryMode) {
        steps.add(ConversationScenario.SetDeliveryMode(label = label, actor = actor, mode = mode))
    }

    internal fun build(): ConversationScenario = ConversationScenario(name = name, steps = steps.toList())
}
