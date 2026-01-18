package com.ivor.kriptex.deliverypolicy.conversationfacade

import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionState
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot
import com.ivor.kriptex.deliverypolicy.conversationtruststate.TrustSnapshot

/**
 * Read-only, authoritative view of a single conversation by composing derived subsystems.
 */
data class ConversationView(
    val conversationId: String,
    /** Derived snapshot (health / encryption / backlog / lastActivityTimestamp). */
    val snapshot: ConversationSnapshot,
    /** Derived trust snapshot (identity, membership, sender keys, etc). */
    val trust: TrustSnapshot,
    /** Current attention state from [com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionCoordinator]. */
    val attention: ConversationAttentionState,
    /** Derived unread counter from coordinator decisions for this conversation. */
    val unreadCount: Int,
    /** Convenience: mirrors [snapshot.lastActivityTimestamp]. */
    val lastActivityTimestamp: Long,
)
