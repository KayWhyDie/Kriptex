package com.ivor.kriptex.deliverypolicy.conversationtruststate

enum class TrustLevel {
    VERIFIED,
    UNVERIFIED,
    CHANGED,
    BROKEN,
}

enum class TrustIssue {
    IdentityKeyChanged,
    MissingSenderKey,
    SenderKeyRotated,
    MemberAdded,
    MemberRemoved,
    SessionReset,
}

data class TrustSnapshot(
    val conversationId: String,
    val trustLevel: TrustLevel,
    /** All currently present issue types (acknowledged + unacknowledged). */
    val issues: Set<TrustIssue>,
    /** Issue types that are currently present and not yet acknowledged. */
    val unacknowledgedIssues: Set<TrustIssue>,
    /** Whether the user explicitly verified this conversation. */
    val explicitlyVerified: Boolean,
) {
    val hasUnacknowledgedIssues: Boolean get() = unacknowledgedIssues.isNotEmpty()
}
