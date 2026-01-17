package com.ivor.kriptex.deliverypolicy.conversationstate

import com.ivor.kriptex.deliverypolicy.ConnectionState
import com.ivor.kriptex.deliverypolicy.connection.ConnectionStateProvider
import com.ivor.kriptex.deliverypolicy.group.GroupStore
import com.ivor.kriptex.deliverypolicy.group.senderkey.SenderKeyStore
import com.ivor.kriptex.deliverypolicy.ledger.ConversationDeliveryLedger
import com.ivor.kriptex.deliverypolicy.ledger.MessageLifecycle
import com.ivor.kriptex.deliverypolicy.messagestore.ConversationMessageStore
import com.ivor.kriptex.deliverypolicy.session.InMemorySessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Side-effect-free derived state for a single conversation.
 */
class ConversationStateAggregator(
    private val conversationId: String,
    private val messageStore: ConversationMessageStore,
    private val ledger: ConversationDeliveryLedger,
    private val connectionStateProvider: ConnectionStateProvider,
    private val groupStore: GroupStore? = null,
    private val senderKeyStore: SenderKeyStore? = null,
    /** 32-byte Ed25519 identity public key; required for group encryption derivation. */
    private val localIdentityPublicKey: ByteArray? = null,
    /** Required for 1:1 encryption derivation. */
    private val peerId: String? = null,
    /** Read-only use only: encryption derivation checks established session presence. */
    private val sessionStore: InMemorySessionStore? = null,
    private val invalidations: ConversationStateInvalidationSources = ConversationStateInvalidationSources(),
    private val debugTrace: ConversationStateDebugTrace = NoOpConversationStateDebugTrace,
) {

    fun snapshot(): ConversationSnapshot {
        val group = groupStore?.getByConversationId(conversationId)
        val conversationType = if (group != null) ConversationType.GROUP else ConversationType.ONE_TO_ONE

        val pendingCount = ledger.conversationView(conversationId).messages.count { view ->
            when (view.state) {
                MessageLifecycle.QUEUED,
                MessageLifecycle.SENT,
                -> true

                MessageLifecycle.RECEIVED,
                MessageLifecycle.ACKED,
                is MessageLifecycle.FAILED_TERMINAL,
                -> false
            }
        }

        val lastActivity = messageStore.conversationTimeline(conversationId)
            .messages
            .maxOfOrNull { it.timestamps.updatedAtElapsedMs }
            ?: 0L

        val (health, healthReason) = deriveHealth(connectionStateProvider.state, pendingCount)
        val (encryptionStatus, encryptionReason) = when (conversationType) {
            ConversationType.ONE_TO_ONE -> deriveOneToOneEncryption()
            ConversationType.GROUP -> deriveGroupEncryption(group)
        }

        debugTrace.onDerived(
            conversationId = conversationId,
            conversationType = conversationType,
            health = health,
            healthReason = healthReason,
            encryptionStatus = encryptionStatus,
            encryptionReason = encryptionReason,
            pendingMessageCount = pendingCount,
            lastActivityTimestamp = lastActivity,
        )

        return ConversationSnapshot(
            conversationId = conversationId,
            conversationType = conversationType,
            health = health,
            encryptionStatus = encryptionStatus,
            pendingMessageCount = pendingCount,
            lastActivityTimestamp = lastActivity,
        )
    }

    /**
     * Emits a new snapshot whenever any observed subsystem changes.
     */
    fun observe(): Flow<ConversationSnapshot> {
        val connectionTicks: Flow<Unit> = connectionStateProvider.stateFlow.map { Unit }
        val ticks = merge(
            connectionTicks,
            invalidations.messageStore,
            invalidations.ledger,
            invalidations.groupStore,
            invalidations.senderKeyStore,
            invalidations.manual,
        )

        return ticks
            .onStart { emit(Unit) }
            .map { snapshot() }
            .distinctUntilChanged()
    }

    private fun deriveHealth(connection: ConnectionState, pendingMessageCount: Int): Pair<ConversationHealth, String?> {
        return when (connection) {
            ConnectionState.PeerOffline -> ConversationHealth.OFFLINE to "peer_offline"

            ConnectionState.DirectReady,
            ConnectionState.RelayReady,
            -> {
                if (pendingMessageCount > 0) ConversationHealth.DEGRADED to "pending_delivery_backlog"
                else ConversationHealth.ACTIVE to null
            }

            ConnectionState.DirectConnecting -> {
                if (pendingMessageCount > 0) ConversationHealth.DEGRADED to "connecting_with_backlog"
                else ConversationHealth.DEGRADED to "connecting"
            }

            ConnectionState.Unknown -> {
                if (pendingMessageCount > 0) ConversationHealth.DEGRADED to "unknown_with_backlog"
                else ConversationHealth.DEGRADED to "unknown"
            }
        }
    }

    private fun deriveOneToOneEncryption(): Pair<ConversationEncryptionStatus, String?> {
        val pid = peerId
        val store = sessionStore
        if (pid == null || store == null) return ConversationEncryptionStatus.SESSION_INVALID to "missing_session_dependencies"

        return if (store.findEstablished(pid, conversationId) != null) {
            ConversationEncryptionStatus.OK to null
        } else {
            ConversationEncryptionStatus.SESSION_INVALID to "no_established_session"
        }
    }

    private fun deriveGroupEncryption(group: com.ivor.kriptex.deliverypolicy.group.GroupDefinition?): Pair<ConversationEncryptionStatus, String?> {
        val gs = group ?: return ConversationEncryptionStatus.SESSION_INVALID to "unknown_group"

        val sk = senderKeyStore
        val local = localIdentityPublicKey
        if (sk == null || local == null) return ConversationEncryptionStatus.SESSION_INVALID to "missing_group_dependencies"

        // Require sender keys for all members, including local, to avoid silent decrypt failures.
        val missing = gs.memberIdentityPublicKeys.firstOrNull { memberKey ->
            sk.get(gs.groupId, memberKey) == null
        }

        return if (missing == null) {
            ConversationEncryptionStatus.OK to null
        } else {
            // Avoid logging keys; just say which category is missing.
            val isLocalMissing = missing.contentEquals(local)
            val reason = if (isLocalMissing) "missing_local_sender_key" else "missing_member_sender_key"
            ConversationEncryptionStatus.MISSING_KEYS to reason
        }
    }
}
