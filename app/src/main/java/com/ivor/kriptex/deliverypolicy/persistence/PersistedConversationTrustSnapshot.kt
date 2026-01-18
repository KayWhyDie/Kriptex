package com.ivor.kriptex.deliverypolicy.persistence

/**
 * Persistable snapshot of conversation trust / verification acknowledgements.
 *
 * Safety:
 * - No secrets
 * - No payloads
 * - No transport identifiers
 */
data class PersistedConversationTrustSnapshot(
    val version: Int = 1,
    val capturedAtElapsedMs: Long,
    val conversations: List<PersistedConversationTrustState>,
)

data class PersistedConversationTrustState(
    val conversationId: String,
    /** User explicitly verified this conversation at least once. */
    val verified: Boolean,
    /**
     * Set of acknowledged issue keys. Keys are opaque strings produced by the trust engine.
     *
     * This is used to ensure restore-safe behavior (no duplicate alerts after restart).
     */
    val acknowledgedIssueKeys: List<String>,
)
