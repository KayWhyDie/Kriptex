package com.ivor.kriptex.deliverypolicy.conversationtruststate

import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationTrustSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationTrustState

/**
 * Persists trust acknowledgements and explicit verification.
 */
interface ConversationTrustStore {
    fun isExplicitlyVerified(conversationId: String): Boolean

    fun setExplicitlyVerified(conversationId: String, verified: Boolean)

    fun acknowledgedIssueKeys(conversationId: String): Set<String>

    /**
     * Adds acknowledgment keys.
     *
     * For baseline-style keys (e.g., PeerIdentity/Session/GroupMembers/SenderKey/LocalSenderKey), implementations
     * should treat acknowledgment as a replacement of the prior baseline for that category, so future changes can be
     * detected and cleared correctly.
     */
    fun acknowledgeIssueKeys(conversationId: String, keys: Set<String>)

    fun snapshot(capturedAtElapsedMs: Long): PersistedConversationTrustSnapshot

    fun restore(snapshot: PersistedConversationTrustSnapshot)
}

class InMemoryConversationTrustStore : ConversationTrustStore {

    private data class Entry(
        var verified: Boolean,
        val acknowledged: LinkedHashSet<String>,
    )

    private val byConversationId = LinkedHashMap<String, Entry>()

    @Synchronized
    override fun isExplicitlyVerified(conversationId: String): Boolean = byConversationId[conversationId]?.verified ?: false

    @Synchronized
    override fun setExplicitlyVerified(conversationId: String, verified: Boolean) {
        val e = byConversationId.getOrPut(conversationId) { Entry(verified = false, acknowledged = LinkedHashSet()) }
        e.verified = verified
    }

    @Synchronized
    override fun acknowledgedIssueKeys(conversationId: String): Set<String> {
        return byConversationId[conversationId]?.acknowledged?.toSet() ?: emptySet()
    }

    @Synchronized
    override fun acknowledgeIssueKeys(conversationId: String, keys: Set<String>) {
        if (keys.isEmpty()) return
        val e = byConversationId.getOrPut(conversationId) { Entry(verified = false, acknowledged = LinkedHashSet()) }

        // Replace baseline keys so acknowledgements can clear change-based issues.
        keys.forEach { k ->
            when {
                k.startsWith("PeerIdentity:") -> e.acknowledged.removeAll { it.startsWith("PeerIdentity:") }
                k.startsWith("Session:") -> e.acknowledged.removeAll { it.startsWith("Session:") }
                k.startsWith("GroupMembers:") -> e.acknowledged.removeAll { it.startsWith("GroupMembers:") }
                k.startsWith("LocalSenderKey:") -> e.acknowledged.removeAll { it.startsWith("LocalSenderKey:") }
                k.startsWith("SenderKey:") -> {
                    // SenderKey:<senderHash>:<id>
                    val parts = k.split(":")
                    if (parts.size >= 3) {
                        val prefix = "SenderKey:${parts[1]}:"
                        e.acknowledged.removeAll { it.startsWith(prefix) }
                    }
                }
            }
        }

        e.acknowledged.addAll(keys)
    }

    @Synchronized
    override fun snapshot(capturedAtElapsedMs: Long): PersistedConversationTrustSnapshot {
        val states = byConversationId.entries.map { (cid, e) ->
            PersistedConversationTrustState(
                conversationId = cid,
                verified = e.verified,
                acknowledgedIssueKeys = e.acknowledged.toList(),
            )
        }
        return PersistedConversationTrustSnapshot(
            capturedAtElapsedMs = capturedAtElapsedMs,
            conversations = states,
        )
    }

    @Synchronized
    override fun restore(snapshot: PersistedConversationTrustSnapshot) {
        byConversationId.clear()
        snapshot.conversations.forEach { s ->
            byConversationId[s.conversationId] = Entry(
                verified = s.verified,
                acknowledged = LinkedHashSet(s.acknowledgedIssueKeys),
            )
        }
    }
}
