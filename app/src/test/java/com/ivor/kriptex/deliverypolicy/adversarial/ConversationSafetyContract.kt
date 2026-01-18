package com.ivor.kriptex.deliverypolicy.adversarial

import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationView
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationDeliveryLedgerSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessageStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedLedgerState
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage
import com.ivor.kriptex.deliverypolicy.protocol.AckMessage
import com.ivor.kriptex.deliverypolicy.protocol.BinaryProtocolCodec
import com.ivor.kriptex.deliverypolicy.protocol.ProtocolMessage
import com.ivor.kriptex.deliverypolicy.protocol.SenderKeyDistributionMessage
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage

/**
 * Test-only, global safety contract.
 *
 * This is intentionally cross-layer: it ties together what is persisted (store/ledger)
 * with what is observable (ConversationView), and with minimal security telemetry
 * (session decrypt success) provided by the adversarial runner.
 */
object ConversationSafetyContract {

    enum class ViolationSeverity {
        ERROR,
        WARN,
    }

    data class ViolationEvent(
        val contractName: String,
        val stepIndex: Int,
        val severity: ViolationSeverity,
        val details: String,
        val relatedMessageIds: Set<String> = emptySet(),
    )

    data class Violation(
        val id: String,
        val message: String,
        val relatedMessageIds: Set<String> = emptySet(),
    )

    data class RestoreContext(
        val isRestoreStep: Boolean,
        val restoredTargets: Set<ConversationScenario.RestoreTarget> = emptySet(),
    )

    fun checkAfterStep(
        stepIndex: Int,
        stepLabel: String,
        actor: ConversationScenario.Actor,
        previousView: ConversationView,
        nextView: ConversationView,
        nextStore: PersistedConversationMessageStoreSnapshot,
        nextLedger: PersistedConversationDeliveryLedgerSnapshot,
        sessionDecryptSucceededMessageIds: Set<String>,
        restore: RestoreContext,
    ): List<ViolationEvent> {
        val violations = ArrayList<Violation>()
        val codec = BinaryProtocolCodec()

        fun ledgerState(messageId: String): PersistedLedgerState? {
            return nextLedger.entries.firstOrNull { it.messageId == messageId }?.state
        }

        // Contract: restore must not cause user-facing unread increases.
        if (restore.isRestoreStep) {
            if (nextView.unreadCount > previousView.unreadCount) {
                violations += Violation(
                    id = "restore_unread_increased",
                    message = "unreadCount increased on restore (prev=${previousView.unreadCount} next=${nextView.unreadCount})",
                )
            }
        }

        // Contract: persisted inbound USER/DISTRIBUTION must have ledger.RECEIVED+.
        // (Tripwire: store mutation without ledger.)
        for ((messageId, msg) in nextStore.messages) {
            if (msg.direction != ConversationMessage.Direction.INBOUND) continue

            val decoded: ProtocolMessage = try {
                codec.decode(msg.payload)
            } catch (_: Exception) {
                continue
            }

            when (decoded) {
                is UserMessage,
                is SenderKeyDistributionMessage,
                -> {
                    val state = ledgerState(messageId)
                    if (state == null || state.ordinal < PersistedLedgerState.RECEIVED.ordinal) {
                        violations += Violation(
                            id = "store_inbound_without_ledger_received",
                            message = "inbound ${decoded.type} stored but ledger not RECEIVED+ (ledger=$state)",
                            relatedMessageIds = setOf(messageId),
                        )
                    }

                    // Contract: no plaintext USER is persisted without session AEAD auth.
                    // (Tripwire: bypassing session/router and calling protocol inbound directly.)
                    if (decoded is UserMessage && !sessionDecryptSucceededMessageIds.contains(messageId)) {
                        violations += Violation(
                            id = "plaintext_user_without_session_auth",
                            message = "inbound USER stored without matching session decrypt success",
                            relatedMessageIds = setOf(messageId),
                        )
                    }
                }

                else -> Unit
            }
        }

        // Contract: if the message store claims a message is ACKED and the ledger has an entry for it,
        // the ledger must also reflect ACKED.
        //
        // NOTE: outbound messages can exist in the store without a persisted ledger entry (e.g. when
        // enqueue events are staged or not recorded). We therefore only flag contradictions (ledger
        // present but not ACKED), not absence.
        nextStore.messages.values.forEach { m ->
            if (m.state != ConversationMessage.State.ACKED) return@forEach
            val st = ledgerState(m.messageId) ?: return@forEach
            if (st != PersistedLedgerState.ACKED) {
                violations += Violation(
                    id = "store_acked_without_ledger_acked",
                    message = "store has ACKED but ledger is not ACKED (ledger=$st)",
                    relatedMessageIds = setOf(m.messageId),
                )
            }
        }

        // Contract: encryption should never regress in observable view within one scenario step.
        // This is scoped to the view transition we validate (quiescent sample).
        if (previousView.snapshot.encryptionStatus == com.ivor.kriptex.deliverypolicy.conversationstate.ConversationEncryptionStatus.OK &&
            nextView.snapshot.encryptionStatus == com.ivor.kriptex.deliverypolicy.conversationstate.ConversationEncryptionStatus.SESSION_INVALID
        ) {
            violations += Violation(
                id = "encryption_regressed_to_session_invalid",
                message = "encryptionStatus regressed OK -> SESSION_INVALID",
            )
        }

        return violations
            .map {
                ViolationEvent(
                    contractName = it.id,
                    stepIndex = stepIndex,
                    severity = ViolationSeverity.ERROR,
                    details = it.message,
                    relatedMessageIds = it.relatedMessageIds,
                )
            }
    }
}
