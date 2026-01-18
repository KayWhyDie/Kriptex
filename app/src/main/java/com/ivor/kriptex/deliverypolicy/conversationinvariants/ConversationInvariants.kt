package com.ivor.kriptex.deliverypolicy.conversationinvariants

import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionState
import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationView
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationEncryptionStatus
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationType
import com.ivor.kriptex.deliverypolicy.conversationtruststate.TrustIssue
import com.ivor.kriptex.deliverypolicy.conversationtruststate.TrustLevel

/**
 * Pure, side-effect-free invariants over [ConversationView].
 *
 * These functions do not read stores, do not perform IO, and must not log sensitive payloads/keys.
 */
object ConversationInvariants {

    private val groupOnlyIssues: Set<TrustIssue> = setOf(
        TrustIssue.MissingSenderKey,
        TrustIssue.SenderKeyRotated,
        TrustIssue.MemberAdded,
        TrustIssue.MemberRemoved,
    )

    fun check(view: ConversationView): List<ConversationInvariantViolation> {
        val violations = ArrayList<ConversationInvariantViolation>()

        if (view.conversationId != view.snapshot.conversationId) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "conversation_id_matches_snapshot",
                    conversationId = view.conversationId,
                    message = "conversationId must match snapshot.conversationId",
                    details = mapOf(
                        "view" to view.conversationId,
                        "snapshot" to view.snapshot.conversationId,
                    ),
                )
            )
        }

        if (view.conversationId != view.trust.conversationId) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "conversation_id_matches_trust",
                    conversationId = view.conversationId,
                    message = "conversationId must match trust.conversationId",
                    details = mapOf(
                        "view" to view.conversationId,
                        "trust" to view.trust.conversationId,
                    ),
                )
            )
        }

        if (view.lastActivityTimestamp != view.snapshot.lastActivityTimestamp) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "last_activity_mirrors_snapshot",
                    conversationId = view.conversationId,
                    message = "lastActivityTimestamp must mirror snapshot.lastActivityTimestamp",
                    details = mapOf(
                        "view" to view.lastActivityTimestamp.toString(),
                        "snapshot" to view.snapshot.lastActivityTimestamp.toString(),
                    ),
                )
            )
        }

        if (view.unreadCount < 0) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "unread_non_negative",
                    conversationId = view.conversationId,
                    message = "unreadCount must be non-negative",
                    details = mapOf("unreadCount" to view.unreadCount.toString()),
                )
            )
        }

        if (view.snapshot.pendingMessageCount < 0) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "pending_non_negative",
                    conversationId = view.conversationId,
                    message = "pendingMessageCount must be non-negative",
                    details = mapOf("pendingMessageCount" to view.snapshot.pendingMessageCount.toString()),
                )
            )
        }

        if (!view.trust.unacknowledgedIssues.all { it in view.trust.issues }) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "trust_unack_subset",
                    conversationId = view.conversationId,
                    message = "unacknowledgedIssues must be a subset of issues",
                    details = mapOf(
                        "issues" to view.trust.issues.size.toString(),
                        "unacknowledged" to view.trust.unacknowledgedIssues.size.toString(),
                    ),
                )
            )
        }

        val expectedTrust = expectedTrustLevel(
            explicitlyVerified = view.trust.explicitlyVerified,
            issues = view.trust.issues,
        )
        if (view.trust.trustLevel != expectedTrust) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "trust_level_consistent_with_issues",
                    conversationId = view.conversationId,
                    message = "trustLevel must be consistent with issues + explicitlyVerified",
                    details = mapOf(
                        "expected" to expectedTrust.name,
                        "actual" to view.trust.trustLevel.name,
                    ),
                )
            )
        }

        if (view.attention == ConversationAttentionState.VISIBLE && view.unreadCount != 0) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "visible_unread_zero",
                    conversationId = view.conversationId,
                    message = "unreadCount must be 0 when attention is VISIBLE",
                    details = mapOf("unreadCount" to view.unreadCount.toString()),
                )
            )
        }

        if (view.snapshot.conversationType == ConversationType.ONE_TO_ONE) {
            val bad = view.trust.issues.intersect(groupOnlyIssues)
            if (bad.isNotEmpty()) {
                violations.add(
                    ConversationInvariantViolation.error(
                        id = "one_to_one_has_no_group_issues",
                        conversationId = view.conversationId,
                        message = "ONE_TO_ONE conversation must not report group-related trust issues",
                        details = mapOf("issues" to bad.joinToString(",") { it.name }),
                    )
                )
            }
        }

        if (view.snapshot.conversationType == ConversationType.GROUP) {
            val missingIssue = TrustIssue.MissingSenderKey in view.trust.issues

            if (missingIssue && view.snapshot.encryptionStatus == ConversationEncryptionStatus.OK) {
                violations.add(
                    ConversationInvariantViolation.error(
                        id = "group_missing_sender_key_not_ok",
                        conversationId = view.conversationId,
                        message = "GROUP with MissingSenderKey trust issue must not have encryptionStatus OK",
                        details = mapOf("encryptionStatus" to view.snapshot.encryptionStatus.name),
                    )
                )
            }

            if (view.snapshot.encryptionStatus == ConversationEncryptionStatus.MISSING_KEYS && !missingIssue) {
                violations.add(
                    ConversationInvariantViolation.warning(
                        id = "group_missing_keys_should_surface_trust_issue",
                        conversationId = view.conversationId,
                        message = "GROUP with encryptionStatus MISSING_KEYS should surface MissingSenderKey trust issue",
                    )
                )
            }
        }

        return violations
    }

    fun checkTransition(previous: ConversationView, next: ConversationView): List<ConversationInvariantViolation> {
        val violations = ArrayList<ConversationInvariantViolation>()

        if (previous.conversationId != next.conversationId) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "conversation_id_stable",
                    conversationId = next.conversationId,
                    message = "conversationId must remain stable across transitions",
                    details = mapOf(
                        "previous" to previous.conversationId,
                        "next" to next.conversationId,
                    ),
                )
            )
        }

        // Monotonic last-activity (elapsed time) should not go backwards.
        val prevTs = previous.lastActivityTimestamp
        val nextTs = next.lastActivityTimestamp
        if (prevTs > 0L && nextTs == 0L) {
            violations.add(
                ConversationInvariantViolation.warning(
                    id = "last_activity_should_not_forget",
                    conversationId = next.conversationId,
                    message = "lastActivityTimestamp should not drop to 0 after being known",
                )
            )
        } else if (prevTs > 0L && nextTs > 0L && nextTs < prevTs) {
            violations.add(
                ConversationInvariantViolation.error(
                    id = "last_activity_monotonic",
                    conversationId = next.conversationId,
                    message = "lastActivityTimestamp must be monotonic",
                    details = mapOf(
                        "previous" to prevTs.toString(),
                        "next" to nextTs.toString(),
                    ),
                )
            )
        }

        // Unread must never decrease except when becoming visible (reset to 0).
        if (next.unreadCount < previous.unreadCount) {
            val ok = next.attention == ConversationAttentionState.VISIBLE && next.unreadCount == 0
            if (!ok) {
                violations.add(
                    ConversationInvariantViolation.error(
                        id = "unread_only_decreases_on_visible",
                        conversationId = next.conversationId,
                        message = "unreadCount may only decrease by resetting to 0 when VISIBLE",
                        details = mapOf(
                            "previousUnread" to previous.unreadCount.toString(),
                            "nextUnread" to next.unreadCount.toString(),
                            "nextAttention" to next.attention.name,
                        ),
                    )
                )
            }
        }

        return violations
    }

    private fun expectedTrustLevel(explicitlyVerified: Boolean, issues: Set<TrustIssue>): TrustLevel {
        return when {
            TrustIssue.SessionReset in issues -> TrustLevel.BROKEN
            TrustIssue.IdentityKeyChanged in issues -> TrustLevel.CHANGED
            TrustIssue.MemberAdded in issues || TrustIssue.MemberRemoved in issues -> TrustLevel.CHANGED
            TrustIssue.MissingSenderKey in issues -> TrustLevel.UNVERIFIED
            explicitlyVerified && issues.isEmpty() -> TrustLevel.VERIFIED
            else -> TrustLevel.UNVERIFIED
        }
    }
}
