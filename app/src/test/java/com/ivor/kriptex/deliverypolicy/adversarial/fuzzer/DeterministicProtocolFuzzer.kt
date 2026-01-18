package com.ivor.kriptex.deliverypolicy.adversarial.fuzzer

import com.ivor.kriptex.deliverypolicy.DeliveryMode
import com.ivor.kriptex.deliverypolicy.adversarial.ConversationScenario
import com.ivor.kriptex.deliverypolicy.adversarial.ConversationScenarioRunner

/**
 * Deterministic protocol fuzzer (test-only).
 *
 * - No randomness: pure enumeration.
 * - Bounded exploration: limits by max length, max sequences, and a wall-clock budget.
 * - Uses the real ConversationScenarioRunner and invariant oracle.
 */
object DeterministicProtocolFuzzer {

    /** Closed action alphabet. */
    sealed interface Action {
        data object SEND_OUTBOUND : Action
        data object RECEIVE_INBOUND : Action
        data object ACK : Action
        data object DUPLICATE_LAST : Action
        data object DROP_LAST : Action
        data class REORDER_WINDOW(val size: Int) : Action
        data object CUT_START : Action
        data object CUT_END : Action
        data class REORDER_ACROSS_CUTS(val windowSize: Int) : Action
        data object SNAPSHOT_RESTORE : Action
        data object VISIBILITY_TOGGLE : Action
        data object DELIVERY_MODE_TOGGLE : Action
    }

    data class Config(
        val maxLen: Int,
        val maxSequences: Int = 20_000,
        val maxMillis: Long = 10_000,
        val reorderWindowSizes: List<Int> = listOf(2, 3),
        val reorderAcrossCutWindowSizes: List<Int> = listOf(2, 3, 4),
        val maxCutDepth: Int = 2,
        val maxConsecutiveDuplicates: Int = 2,
    ) {
        init {
            require(maxLen >= 1)
            require(maxSequences >= 1)
            require(maxMillis >= 1)
            require(reorderWindowSizes.all { it >= 2 })
            require(reorderAcrossCutWindowSizes.all { it >= 2 })
            require(maxCutDepth >= 0)
            require(maxConsecutiveDuplicates >= 0)
        }
    }

    private enum class Kind { USER, ACK }

    private data class ModelPacket(
        val id: String,
        val from: ConversationScenario.Actor,
        val to: ConversationScenario.Actor,
        val kind: Kind,
        val messageId: String,
        val ackedMessageId: String? = null,
        /** Snapshot of active cut ids at send time (nested cuts supported). */
        val cutIdsAtSend: List<String> = emptyList(),
    )

    private data class ModelState(
        val nextPacketId: Int,
        val queue: List<ModelPacket>,
        val nextUserId: Int,
        val nextInjectedAckId: Int,
        val nextCutId: Int,
        val activeCuts: List<String>,
        val endedCuts: Set<String>,
        val lastSentOutboundMessageId: String?,
        val ackCounterB: Int,
        val processedInboundAtB: Set<String>,
        val visibleA: Boolean,
        val activeA: Boolean,
        val consecutiveDup: Int,
        val consecutiveRestore: Int,
    )

    private fun initialState(): ModelState {
        // ConversationScenarioRunner establishes a session first, which enqueues/removes 2 packets (p1, p2).
        // The deterministic network id counter therefore starts subsequent scenarios at p3.
        return ModelState(
            nextPacketId = 3,
            queue = emptyList(),
            nextUserId = 1,
            nextInjectedAckId = 1,
            nextCutId = 1,
            activeCuts = emptyList(),
            endedCuts = emptySet(),
            lastSentOutboundMessageId = null,
            ackCounterB = 1,
            processedInboundAtB = emptySet(),
            visibleA = false,
            activeA = true,
            consecutiveDup = 0,
            consecutiveRestore = 0,
        )
    }

    private fun isDeliverable(pkt: ModelPacket, state: ModelState): Boolean {
        // Deliverable only when all cuts active at send time are ended.
        return pkt.cutIdsAtSend.all { state.endedCuts.contains(it) }
    }

    private data class ApplyResult(
        val next: ModelState,
        val emittedStep: (ConversationScenarioBuilderProxy.() -> Unit)?,
        val applied: Boolean,
    )

    private class ConversationScenarioBuilderProxy(
        val conversationId: String,
        val peerId: String,
        val steps: MutableList<ConversationScenario.Step>,
    ) {
        fun add(step: ConversationScenario.Step) {
            steps.add(step)
        }
    }

    private fun toHandle(pkt: ModelPacket): ConversationScenario.PacketHandle {
        return ConversationScenario.PacketHandle(
            id = pkt.id,
            from = pkt.from,
            to = pkt.to,
            messageId = pkt.messageId,
        )
    }

    private fun applyAction(state: ModelState, action: Action, config: Config): ApplyResult {
        val convId = "c_fuzz"
        val allTargets = setOf(
            ConversationScenario.RestoreTarget.MESSAGE_STORE,
            ConversationScenario.RestoreTarget.LEDGER,
            ConversationScenario.RestoreTarget.OUTBOX,
            ConversationScenario.RestoreTarget.SESSION_PROTOCOL_ENGINE,
            ConversationScenario.RestoreTarget.GROUP_STORE,
            ConversationScenario.RestoreTarget.SENDER_KEY_STORE,
            ConversationScenario.RestoreTarget.SENDER_KEY_DISTRIBUTION_STORE,
            ConversationScenario.RestoreTarget.TRUST_STORE,
            ConversationScenario.RestoreTarget.IDENTITY_KEY_STORE,
        )

        fun noop(): ApplyResult = ApplyResult(state, emittedStep = null, applied = false)

        return when (action) {
            Action.SEND_OUTBOUND -> {
                val msgId = "m" + state.nextUserId
                val pktId = "p" + state.nextPacketId
                val pkt = ModelPacket(
                    id = pktId,
                    from = ConversationScenario.Actor.A,
                    to = ConversationScenario.Actor.B,
                    kind = Kind.USER,
                    messageId = msgId,
                    cutIdsAtSend = state.activeCuts,
                )
                val next = state.copy(
                    nextPacketId = state.nextPacketId + 1,
                    queue = state.queue + pkt,
                    nextUserId = state.nextUserId + 1,
                    lastSentOutboundMessageId = msgId,
                    consecutiveDup = 0,
                    consecutiveRestore = 0,
                )
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        val payload = byteArrayOf((state.nextUserId and 0xFF).toByte())
                        add(
                            ConversationScenario.SendOutbound(
                                label = "SEND_OUTBOUND($msgId)",
                                from = ConversationScenario.Actor.A,
                                conversationId = convId,
                                peerId = peerId,
                                protocol = ConversationScenario.OutboundProtocol.User(
                                    messageId = msgId,
                                    createdAtElapsedMs = state.nextUserId.toLong(),
                                    payload = payload,
                                ),
                            ),
                        )
                    },
                )
            }

            Action.RECEIVE_INBOUND -> {
                val head = state.queue.firstOrNull { isDeliverable(it, state) } ?: return noop()
                val remaining = state.queue.toMutableList().also { it.remove(head) }

                var nextState = state.copy(
                    queue = remaining,
                    consecutiveDup = 0,
                    consecutiveRestore = 0,
                )

                // Auto-ACK behavior: receiving a USER at B acks it once (dedup by messageId).
                if (head.kind == Kind.USER && head.to == ConversationScenario.Actor.B) {
                    if (!state.processedInboundAtB.contains(head.messageId)) {
                        val ackMsgId = "ack" + state.ackCounterB
                        val ackPktId = "p" + nextState.nextPacketId
                        val ackPkt = ModelPacket(
                            id = ackPktId,
                            from = ConversationScenario.Actor.B,
                            to = ConversationScenario.Actor.A,
                            kind = Kind.ACK,
                            messageId = ackMsgId,
                            ackedMessageId = head.messageId,
                            cutIdsAtSend = state.activeCuts,
                        )
                        nextState = nextState.copy(
                            nextPacketId = nextState.nextPacketId + 1,
                            ackCounterB = state.ackCounterB + 1,
                            processedInboundAtB = state.processedInboundAtB + head.messageId,
                            queue = nextState.queue + ackPkt,
                        )
                    }
                }

                ApplyResult(
                    next = nextState,
                    applied = true,
                    emittedStep = {
                        add(ConversationScenario.ReceiveInbound(label = "RECEIVE_INBOUND(${head.id})", packet = toHandle(head)))
                    },
                )
            }

            Action.ACK -> {
                val acked = state.lastSentOutboundMessageId ?: return noop()
                val ackMsgId = "fack" + state.nextInjectedAckId
                val pktId = "p" + state.nextPacketId
                val pkt = ModelPacket(
                    id = pktId,
                    from = ConversationScenario.Actor.B,
                    to = ConversationScenario.Actor.A,
                    kind = Kind.ACK,
                    messageId = ackMsgId,
                    ackedMessageId = acked,
                    cutIdsAtSend = state.activeCuts,
                )
                val next = state.copy(
                    nextPacketId = state.nextPacketId + 1,
                    nextInjectedAckId = state.nextInjectedAckId + 1,
                    queue = state.queue + pkt,
                    consecutiveDup = 0,
                    consecutiveRestore = 0,
                )
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(
                            ConversationScenario.InjectAck(
                                label = "ACK($acked)",
                                from = ConversationScenario.Actor.B,
                                to = ConversationScenario.Actor.A,
                                conversationId = convId,
                                ackMessageId = ackMsgId,
                                ackedMessageId = acked,
                            ),
                        )
                    },
                )
            }

            Action.DUPLICATE_LAST -> {
                val last = state.queue.lastOrNull() ?: return noop()
                if (state.consecutiveDup >= config.maxConsecutiveDuplicates) return noop()
                val dupId = last.id + ":dup1"
                val dupPkt = last.copy(id = dupId)
                val next = state.copy(
                    queue = state.queue + dupPkt,
                    consecutiveDup = state.consecutiveDup + 1,
                    consecutiveRestore = 0,
                )
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(ConversationScenario.Duplicate(label = "DUPLICATE_LAST(${last.id})", packet = toHandle(last), times = 2))
                    },
                )
            }

            Action.DROP_LAST -> {
                val last = state.queue.lastOrNull() ?: return noop()
                val next = state.copy(
                    queue = state.queue.dropLast(1),
                    consecutiveDup = 0,
                    consecutiveRestore = 0,
                )
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(ConversationScenario.Drop(label = "DROP_LAST(${last.id})", packet = toHandle(last)))
                    },
                )
            }

            is Action.REORDER_WINDOW -> {
                val n = action.size
                if (state.queue.size < n) return noop()
                val reordered = state.queue.take(n).reversed() + state.queue.drop(n)
                val next = state.copy(
                    queue = reordered,
                    consecutiveDup = 0,
                    consecutiveRestore = 0,
                )
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(ConversationScenario.ReorderWindow(label = "REORDER_WINDOW($n)", size = n))
                    },
                )
            }

            Action.CUT_START -> {
                if (state.activeCuts.size >= config.maxCutDepth) return noop()
                val cutId = "cut" + state.nextCutId
                val next = state.copy(
                    nextCutId = state.nextCutId + 1,
                    activeCuts = state.activeCuts + cutId,
                    consecutiveDup = 0,
                    consecutiveRestore = 0,
                )
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(ConversationScenario.CutStart(label = "CUT_START($cutId)", cutId = cutId))
                    },
                )
            }

            Action.CUT_END -> {
                val cutId = state.activeCuts.lastOrNull() ?: return noop()
                // Adversarial release ordering mirrors DeterministicNetwork.endCut.
                val ended = state.endedCuts + cutId
                val remainingCuts = state.activeCuts.dropLast(1)

                val newlyUnblocked = state.queue.filter { it.cutIdsAtSend.contains(cutId) && it.cutIdsAtSend.all { id -> ended.contains(id) } }
                val still = state.queue.filterNot { newlyUnblocked.contains(it) }
                val reordered = newlyUnblocked.asReversed() + still

                val next = state.copy(
                    activeCuts = remainingCuts,
                    endedCuts = ended,
                    queue = reordered,
                    consecutiveDup = 0,
                    consecutiveRestore = 0,
                )
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(ConversationScenario.CutEnd(label = "CUT_END($cutId)", cutId = cutId))
                    },
                )
            }

            is Action.REORDER_ACROSS_CUTS -> {
                val n = action.windowSize
                if (state.queue.size < n) return noop()
                val reordered = state.queue.take(n).reversed() + state.queue.drop(n)
                val next = state.copy(queue = reordered, consecutiveDup = 0, consecutiveRestore = 0)
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(ConversationScenario.ReorderAcrossCuts(label = "REORDER_ACROSS_CUTS($n)", windowSize = n))
                    },
                )
            }

            Action.SNAPSHOT_RESTORE -> {
                if (state.consecutiveRestore >= 1) return noop()
                val next = state.copy(consecutiveDup = 0, consecutiveRestore = state.consecutiveRestore + 1)
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(
                            ConversationScenario.SnapshotAndRestore(
                                label = "SNAPSHOT_RESTORE",
                                actors = setOf(ConversationScenario.Actor.A, ConversationScenario.Actor.B),
                                targets = allTargets,
                                assertEquivalent = false,
                            ),
                        )
                    },
                )
            }

            Action.VISIBILITY_TOGGLE -> {
                val nextVisible = !state.visibleA
                val next = state.copy(visibleA = nextVisible, consecutiveDup = 0, consecutiveRestore = 0)
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(
                            ConversationScenario.SetVisibility(
                                label = "VISIBILITY_TOGGLE(${if (nextVisible) "VISIBLE" else "HIDDEN"})",
                                actor = ConversationScenario.Actor.A,
                                visibility = if (nextVisible) ConversationScenario.Visibility.VISIBLE else ConversationScenario.Visibility.HIDDEN,
                            ),
                        )
                    },
                )
            }

            Action.DELIVERY_MODE_TOGGLE -> {
                val nextActive = !state.activeA
                val next = state.copy(activeA = nextActive, consecutiveDup = 0, consecutiveRestore = 0)
                ApplyResult(
                    next = next,
                    applied = true,
                    emittedStep = {
                        add(
                            ConversationScenario.SetDeliveryMode(
                                label = "DELIVERY_MODE_TOGGLE(${if (nextActive) "ACTIVE" else "PASSIVE"})",
                                actor = ConversationScenario.Actor.A,
                                mode = if (nextActive) DeliveryMode.ACTIVE else DeliveryMode.PASSIVE,
                            ),
                        )
                    },
                )
            }
        }
    }

    /**
     * Enumerates sequences up to [config.maxLen], applying pruning rules.
     *
     * Returned sequences are in deterministic order.
     */
    fun enumerate(config: Config): Sequence<List<Action>> = sequence {
        val start = System.currentTimeMillis()
        var yielded = 0

        val alphabet: List<Action> = buildList {
            add(Action.SEND_OUTBOUND)
            add(Action.RECEIVE_INBOUND)
            add(Action.ACK)
            add(Action.DUPLICATE_LAST)
            add(Action.DROP_LAST)
            config.reorderWindowSizes.forEach { add(Action.REORDER_WINDOW(it)) }
            add(Action.CUT_START)
            add(Action.CUT_END)
            config.reorderAcrossCutWindowSizes.forEach { add(Action.REORDER_ACROSS_CUTS(it)) }
            add(Action.SNAPSHOT_RESTORE)
            add(Action.VISIBILITY_TOGGLE)
            add(Action.DELIVERY_MODE_TOGGLE)
        }

        suspend fun SequenceScope<List<Action>>.dfs(prefix: List<Action>, state: ModelState) {
            if (yielded >= config.maxSequences) return
            if (System.currentTimeMillis() - start > config.maxMillis) return

            if (prefix.isNotEmpty()) {
                yield(prefix)
                yielded++
                if (yielded >= config.maxSequences) return
            }
            if (prefix.size >= config.maxLen) return

            for (a in alphabet) {
                // Basic pruning: avoid no-op extensions.
                val res = applyAction(state, a, config)
                if (!res.applied) continue

                // Redundant restore chains (already captured in applyAction with consecutiveRestore cap).
                // Cap duplicate depth (already captured in applyAction with consecutiveDup cap).

                dfs(prefix + a, res.next)
                if (yielded >= config.maxSequences) return
                if (System.currentTimeMillis() - start > config.maxMillis) return
            }
        }

        dfs(emptyList(), initialState())
    }

    /** Build a concrete ConversationScenario for a given action sequence. */
    fun buildScenario(actions: List<Action>, config: Config, name: String): ConversationScenario {
        val convId = "c_fuzz"
        val peerId = "B"
        val steps = ArrayList<ConversationScenario.Step>(actions.size + 1)
        val proxy = ConversationScenarioBuilderProxy(conversationId = convId, peerId = peerId, steps = steps)

        var state = initialState()
        for (a in actions) {
            val res = applyAction(state, a, config)
            state = res.next
            res.emittedStep?.invoke(proxy)
        }

        // Close any remaining cuts deterministically (LIFO) so we can drain and then apply the
        // end-of-sequence restore-equivalence oracle.
        while (state.activeCuts.isNotEmpty()) {
            val res = applyAction(state, Action.CUT_END, config)
            state = res.next
            res.emittedStep?.invoke(proxy)
        }

        // Deterministic finalization: drain all in-flight packets to reach a quiescent state before
        // applying the end-of-sequence restore-equivalence oracle.
        var drainSteps = 0
        val maxDrainSteps = 1_000
        while (state.queue.isNotEmpty() && drainSteps < maxDrainSteps) {
            val res = applyAction(state, Action.RECEIVE_INBOUND, config)
            if (!res.applied) break
            state = res.next
            res.emittedStep?.invoke(proxy)
            drainSteps++
        }
        require(state.queue.isEmpty()) { "drain_network_exceeded_max_steps($maxDrainSteps)" }

        // Liveness under fairness (test-only): only enforce when the sequence respects the fairness rule
        // "every queued packet is eventually eligible for delivery".
        // An explicit DROP violates that assumption.
        val isFair = actions.none { it == Action.DROP_LAST }
        if (isFair) {
            steps.add(
                ConversationScenario.AssertLivenessUnderFairness(
                    label = "ASSERT_LIVENESS_UNDER_FAIRNESS",
                    maxDrainMultiplier = 8,
                ),
            )
        }

        // End-of-sequence oracle: restore equivalence.
        val allTargets = setOf(
            ConversationScenario.RestoreTarget.MESSAGE_STORE,
            ConversationScenario.RestoreTarget.LEDGER,
            ConversationScenario.RestoreTarget.OUTBOX,
            ConversationScenario.RestoreTarget.SESSION_PROTOCOL_ENGINE,
            ConversationScenario.RestoreTarget.GROUP_STORE,
            ConversationScenario.RestoreTarget.SENDER_KEY_STORE,
            ConversationScenario.RestoreTarget.SENDER_KEY_DISTRIBUTION_STORE,
            ConversationScenario.RestoreTarget.TRUST_STORE,
            ConversationScenario.RestoreTarget.IDENTITY_KEY_STORE,
        )
        steps.add(
            ConversationScenario.SnapshotAndRestore(
                label = "FINAL_RESTORE_EQ",
                actors = setOf(ConversationScenario.Actor.A, ConversationScenario.Actor.B),
                targets = allTargets,
                assertEquivalent = true,
            ),
        )

        return ConversationScenario(
            name = name,
            steps = steps,
            enableResourceAndLivenessValidation = true,
        )
    }

    /** Runs a sequence; throws AssertionError with diagnostics if it fails. */
    fun run(actions: List<Action>, config: Config, sequenceName: String = "fuzz"): Unit {
        val scenario = buildScenario(actions, config = config, name = sequenceName)
        val runner = ConversationScenarioRunner(enableDiagnostics = true)
        runner.runScenario(scenario)
    }

    enum class FailureKind {
        SAFETY_OR_RESOURCE,
        LIVENESS,
    }

    fun classifyFailure(message: String?): FailureKind {
        val m = message ?: return FailureKind.SAFETY_OR_RESOURCE
        return if (m.contains("liveness_") || m.contains("starvation_")) FailureKind.LIVENESS else FailureKind.SAFETY_OR_RESOURCE
    }

    /**
     * Heuristic minimizer:
     * - Prefer shrinking reorder windows before deleting actions.
     * - Prefer removing cut actions before removing SENDs.
     */
    fun minimizeFailing(actions: List<Action>, failureKind: FailureKind, failurePredicate: (List<Action>) -> Boolean): List<Action> {
        var current = actions

        fun deletionPriority(a: Action): Int {
            return when (failureKind) {
                FailureKind.SAFETY_OR_RESOURCE -> when (a) {
                    Action.CUT_START, Action.CUT_END -> 0
                    is Action.REORDER_ACROSS_CUTS -> 1
                    is Action.REORDER_WINDOW -> 2
                    Action.SNAPSHOT_RESTORE -> 3
                    Action.VISIBILITY_TOGGLE, Action.DELIVERY_MODE_TOGGLE -> 4
                    Action.DUPLICATE_LAST, Action.DROP_LAST -> 5
                    Action.ACK, Action.RECEIVE_INBOUND -> 6
                    Action.SEND_OUTBOUND -> 7
                }

                FailureKind.LIVENESS -> when (a) {
                    // Aggressively shrink message count first.
                    Action.SEND_OUTBOUND -> 0
                    Action.RECEIVE_INBOUND, Action.ACK -> 1
                    Action.DUPLICATE_LAST, Action.DROP_LAST -> 2
                    is Action.REORDER_WINDOW -> 3
                    is Action.REORDER_ACROSS_CUTS -> 4
                    Action.SNAPSHOT_RESTORE -> 5
                    Action.VISIBILITY_TOGGLE -> 6

                    // Preserve CUT structure and downgrade/upgrade oscillations.
                    Action.DELIVERY_MODE_TOGGLE -> 9
                    Action.CUT_START, Action.CUT_END -> 10
                }
            }
        }

        fun isProtectedFromDeletion(a: Action): Boolean {
            return failureKind == FailureKind.LIVENESS && (a == Action.CUT_START || a == Action.CUT_END || a == Action.DELIVERY_MODE_TOGGLE)
        }

        var changed: Boolean
        do {
            changed = false

            // 1) Shrink reorder window sizes in-place.
            run {
                for (i in current.indices) {
                    val a = current[i]
                    when (a) {
                        is Action.REORDER_WINDOW -> {
                            if (a.size <= 2) continue
                            for (newSize in 2 until a.size) {
                                val candidate = current.toMutableList()
                                candidate[i] = Action.REORDER_WINDOW(newSize)
                                if (failurePredicate(candidate)) {
                                    current = candidate
                                    changed = true
                                    return@run
                                }
                            }
                        }

                        is Action.REORDER_ACROSS_CUTS -> {
                            if (a.windowSize <= 2) continue
                            for (newSize in 2 until a.windowSize) {
                                val candidate = current.toMutableList()
                                candidate[i] = Action.REORDER_ACROSS_CUTS(newSize)
                                if (failurePredicate(candidate)) {
                                    current = candidate
                                    changed = true
                                    return@run
                                }
                            }
                        }

                        else -> Unit
                    }
                }
            }
            if (changed) continue

            // 2) Delete actions, in priority order.
            val indices = current.indices.sortedWith(compareBy({ deletionPriority(current[it]) }, { it }))
            for (i in indices) {
                if (isProtectedFromDeletion(current[i])) continue
                val candidate = current.toMutableList().also { it.removeAt(i) }
                if (candidate.isEmpty()) continue
                if (failurePredicate(candidate)) {
                    current = candidate
                    changed = true
                    break
                }
            }
        } while (changed)

        return current
    }
}
