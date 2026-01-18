package com.ivor.kriptex.deliverypolicy.adversarial

import com.ivor.kriptex.deliverypolicy.conversationattention.ConversationAttentionState
import com.ivor.kriptex.deliverypolicy.conversationfacade.ConversationView
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationEncryptionStatus
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationHealth
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationSnapshot
import com.ivor.kriptex.deliverypolicy.conversationstate.ConversationType
import com.ivor.kriptex.deliverypolicy.conversationtruststate.TrustLevel
import com.ivor.kriptex.deliverypolicy.conversationtruststate.TrustSnapshot
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationDeliveryLedgerSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessage
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationMessageStoreSnapshot
import com.ivor.kriptex.deliverypolicy.persistence.PersistedConversationTimeline
import com.ivor.kriptex.deliverypolicy.persistence.PersistedLedgerEntry
import com.ivor.kriptex.deliverypolicy.persistence.PersistedLedgerState
import com.ivor.kriptex.deliverypolicy.protocol.BinaryProtocolCodec
import com.ivor.kriptex.deliverypolicy.protocol.UserMessage
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSafetyContractTest {

    private val codec = BinaryProtocolCodec()

    private fun view(conversationId: String, unread: Int = 0, enc: ConversationEncryptionStatus = ConversationEncryptionStatus.OK): ConversationView {
        return ConversationView(
            conversationId = conversationId,
            snapshot = ConversationSnapshot(
                conversationId = conversationId,
                conversationType = ConversationType.ONE_TO_ONE,
                health = ConversationHealth.ACTIVE,
                encryptionStatus = enc,
                pendingMessageCount = 0,
                lastActivityTimestamp = 0L,
            ),
            trust = TrustSnapshot(
                conversationId = conversationId,
                trustLevel = TrustLevel.VERIFIED,
                issues = emptySet(),
                unacknowledgedIssues = emptySet(),
                explicitlyVerified = true,
            ),
            attention = ConversationAttentionState.FOREGROUND_BACKGROUND,
            unreadCount = unread,
            lastActivityTimestamp = 0L,
        )
    }

    private fun storeSnapshot(conversationId: String, persisted: PersistedConversationMessage): PersistedConversationMessageStoreSnapshot {
        return PersistedConversationMessageStoreSnapshot(
            capturedAtElapsedMs = 0L,
            conversations = mapOf(
                conversationId to PersistedConversationTimeline(conversationId = conversationId, orderedMessageIds = listOf(persisted.messageId)),
            ),
            messages = mapOf(persisted.messageId to persisted),
            nextSendIndexByConversation = mapOf(conversationId to 0),
            nextReceiveIndexByConversation = mapOf(conversationId to 1),
        )
    }

    private fun ledgerSnapshot(entry: PersistedLedgerEntry?): PersistedConversationDeliveryLedgerSnapshot {
        return PersistedConversationDeliveryLedgerSnapshot(
            capturedAtElapsedMs = 0L,
            entries = if (entry == null) emptyList() else listOf(entry),
        )
    }

    @Test
    fun tripwire_plaintext_user_without_session_auth_is_detected() {
        val cid = "c_tripwire"
        val msg = UserMessage(messageId = "m1", conversationId = cid, createdAtElapsedMs = 0L, payload = byteArrayOf(1, 2, 3))
        val persisted = PersistedConversationMessage(
            messageId = msg.messageId,
            conversationId = cid,
            direction = ConversationMessage.Direction.INBOUND,
            payload = codec.encode(msg),
            sendIndex = null,
            receiveIndex = 0,
            state = ConversationMessage.State.RECEIVED,
            timestamps = ConversationMessage.Timestamps(createdAtElapsedMs = 0L, updatedAtElapsedMs = 0L, receivedAtElapsedMs = 0L),
        )

        val violations = ConversationSafetyContract.checkAfterStep(
            stepIndex = 1,
            stepLabel = "T#1",
            actor = ConversationScenario.Actor.A,
            previousView = view(cid),
            nextView = view(cid),
            nextStore = storeSnapshot(cid, persisted),
            nextLedger = ledgerSnapshot(
                PersistedLedgerEntry(
                    messageId = msg.messageId,
                    conversationId = cid,
                    index = 0,
                    state = PersistedLedgerState.RECEIVED,
                ),
            ),
            sessionDecryptSucceededMessageIds = emptySet(),
            restore = ConversationSafetyContract.RestoreContext(isRestoreStep = false),
        )

        assertTrue(violations.any { it.contractName == "plaintext_user_without_session_auth" })
    }

    @Test
    fun tripwire_store_acked_but_ledger_not_acked_is_detected() {
        val cid = "c_tripwire_ack"
        val msg = UserMessage(messageId = "m1", conversationId = cid, createdAtElapsedMs = 0L, payload = byteArrayOf(4))
        val persisted = PersistedConversationMessage(
            messageId = msg.messageId,
            conversationId = cid,
            direction = ConversationMessage.Direction.OUTBOUND,
            payload = codec.encode(msg),
            sendIndex = 0,
            receiveIndex = null,
            state = ConversationMessage.State.ACKED,
            timestamps = ConversationMessage.Timestamps(createdAtElapsedMs = 0L, updatedAtElapsedMs = 0L, ackedAtElapsedMs = 0L),
        )

        val violations = ConversationSafetyContract.checkAfterStep(
            stepIndex = 1,
            stepLabel = "T#1",
            actor = ConversationScenario.Actor.A,
            previousView = view(cid),
            nextView = view(cid),
            nextStore = storeSnapshot(cid, persisted),
            nextLedger = ledgerSnapshot(
                PersistedLedgerEntry(
                    messageId = msg.messageId,
                    conversationId = cid,
                    index = 0,
                    state = PersistedLedgerState.SENT,
                ),
            ),
            sessionDecryptSucceededMessageIds = emptySet(),
            restore = ConversationSafetyContract.RestoreContext(isRestoreStep = false),
        )

        assertTrue(violations.any { it.contractName == "store_acked_without_ledger_acked" })
    }

    @Test
    fun tripwire_store_inbound_without_ledger_received_is_detected() {
        val cid = "c_tripwire_store"
        val msg = UserMessage(messageId = "m1", conversationId = cid, createdAtElapsedMs = 0L, payload = byteArrayOf(7))
        val persisted = PersistedConversationMessage(
            messageId = msg.messageId,
            conversationId = cid,
            direction = ConversationMessage.Direction.INBOUND,
            payload = codec.encode(msg),
            sendIndex = null,
            receiveIndex = 0,
            state = ConversationMessage.State.RECEIVED,
            timestamps = ConversationMessage.Timestamps(createdAtElapsedMs = 0L, updatedAtElapsedMs = 0L, receivedAtElapsedMs = 0L),
        )

        val violations = ConversationSafetyContract.checkAfterStep(
            stepIndex = 1,
            stepLabel = "T#1",
            actor = ConversationScenario.Actor.A,
            previousView = view(cid),
            nextView = view(cid),
            nextStore = storeSnapshot(cid, persisted),
            nextLedger = ledgerSnapshot(null),
            sessionDecryptSucceededMessageIds = setOf("m1"),
            restore = ConversationSafetyContract.RestoreContext(isRestoreStep = false),
        )

        assertTrue(violations.any { it.contractName == "store_inbound_without_ledger_received" })
    }
}
