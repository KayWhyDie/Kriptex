package com.ivor.kriptex.deliverypolicy.conversationfacade

import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionCoordinator
import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionDecision
import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionState
import com.ivor.kriptex.deliverypolicy.conversationattention.UnreadDecision
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationStateAggregator
import com.ivor.kriptex.deliverypolicy.conversationtruststate.ConversationTrustStateEngine
import com.ivor.kriptex.deliverypolicy.conversationtruststate.TrustSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.runningFold

/**
 * Read-only composition layer that exposes a single authoritative read model per conversation.
 *
 * - No protocol/crypto changes.
 * - No persistence IO.
 * - Deterministic: emits [ConversationView] with distinctUntilChanged().
 */
class ConversationFacade(
    private val conversationId: String,
    private val state: ConversationStateAggregator,
    private val trust: ConversationTrustStateEngine,
    private val attention: ConversationAttentionCoordinator,
    private val debugTrace: ConversationFacadeDebugTrace = NoOpConversationFacadeDebugTrace,
) {

    fun snapshot(
        attentionState: ConversationAttentionState = attention.attentionStateFor(conversationId),
        unreadCount: Int = 0,
    ): ConversationView {
        val snap = state.snapshot()
        val trustSnap = trust.snapshot()
        return ConversationView(
            conversationId = conversationId,
            snapshot = snap,
            trust = trustSnap,
            attention = attentionState,
            unreadCount = unreadCount,
            lastActivityTimestamp = snap.lastActivityTimestamp,
        )
    }

    fun observe(): Flow<ConversationView> {
        val snapshots: Flow<ConversationSnapshot> = state.observe()
        val trustSnapshots: Flow<TrustSnapshot> = trust.observe()

        val decisions: Flow<ConversationAttentionDecision> = attention.decisions
            .filter { it.conversationId == conversationId }

        val attentionState: Flow<ConversationAttentionState> = decisions
            .map { it.attentionState }
            .onStart { emit(attention.attentionStateFor(conversationId)) }
            .distinctUntilChanged()

        val unreadCount: Flow<Int> = decisions
            .runningFold(0) { currentCount, decision ->
                when {
                    decision.attentionState == ConversationAttentionState.VISIBLE -> 0
                    decision.unread == UnreadDecision.INCREMENT -> currentCount + 1
                    else -> currentCount
                }
            }
            .distinctUntilChanged()

        var baselineEmitted = false
        var last: ConversationView? = null

        return combine(snapshots, trustSnapshots, attentionState, unreadCount) { snap, trustSnap, attn, unread ->
            ConversationView(
                conversationId = conversationId,
                snapshot = snap,
                trust = trustSnap,
                attention = attn,
                unreadCount = unread,
                lastActivityTimestamp = snap.lastActivityTimestamp,
            )
        }
            .distinctUntilChanged()
            .onEach { next ->
                if (!baselineEmitted) {
                    baselineEmitted = true
                    last = next
                    return@onEach
                }

                val prev = last
                if (prev != null && prev != next) {
                    val changed = LinkedHashSet<String>()
                    if (prev.snapshot != next.snapshot) changed.add("state")
                    if (prev.trust != next.trust) changed.add("trust")
                    if (prev.attention != next.attention || prev.unreadCount != next.unreadCount) changed.add("attention")

                    val reason = when {
                        changed.size == 1 -> changed.first() + "_changed"
                        changed.isEmpty() -> "no_change"
                        else -> "multiple_changed"
                    }

                    debugTrace.onViewUpdated(conversationId = conversationId, reason = reason, changedComponents = changed)
                }
                last = next
            }
    }
}
